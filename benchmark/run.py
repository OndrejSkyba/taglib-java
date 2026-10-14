#!/usr/bin/env python3
"""Build and run the serial Java / C++ metadata benchmark."""
import argparse
import collections
import fcntl
import hashlib
import itertools
import json
import os
from pathlib import Path
import re
import shutil
import signal
import statistics
import subprocess
import sys
import tempfile
import tarfile
import time
import urllib.request
import zipfile

sys.dont_write_bytecode = True

ROOT = Path(__file__).resolve().parent.parent
RESULTS = ROOT / "benchmark/results"
FIXTURES = {
    "MPEG": "lame_cbr.mp3", "FLAC": "sinewave.flac",
    "OGG_VORBIS": "test.ogg", "OGG_OPUS": "correctness_gain_silent_output.opus",
    "OGG_SPEEX": "empty.spx", "OGG_FLAC": "empty_flac.oga",
    "MP4": "has-tags.m4a", "ASF": "silence-1.wma", "WAV": "empty.wav",
    "AIFF": "noise.aif", "APE": "mac-399.ape", "WAVPACK": "click.wv",
    "MPC": "click.mpc", "TRUEAUDIO": "tagged.tta", "DSF": "empty10ms.dsf",
    "DSDIFF": "empty10ms.dff", "MOD": "test.mod", "S3M": "test.s3m",
    "IT": "test.it", "XM": "test.xm", "MATROSKA": "no-tags.mka",
    "SHORTEN": "2sec-silence.shn",
}
EXTENSIONS = {"mp3": "MPEG", "flac": "FLAC", "ogg": "OGG_VORBIS",
              "opus": "OGG_OPUS", "m4a": "MP4", "wav": "WAV", "aiff": "AIFF"}
TRACKERS = {"MOD", "S3M", "IT", "XM"}
WORKLOADS = ("read", "title", "growth")
PRIMARY = {"readWarmup": 20, "readSamples": 31, "readIterations": 200,
           "writeWarmup": 100, "writeSamples": 101}
LARGE = {"readWarmup": 10, "readSamples": 31, "readIterations": 5,
         "writeWarmup": 10, "writeSamples": 31}
LOG_SEQUENCE = itertools.count()


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def invoke(command, work, label, *, timeout=1800, env=None):
    """Keep child processes and logs inside the run's lifetime, including interruption."""
    logs = work / "logs"
    logs.mkdir(exist_ok=True)
    prefix = logs / f"{next(LOG_SEQUENCE):04d}-{label}"
    stdout = prefix.with_suffix(".stdout")
    stderr = prefix.with_suffix(".stderr")
    with stdout.open("w") as out, stderr.open("w") as err:
        process = subprocess.Popen(command, cwd=ROOT, stdout=out, stderr=err,
                                   start_new_session=True, env=env)
        try:
            status = process.wait(timeout=timeout)
        except BaseException:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait()
            raise
    if status:
        detail = (stderr.read_text() + stdout.read_text())[-6000:]
        raise RuntimeError(f"{label} failed (exit {status}):\n{detail}")
    return stdout.read_text()


def rows(command, work, label):
    return [json.loads(line) for line in invoke(command, work, label).splitlines() if line]


def download(url, path):
    for attempt in range(3):
        try:
            with urllib.request.urlopen(url, timeout=60) as response, path.open("wb") as output:
                shutil.copyfileobj(response, output)
            return
        except OSError:
            path.unlink(missing_ok=True)
            if attempt == 2:
                raise
            time.sleep(attempt + 1)


def extract_tar(archive, destination):
    with tarfile.open(archive, "r:gz") as bundle:
        # Only regular files/directories are needed from the checksum-pinned release.
        for member in bundle.getmembers():
            target = (destination / member.name).resolve()
            if not target.is_relative_to(destination.resolve()) or not (member.isfile() or member.isdir()):
                raise RuntimeError(f"Unsupported archive entry: {member.name}")
        bundle.extractall(destination)


def maven_command(work):
    properties = (ROOT / ".mvn/wrapper/maven-wrapper.properties").read_text()
    url = re.search(r"^distributionUrl=(.+)$", properties, re.M)[1].strip()
    name = url.rsplit("/", 1)[1].removesuffix("-bin.zip")
    cache = Path(os.environ.get("MAVEN_USER_HOME", str(Path.home() / ".m2"))) / "wrapper/dists" / name
    cached = sorted(cache.glob("*/bin/mvn"))
    if cached:
        return str(cached[0])
    # Bootstrap the wrapper's exact Maven version without curl, wget, unzip or tar.
    archive = work / "maven.zip"
    download(url, archive)
    pinned = re.search(r"^distributionSha256Sum=(\S+)$", properties, re.M)
    if pinned:
        if sha(archive) != pinned[1]:
            raise RuntimeError("Maven archive checksum mismatch")
    else:
        checksum = work / "maven.sha512"
        download(url + ".sha512", checksum)
        expected = checksum.read_text().split()[0].lower()
        with archive.open("rb") as stream:
            actual = hashlib.file_digest(stream, "sha512").hexdigest()
        if not re.fullmatch(r"[0-9a-f]{128}", expected) or actual != expected:
            raise RuntimeError("Maven archive checksum mismatch")
    target = work / "maven"
    target.mkdir()
    with zipfile.ZipFile(archive) as bundle:
        for member in bundle.infolist():
            if not (target / member.filename).resolve().is_relative_to(target.resolve()):
                raise RuntimeError("Invalid Maven archive path")
        bundle.extractall(target)
    executable = target / name / "bin/mvn"
    executable.chmod(executable.stat().st_mode | 0o100)
    return str(executable)


def manifest(path, cases):
    path.write_text("".join(f"{c['id']}\t{c['path']}\t{c['iterations']}\t{c['writes']}\n" for c in cases))


def statistics_for_runs(runs):
    """Equal weight per process: median of run medians, mean of run means."""
    by_run = []
    for samples in runs:
        values = [r["nanos"] / r["iterations"] / 1000 for r in samples]
        if not values or any(value <= 0 for value in values):
            raise RuntimeError("Missing or invalid timing samples")
        by_run.append({"medianUs": statistics.median(values), "meanUs": statistics.mean(values),
                       "samples": samples})
    return {"medianUs": statistics.median(r["medianUs"] for r in by_run),
            "meanUs": statistics.mean(r["meanUs"] for r in by_run), "runs": by_run}


def supported(fmt, workload):
    return fmt != "SHORTEN" and not (fmt in TRACKERS and workload == "growth")


def prepare(work, args):
    required = ["java", "javac"]
    if args.large and not args.large_corpus:
        required += ["ffmpeg"]
    if not args.native_tools:
        required += ["cmake", "c++"]
    if args.native_tools:
        executable = args.native_tools / "taglib-benchmark"
        if not executable.is_file() or not os.access(executable, os.X_OK):
            raise RuntimeError("--native-tools must contain executable taglib-benchmark")
    for tool in required:
        if not shutil.which(tool) and not (tool in ("java", "javac") and os.environ.get("JAVA_HOME")):
            raise RuntimeError(f"Required tool is unavailable: {tool}")
    if not args.skip_build:
        print("Building Java artifacts", flush=True)
        invoke([maven_command(work), "-q", "-pl", "taglib", "-am",
                "-Dmaven.test.skip=true", "clean", "package"], work, "maven")
    upstream_env = (ROOT / "parity/native/upstream.env").read_text()
    pin = {key: re.search(rf"^{key}=(\S+)$", upstream_env, re.M)[1]
           for key in ("TAGLIB_VERSION", "TAGLIB_SHA256", "TAGLIB_URL")}
    archive = ROOT / f"work/upstream/taglib-{pin['TAGLIB_VERSION']}.tar.gz"
    if not archive.is_file():
        archive = work / "taglib.tar.gz"
        download(pin["TAGLIB_URL"], archive)
    if sha(archive) != pin["TAGLIB_SHA256"]:
        raise RuntimeError("Upstream archive checksum mismatch")
    # The pinned archive is extracted into this run; shared source caches are not rewritten.
    upstream = work / f"taglib-{pin['TAGLIB_VERSION']}"
    extract_tar(archive, work)
    build = args.native_tools.resolve() if args.native_tools else work / "native"
    if not args.native_tools:
        print(f"Building original C++ TagLib {pin['TAGLIB_VERSION']} (Release)", flush=True)
        invoke(["cmake", "-S", str(ROOT / "benchmark/native"), "-B", str(build),
                "-DCMAKE_BUILD_TYPE=Release", f"-DTAGLIB_SOURCE={upstream}"], work, "cmake")
        invoke(["cmake", "--build", str(build), "--target", "taglib-benchmark",
                "--parallel", str(min(4, os.cpu_count() or 1))], work, "native-build")
    native_version = json.loads(invoke([str(build / "taglib-benchmark"), "version"], work, "native-version"))
    if native_version.get("version") != pin["TAGLIB_VERSION"]:
        raise RuntimeError("Native tools do not match the pinned TagLib version")
    artifact_name = invoke([maven_command(work), "-q", "-pl", "taglib", "help:evaluate",
                            "-Dexpression=project.build.finalName", "-DforceStdout"],
                           work, "maven-artifact-name").strip()
    taglib = ROOT / "taglib/target" / f"{artifact_name}.jar"
    if not taglib.is_file():
        raise RuntimeError("Missing Java library; run without --skip-build")
    classes = work / "java"
    classes.mkdir()
    classpath = os.pathsep.join(map(str, [classes, taglib]))
    javac = str(Path(os.environ["JAVA_HOME"]) / "bin/javac") if os.environ.get("JAVA_HOME") else "javac"
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else "java"
    invoke([javac, "-cp", classpath, "-d", str(classes),
            str(ROOT / "benchmark/java/AllFormatsBenchmark.java")], work, "javac")
    return {"java": [java, "-Xms512m", "-Xmx512m", "-cp", classpath,
                      "AllFormatsBenchmark"],
            "native": [str(build / "taglib-benchmark")],
            "upstream": upstream, "pin": pin,
            "artifacts": {"javaLibrarySha256": sha(taglib),
                          "javaWorkerSha256": sha(classes / "AllFormatsBenchmark.class"),
                          "nativeWorkerSha256": sha(build / "taglib-benchmark")}}


def source_cases(suite, tools, work, args):
    profile = dict(PRIMARY if suite == "fixtures" else LARGE)
    if suite == "fixtures":
        # Guard coverage when a new format is added to the Java API.
        formats = (ROOT / "taglib/src/main/java/cz/skyba/taglib/Format.java").read_text().split("{", 1)[1].split("}", 1)[0]
        if set(re.findall(r"\b[A-Z][A-Z_0-9]*\b", formats)) != set(FIXTURES):
            raise RuntimeError("Benchmark fixture coverage does not match Format.java")
        inputs = [(fmt, fmt, tools["upstream"] / "tests/data" / name) for fmt, name in FIXTURES.items()]
    else:
        corpus = args.large_corpus.resolve() if args.large_corpus else work / "synthetic"
        if not args.large_corpus:
            print("Generating 1/10/50 MiB synthetic corpus", flush=True)
            env = dict(os.environ, CORPUS=str(corpus), SIZES_MIB="1,10,50", FORMATS=",".join(EXTENSIONS))
            invoke([sys.executable, str(ROOT / "benchmark/helpers/generate_corpus.py")], work, "generate", env=env)
        inputs = [(f"{fmt}-{size:03d}MiB", fmt, corpus / f"pink-{size:03d}mib.{ext}")
                  for size in (1, 10, 50) for ext, fmt in EXTENSIONS.items()]
    target = work / suite / "corpus"
    target.mkdir(parents=True)
    cases = []
    for identity, fmt, source in inputs:
        path = target / (identity + source.suffix)
        shutil.copyfile(source, path)
        cases.append({"id": identity, "format": fmt, "path": str(path), "sourceName": source.name, "bytes": source.stat().st_size,
                      "iterations": profile["readIterations"],
                      "writes": 0 if fmt == "SHORTEN" else 1 if fmt in TRACKERS else 3})
    return cases, profile


def run_suite(suite, tools, work, args):
    cases, profile = source_cases(suite, tools, work, args)
    area = work / suite
    files = area / "cases.tsv"
    manifest(files, cases)
    timings = collections.defaultdict(dict)
    environments = {}
    for run in range(args.runs):
        engines = ("native", "java") if run % 2 == 0 else ("java", "native")
        for mode in ("read", "write"):
            for engine in engines:
                output = area / f"{engine}-{mode}"
                output.mkdir(exist_ok=True)
                print(f"{suite}: run {run + 1}/{args.runs}, {engine} {mode}", flush=True)
                records = rows(tools[engine] + [mode, str(files), str(output),
                               str(profile[f"{mode}Warmup"]), str(profile[f"{mode}Samples"])],
                               work, f"{suite}-{engine}-{mode}-{run}")
                environments[engine] = records[0]
                for record in records[1:]:
                    key = (engine, record["id"], record["workload"])
                    timings[key].setdefault(run, []).append(
                        {"sample": record["sample"], "nanos": record["nanos"], "iterations": record["iterations"]})
                if mode == "write":
                    shutil.rmtree(output)
    results = []
    for case in cases:
        for workload in WORKLOADS:
            record = {"id": case["id"], "format": case["format"], "bytes": case["bytes"], "workload": workload,
                      "supported": workload == "read" or supported(case["format"], workload)}
            if record["supported"]:
                for engine in ("native", "java"):
                    runs = timings[(engine, case["id"], workload)]
                    record[engine] = statistics_for_runs([runs[index] for index in range(args.runs)])
                record["medianJavaOverNative"] = record["java"]["medianUs"] / record["native"]["medianUs"]
                record["meanJavaOverNative"] = record["java"]["meanUs"] / record["native"]["meanUs"]
            results.append(record)
    return {"name": suite, "settings": dict(profile, runs=args.runs), "results": results,
            "cases": [{k: v for k, v in case.items() if k != "path"} for case in cases], "workers": environments}


def publish(result, work, output=RESULTS / "result.json"):
    """Atomically replace the latest completed JSON result."""
    staged = work / "result.json"
    staged.write_text(json.dumps(result, indent=2) + "\n")
    os.replace(staged, output)


def storage_type(path):
    """Report the storage medium without retaining device/partition identifiers."""
    device = path.stat().st_dev
    sys_device = Path(f"/sys/dev/block/{os.major(device)}:{os.minor(device)}")
    try:
        block = sys_device.resolve(strict=True)
        for parent in (block, *block.parents):
            rotational = parent / "queue/rotational"
            if rotational.is_file():
                if rotational.read_text().strip() == "1":
                    return "HDD"
                return "NVMe SSD" if parent.name.startswith("nvme") else "SSD"
    except OSError:
        pass
    return "Unknown"


def execute(args, output_directory=RESULTS):
    output_directory.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".run-", dir=output_directory) as temporary:
        work = Path(temporary)
        tools = prepare(work, args)
        suites = [run_suite("fixtures", tools, work, args)]
        if args.large:
            suites.append(run_suite("large", tools, work, args))
        cpu = next(line.split(":", 1)[1].strip() for line in Path("/proc/cpuinfo").read_text().splitlines()
                   if line.startswith("model name"))
        operating_system = os.uname()
        machine = {"cpu": cpu, "os": f"{operating_system.sysname} {operating_system.release} {operating_system.machine}",
                   "storage": storage_type(work)}
        result = {"schemaVersion": 1, "status": "complete", "machine": machine,
                  "upstream": tools["pin"], "artifacts": tools["artifacts"], "suites": suites}
        publish(result, work, output=output_directory / "result.json")
        print(f"Complete: {output_directory / 'result.json'}", flush=True)


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runs", type=int, default=4, help="Fresh processes per engine (default: 4, minimum: 2)")
    parser.add_argument("--large", action="store_true", help="Also measure synthetic 1/10/50 MiB files")
    parser.add_argument("--large-corpus", type=Path, help="Reuse an existing pink-{001,010,050}mib corpus")
    parser.add_argument("--skip-build", action="store_true", help="Reuse already-built Java artifacts")
    parser.add_argument("--native-tools", type=Path, help="Use a directory containing a prebuilt taglib-benchmark")
    args = parser.parse_args(argv)
    if args.runs < 2:
        parser.error("--runs must be at least 2")
    if args.large_corpus and not args.large:
        parser.error("--large-corpus requires --large")
    return args


def main():
    args = parse_args()
    # Lock the existing script inode instead of retaining a separate output/lock file.
    with Path(__file__).open("r") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise RuntimeError("Another benchmark run is active") from None
        execute(args)


def interrupt(_signum, _frame):
    raise KeyboardInterrupt()


if __name__ == "__main__":
    signal.signal(signal.SIGTERM, interrupt)
    try:
        main()
    except KeyboardInterrupt:
        print("Benchmark interrupted; temporary files removed, previous result retained", file=sys.stderr)
        sys.exit(130)
    except Exception as error:
        print(f"Benchmark failed: {error}", file=sys.stderr)
        sys.exit(1)
