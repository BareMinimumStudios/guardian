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

    def test_unrelated_miss_is_not_retried(self):
        status, run, _ = self.verify([(0, COLD.replace('mojang_versions_manifest.json', 'another.json'))])
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

    def test_additional_invalidation_reason_is_not_allowed(self):
        output = COLD + 'Calculating task graph as configuration cache cannot be reused because a source file changed.\n'
        status, run, _ = self.verify([(0, output)])
        self.assertEqual(status, 1)
        self.assertEqual(run.call_count, 1)

    def test_windows_manifest_path_is_recognized(self):
        self.assertTrue(cache.loom_cache_created(COLD.replace('/', '\\')))

    def jar_cold(self):
        return COLD.replace('../../../.gradle/caches/fabric-loom/mojang_versions_manifest.json',
            '.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-9b5ff62f35/'
            '1.21.1-loom.mappings.1_21_1.layered+hash.2198-v2/'
            'minecraft-merged-9b5ff62f35-1.21.1-loom.mappings.1_21_1.layered+hash.2198-v2.jar')

    def test_actual_restored_runner_minecraft_jar_gets_one_retry(self):
        status, run, _ = self.verify([(0, self.jar_cold()), (0, HIT)])
        self.assertEqual(status, 0)
        self.assertEqual(run.call_count, 2)

    def test_minecraft_jar_repeated_miss_still_fails(self):
        status, run, _ = self.verify([(0, self.jar_cold()), (0, self.jar_cold())])
        self.assertEqual(status, 1)
        self.assertEqual(run.call_count, 2)

    def test_changed_generated_jar_is_not_creation(self):
        status, run, _ = self.verify([(0, self.jar_cold().replace('has been created.', 'has changed.'))])
        self.assertEqual(status, 1)
        self.assertEqual(run.call_count, 1)

    def test_unrelated_jar_creation_is_not_retried(self):
        output = self.jar_cold().replace('.gradle/loom-cache/minecraftMaven/net/minecraft/', 'src/main/resources/')
        status, run, _ = self.verify([(0, output)])
        self.assertEqual(status, 1)
        self.assertEqual(run.call_count, 1)

    def test_generated_path_cannot_escape_version_directory(self):
        output = self.jar_cold().replace('1.21.1-loom.mappings.1_21_1.layered+hash.2198-v2/', '../')
        self.assertFalse(cache.loom_cache_created(output))


if __name__ == '__main__':
    unittest.main()
