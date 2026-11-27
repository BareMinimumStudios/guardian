import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock

spec = importlib.util.spec_from_file_location('cache_check', Path(__file__).resolve().parents[1] / 'verify_configuration_cache.py')
cache = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cache)

COLD = ("Calculating task graph as configuration cache cannot be reused because the file system entry "
        "'../../../.gradle/caches/fabric-loom/mojang_versions_manifest.json' has been created.\n"
        "BUILD SUCCESSFUL\nConfiguration cache entry stored.\n")
HIT = 'BUILD SUCCESSFUL\nConfiguration cache entry reused.\n'


class ConfigurationCacheChecks(unittest.TestCase):
    def verify(self, outputs):
        runner = Mock(side_effect=[subprocess.CompletedProcess([], code, text) for code, text in outputs])
        with tempfile.TemporaryDirectory() as folder:
            status = cache.verify(Path(folder), run=runner, emit=lambda _: None)
            logs = {p.name: p.read_text() for p in (Path(folder) / 'build').glob('*.log')}
        return status, runner, logs

    def test_immediate_reuse_needs_one_build(self):
        status, run, logs = self.verify([(0, HIT)])
        self.assertEqual(status, 0)
        self.assertEqual(run.call_count, 1)
        self.assertIn('--max-workers=2', run.call_args.args[0])
        self.assertIn('--no-parallel', run.call_args.args[0])
        self.assertEqual(logs['configuration-cache-verification.log'], HIT)

    def test_exact_ci_cold_manifest_gets_one_retry(self):
        status, run, logs = self.verify([(0, COLD), (0, HIT)])
        self.assertEqual(status, 0)
        self.assertEqual(run.call_count, 2)
        self.assertEqual(logs['configuration-cache-verification-1.log'], COLD)
        self.assertEqual(logs['configuration-cache-verification-2.log'], HIT)

    def test_repeated_miss_fails_after_two_builds(self):
        status, run, _ = self.verify([(0, COLD), (0, COLD)])
        self.assertEqual(status, 1)
        self.assertEqual(run.call_count, 2)

    def test_discarded_entry_is_not_retried(self):
        output = COLD.replace('entry stored.', 'entry discarded.')
        status, run, _ = self.verify([(0, output)])
        self.assertEqual(status, 1)
        self.assertEqual(run.call_count, 1)

    def test_failed_build_is_not_hidden_by_reuse_message(self):
        status, run, _ = self.verify([(2, HIT)])
        self.assertEqual(status, 2)
        self.assertEqual(run.call_count, 1)

    def test_retry_build_failure_is_preserved(self):
        status, run, _ = self.verify([(0, COLD), (3, 'BUILD FAILED')])
        self.assertEqual(status, 3)
        self.assertEqual(run.call_count, 2)

    def test_actual_ci_temporary_manifest_removal_gets_one_retry(self):
        output = COLD.replace(
            "mojang_versions_manifest.json' has been created.",
            "mojang_versions_manifest.json.part' has been removed.")
        status, run, logs = self.verify([(0, output), (0, HIT)])
        self.assertEqual(status, 0)
        self.assertEqual(run.call_count, 2)
        self.assertEqual(logs['configuration-cache-verification-1.log'], output)


if __name__ == '__main__':
    unittest.main()
