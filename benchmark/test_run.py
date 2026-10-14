"""Checks orchestration and reporting without requiring native builds or network access."""
import importlib.util
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("benchmark_runner", Path(__file__).with_name("run.py"))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class ComparisonTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.work = Path(self.temporary.name)
        (self.work / "fixtures").mkdir()
        self.cases = [{"id": fmt, "format": fmt, "path": f"/{fmt}.audio", "bytes": 100,
                       "iterations": 2, "writes": writes}
                      for fmt, writes in [("MPEG", 3), ("OGG_OPUS", 3), ("DSDIFF", 3),
                                          ("MOD", 1), ("SHORTEN", 0)]]
        self.profile = {"readWarmup": 0, "readSamples": 1, "writeWarmup": 0, "writeSamples": 1}
        self.commands = []

    def worker_rows(self, command, work, label):
        engine, mode, manifest = command[:3]
        self.commands.append((engine, mode))
        records = [{"kind": "environment", "version": "test"}]
        if engine == "jaudiotagger":
            self.assertEqual([line.split("\t")[0] for line in Path(manifest).read_text().splitlines()], ["MPEG"])
        for line in Path(manifest).read_text().splitlines():
            identity, _, iterations, writes = line.split("\t")
            workloads = ["read"] if mode == "read" else [workload for bit, workload in enumerate(("title", "growth"))
                                                        if int(writes) & (1 << bit)]
            for workload in workloads:
                operations = int(iterations) if mode == "read" else 1
                records.append({"kind": "timing", "id": identity, "workload": workload, "sample": 0,
                                "nanos": (runner.ENGINES.index(engine) + 1) * 1000 * operations,
                                "iterations": operations})
        return records

    def run_suite(self, worker):
        with patch.object(runner, "source_cases", return_value=(self.cases, self.profile)), \
             patch.object(runner, "rows", side_effect=worker), patch("builtins.print"):
            return runner.run_suite("fixtures", {engine: [engine] for engine in runner.ENGINES},
                                    self.work, SimpleNamespace(runs=3))

    def test_three_engines_with_partial_format_coverage(self):
        suite = self.run_suite(self.worker_rows)
        self.assertEqual(set(suite["workers"]), set(runner.ENGINES))
        self.assertEqual([engine for engine, mode in self.commands if mode == "read"],
                         ["native", "java", "jaudiotagger", "java", "jaudiotagger", "native",
                          "jaudiotagger", "native", "java"])
        for result in suite["results"]:
            jaudiotagger = result["jaudiotagger"]
            if result["id"] == "MPEG":
                self.assertTrue(jaudiotagger["supported"])
                self.assertEqual(jaudiotagger["medianUs"], 3)
                self.assertEqual(result["medianJaudiotaggerOverJava"], 1.5)
                self.assertEqual(result["meanJaudiotaggerOverNative"], 3)
                self.assertEqual(len(jaudiotagger["runs"]), 3)
            else:
                self.assertFalse(jaudiotagger["supported"])
                self.assertIn("reason", jaudiotagger)
                self.assertNotIn("medianUs", jaudiotagger)
                self.assertNotIn("medianJaudiotaggerOverJava", result)

    def test_missing_jaudiotagger_timings_fail_the_run(self):
        def missing(command, work, label):
            records = self.worker_rows(command, work, label)
            return records[:1] if command[0] == "jaudiotagger" else records

        with self.assertRaisesRegex(RuntimeError, "Missing or invalid timing samples"):
            self.run_suite(missing)


class CorpusCategoryTest(unittest.TestCase):
    def test_reused_corpus_is_split_by_size_and_rejects_wrong_sizes(self):
        with tempfile.TemporaryDirectory() as temporary:
            work = Path(temporary)
            corpus = work / "input"
            corpus.mkdir()
            for size in (4, 40):
                with (corpus / f"pink-{size:03d}mib.mp3").open("wb") as stream:
                    stream.truncate(size * 1_048_576)
            args = SimpleNamespace(large_corpus=corpus)
            with patch.object(runner, "EXTENSIONS", {"mp3": "MPEG"}):
                for size in (4, 40):
                    cases, profile = runner.source_cases(f"{size}MiB", {}, work, args)
                    self.assertEqual(len(cases), 1)
                    self.assertEqual(cases[0]["id"], f"MPEG-{size:03d}MiB")
                    self.assertEqual(cases[0]["label"], f"MP3 ({size} MiB)")
                    self.assertEqual(cases[0]["bytes"], size * 1_048_576)
                    self.assertEqual(Path(cases[0]["path"]).stat().st_size, size * 1_048_576)
                    self.assertEqual(profile, runner.LARGE)
                (corpus / "pink-004mib.mp3").write_bytes(b"wrong size")
                with self.assertRaisesRegex(RuntimeError, "outside the 4 MiB category"):
                    runner.source_cases("4MiB", {}, work / "invalid", args)


if __name__ == "__main__":
    unittest.main()
