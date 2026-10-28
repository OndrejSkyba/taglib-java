#!/usr/bin/env python3
"""Build and run the serial TagLib Java / C++ / GraalVM / jaudiotagger metadata benchmark."""
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
FORMAT_LABELS = {
    "MPEG": "MP3", "FLAC": "FLAC",
    "OGG_VORBIS": "Ogg Vorbis", "OGG_OPUS": "Ogg Opus",
    "OGG_SPEEX": "Ogg Speex", "OGG_FLAC": "Ogg FLAC",
    "MP4": "M4A (MP4 audio)", "ASF": "WMA (ASF)", "WAV": "WAV",
    "AIFF": "AIFF", "APE": "Monkey's Audio (APE)", "WAVPACK": "WavPack",
    "MPC": "Musepack (MPC)", "TRUEAUDIO": "True Audio (TTA)", "DSF": "DSF (DSD audio)",
    "DSDIFF": "DSDIFF (DFF)", "MOD": "Tracker module (MOD)", "S3M": "Tracker module (S3M)",
    "IT": "Tracker module (IT)", "XM": "Tracker module (XM)", "MATROSKA": "Matroska audio (MKA)",
    "SHORTEN": "Shorten (SHN)",
}
EXTENSIONS = {"mp3": "MPEG", "flac": "FLAC", "ogg": "OGG_VORBIS",
              "opus": "OGG_OPUS", "m4a": "MP4", "wav": "WAV", "aiff": "AIFF"}
TRACKERS = {"MOD", "S3M", "IT", "XM"}
WORKLOADS = ("read", "title", "growth")
ENGINES = ("native", "java", "graalvm", "jaudiotagger")
ENGINE_LABELS = {"native": "C++ TagLib", "java": "TagLib Java", "graalvm": "TagLib Java (Native Image)",
                 "jaudiotagger": "jaudiotagger"}
NATIVE_IMAGE_OPTIONS = ["-O3", "-march=compatibility", "--gc=serial"]
LARGE_SUITES = {"4MiB": 4, "40MiB": 40}
SUITE_LABELS = {"fixtures": "Small samples", "4MiB": "4 MiB", "40MiB": "40 MiB"}
JAUDIOTAGGER = {
    "version": "3.0.1",
    "url": "https://repo.maven.apache.org/maven2/net/jthink/jaudiotagger/3.0.1/jaudiotagger-3.0.1.jar",
    "sha256": "68aa0fe601b05df683a843acf6f8d4793ab476f47da1ac3dab57e0b1886b0aee",
}
JAUDIOTAGGER_FORMATS = {"MPEG", "FLAC", "OGG_VORBIS", "MP4", "ASF", "WAV", "AIFF", "DSF"}
PRIMARY = {"readWarmup": 20, "readSamples": 31, "readIterations": 200,
           "writeWarmup": 100, "writeSamples": 101}
LARGE = {"readWarmup": 100, "readSamples": 31, "readIterations": 5,
         "writeWarmup": 30, "writeSamples": 31}
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


def case_label(identity, fmt):
    size = re.fullmatch(rf"{fmt}-(\d+)MiB", identity)
    label = FORMAT_LABELS[fmt]
    return f"{label} ({int(size[1])} MiB)" if size else label


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


def jaudiotagger_unsupported(fmt):
    version = JAUDIOTAGGER["version"]
    if fmt == "DSDIFF":
        return (f"jaudiotagger {version} reads DSDIFF audio properties only, without tags or artwork; "
                "it has no DSDIFF writer")
    if fmt in {"OGG_OPUS", "OGG_SPEEX", "OGG_FLAC"}:
        return (f"jaudiotagger {version}'s Ogg reader/writer supports Vorbis only, "
                f"without {FORMAT_LABELS[fmt]} metadata support")
    if fmt not in JAUDIOTAGGER_FORMATS:
        extension = Path(FIXTURES[fmt]).suffix
        return f"jaudiotagger {version} has no registered reader/writer for {FORMAT_LABELS[fmt]} ({extension})"
    return None


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
    if args.graalvm_home:
        native_image = args.graalvm_home.resolve() / "bin/native-image"
        if not native_image.is_file() or not os.access(native_image, os.X_OK):
            raise RuntimeError("--graalvm-home must contain executable bin/native-image")
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
    jaudiotagger = work / f"jaudiotagger-{JAUDIOTAGGER['version']}.jar"
    download(JAUDIOTAGGER["url"], jaudiotagger)
    if sha(jaudiotagger) != JAUDIOTAGGER["sha256"]:
        raise RuntimeError("jaudiotagger archive checksum mismatch")
    classes = work / "java"
    classes.mkdir()
    classpath = os.pathsep.join(map(str, [classes, taglib]))
    javac = str(Path(os.environ["JAVA_HOME"]) / "bin/javac") if os.environ.get("JAVA_HOME") else "javac"
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else "java"
    invoke([javac, "-cp", classpath, "-d", str(classes),
            str(ROOT / "benchmark/java/AllFormatsBenchmark.java")], work, "javac")
    jaudiotagger_classpath = os.pathsep.join(map(str, [classes, jaudiotagger]))
    invoke([javac, "-cp", jaudiotagger_classpath, "-d", str(classes),
            str(ROOT / "benchmark/java/JaudiotaggerBenchmark.java")], work, "javac-jaudiotagger")
    tools = {"java": [java, "-Xms512m", "-Xmx512m", "-cp", classpath,
                      "AllFormatsBenchmark"],
            "native": [str(build / "taglib-benchmark")],
            "jaudiotagger": [java, "-Xms512m", "-Xmx512m",
                             f"-Djaudiotagger.version={JAUDIOTAGGER['version']}",
                             "-cp", jaudiotagger_classpath, "JaudiotaggerBenchmark"],
            "upstream": upstream, "pin": pin,
            "artifacts": {"javaLibrarySha256": sha(taglib),
                          "javaWorkerSha256": sha(classes / "AllFormatsBenchmark.class"),
                          "jaudiotaggerLibrarySha256": sha(jaudiotagger),
                          "jaudiotaggerWorkerSha256": sha(classes / "JaudiotaggerBenchmark.class"),
                          "nativeWorkerSha256": sha(build / "taglib-benchmark")}}
    if args.graalvm_home:
        print("Building TagLib Java benchmark with GraalVM Native Image (-O3)", flush=True)
        executable = work / "taglib-graalvm-benchmark"
        invoke([str(native_image), *NATIVE_IMAGE_OPTIONS, "--parallelism=4", "-J-Xmx4g",
                "-cp", classpath, "-o", str(executable), "AllFormatsBenchmark"], work, "native-image")
        tools["graalvm"] = [str(executable), "-Xms512m", "-Xmx512m"]
        tools["graalvmBuild"] = {
            "version": invoke([str(native_image), "--version"], work, "native-image-version").strip(),
            "options": NATIVE_IMAGE_OPTIONS,
        }
        tools["artifacts"]["graalvmWorkerSha256"] = sha(executable)
    return tools


def source_cases(suite, tools, work, args):
    profile = dict(PRIMARY if suite == "fixtures" else LARGE)
    if suite == "fixtures":
        # Guard coverage when a new format is added to the Java API.
        formats = (ROOT / "taglib/src/main/java/cz/skyba/taglib/Format.java").read_text().split("{", 1)[1].split(";", 1)[0]
        if set(re.findall(r"\b[A-Z][A-Z_0-9]*\b", formats)) != set(FIXTURES):
            raise RuntimeError("Benchmark fixture coverage does not match Format.java")
        inputs = [(fmt, fmt, tools["upstream"] / "tests/data" / name) for fmt, name in FIXTURES.items()]
    else:
        corpus = args.large_corpus.resolve() if args.large_corpus else work / "synthetic"
        if not args.large_corpus and not corpus.exists():
            print("Generating 4 MiB / 40 MiB synthetic corpus", flush=True)
            env = dict(os.environ, CORPUS=str(corpus), SIZES_MIB=",".join(map(str, LARGE_SUITES.values())),
                       FORMATS=",".join(EXTENSIONS))
            invoke([sys.executable, str(ROOT / "benchmark/helpers/generate_corpus.py")], work, "generate", env=env)
        size = LARGE_SUITES[suite]
        inputs = [(f"{fmt}-{size:03d}MiB", fmt, corpus / f"pink-{size:03d}mib.{ext}")
                  for ext, fmt in EXTENSIONS.items()]
    target = work / suite / "corpus"
    target.mkdir(parents=True)
    cases = []
    for identity, fmt, source in inputs:
        source_bytes = source.stat().st_size
        if suite != "fixtures" and not size * 1_048_576 * 0.85 <= source_bytes <= size * 1_048_576 * 1.15:
            raise RuntimeError(f"{source.name} is outside the {size} MiB category's size range")
        path = target / (identity + source.suffix)
        shutil.copyfile(source, path)
        cases.append({"id": identity, "label": case_label(identity, fmt), "format": fmt,
                      "path": str(path), "sourceName": source.name, "bytes": source_bytes,
                      "iterations": profile["readIterations"],
                      "writes": 0 if fmt == "SHORTEN" else 1 if fmt in TRACKERS else 3})
    return cases, profile


def run_suite(suite, tools, work, args):
    cases, profile = source_cases(suite, tools, work, args)
    area = work / suite
    files = area / "cases.tsv"
    manifest(files, cases)
    jaudiotagger_files = area / "jaudiotagger-cases.tsv"
    jaudiotagger_cases = [case for case in cases if not jaudiotagger_unsupported(case["format"])]
    manifest(jaudiotagger_files, jaudiotagger_cases)
    timings = collections.defaultdict(dict)
    environments = {}
    available_engines = tuple(engine for engine in ENGINES if engine in tools)
    for run in range(args.runs):
        offset = run % len(available_engines)
        engines = available_engines[offset:] + available_engines[:offset]
        for mode in ("read", "write"):
            for engine in engines:
                if engine == "jaudiotagger" and not jaudiotagger_cases:
                    continue
                output = area / f"{engine}-{mode}"
                output.mkdir(exist_ok=True)
                suite_label = SUITE_LABELS[suite]
                mode_label = "read metadata" if mode == "read" else "update metadata"
                print(f"{suite_label}: run {run + 1}/{args.runs}, {ENGINE_LABELS[engine]} — {mode_label}", flush=True)
                engine_files = jaudiotagger_files if engine == "jaudiotagger" else files
                records = rows(tools[engine] + [mode, str(engine_files), str(output),
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
            record = {"id": case["id"], "label": case_label(case["id"], case["format"]),
                      "format": case["format"], "bytes": case["bytes"], "workload": workload,
                      "supported": workload == "read" or supported(case["format"], workload)}
            if record["supported"]:
                for engine in available_engines:
                    if engine == "jaudiotagger":
                        continue
                    runs = timings[(engine, case["id"], workload)]
                    record[engine] = statistics_for_runs([runs.get(index, []) for index in range(args.runs)])
                record["medianJavaOverNative"] = record["java"]["medianUs"] / record["native"]["medianUs"]
                record["meanJavaOverNative"] = record["java"]["meanUs"] / record["native"]["meanUs"]
            reason = jaudiotagger_unsupported(case["format"])
            record["jaudiotagger"] = {"supported": reason is None}
            if reason:
                record["jaudiotagger"]["reason"] = reason
            else:
                runs = timings[("jaudiotagger", case["id"], workload)]
                record["jaudiotagger"].update(statistics_for_runs([runs.get(index, []) for index in range(args.runs)]))
                for reference in ("java", "native"):
                    for metric in ("median", "mean"):
                        record[f"{metric}JaudiotaggerOver{reference.title()}"] = (
                            record["jaudiotagger"][f"{metric}Us"] / record[reference][f"{metric}Us"])
            if "graalvm" in tools and record["supported"]:
                for reference in ("native", "java", "jaudiotagger"):
                    if reference == "jaudiotagger" and not record["jaudiotagger"]["supported"]:
                        continue
                    for metric in ("median", "mean"):
                        record[f"{metric}GraalvmOver{reference.title()}"] = (
                            record["graalvm"][f"{metric}Us"] / record[reference][f"{metric}Us"])
            results.append(record)
    return {"name": suite, "label": SUITE_LABELS[suite], "settings": dict(profile, runs=args.runs), "results": results,
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
            for suite in LARGE_SUITES:
                suites.append(run_suite(suite, tools, work, args))
        cpu = next(line.split(":", 1)[1].strip() for line in Path("/proc/cpuinfo").read_text().splitlines()
                   if line.startswith("model name"))
        operating_system = os.uname()
        machine = {"cpu": cpu, "os": f"{operating_system.sysname} {operating_system.release} {operating_system.machine}",
                   "storage": storage_type(work)}
        result = {"schemaVersion": 2, "status": "complete", "machine": machine,
                  "jaudiotagger": JAUDIOTAGGER,
                  "upstream": tools["pin"], "artifacts": tools["artifacts"], "suites": suites}
        if "graalvmBuild" in tools:
            result["graalvm"] = tools["graalvmBuild"]
        publish(result, work, output=output_directory / "result.json")
        print(f"Complete: {output_directory / 'result.json'}", flush=True)


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runs", type=int, default=4, help="Fresh processes per engine (default: 4, minimum: 2)")
    parser.add_argument("--large", action="store_true", help="Also measure the 4 MiB and 40 MiB categories")
    parser.add_argument("--large-corpus", type=Path, help="Reuse an existing pink-{004,040}mib corpus")
    parser.add_argument("--skip-build", action="store_true", help="Reuse already-built Java artifacts")
    parser.add_argument("--native-tools", type=Path, help="Use a directory containing a prebuilt taglib-benchmark")
    parser.add_argument("--graalvm-home", type=Path, help="Also build and measure Native Image using this GraalVM JDK")
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
