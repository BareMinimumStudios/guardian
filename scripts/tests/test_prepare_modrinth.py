import hashlib
import contextlib
import io
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile

spec = importlib.util.spec_from_file_location('publisher', Path(__file__).resolve().parents[1] / 'prepare_modrinth.py')
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)


class PublisherChecks(unittest.TestCase):
    def entry(self):
        return {'minecraft': '1.21.1', 'loader': 'fabric', 'version': '0.4.0-alpha.99+1.21.1-fabric',
                'sha512': 'abc', 'dependencies': list(publisher.DEPENDENCIES['fabric'])}

    def remote(self, versions=(), status='processing', source='https://github.com/BareMinimumStudios/guardian'):
        def get(route, token):
            self.assertEqual('test-token', token)
            if route.endswith('/version'):
                return list(versions)
            if route == '/project/guardian':
                return {'id': 'project1', 'project_type': 'mod', 'status': status, 'source_url': source}
            return {'id': route.rsplit('/', 1)[1] + '-id'}
        return get

    def existing(self):
        entry = self.entry()
        return {'version_number': entry['version'], 'version_type': 'alpha', 'loaders': ['fabric'],
                'game_versions': ['1.21.1'], 'files': [{'primary': True, 'hashes': {'sha512': 'abc'}}],
                'dependencies': [{'dependency_type': 'required', 'project_id': slug + '-id'} for slug in entry['dependencies']]}

    def test_stable_and_prerelease_classification(self):
        for version, kind in [('0.4.0', 'release'), ('1.0.0', 'release'),
                              ('0.4.0-alpha.103', 'alpha'), ('0.4.0-beta.1', 'beta'),
                              ('0.4.0-rc.1', 'beta')]:
            with self.subTest(version=version):
                self.assertEqual(kind, publisher.release_type(version))
        for version in ['0.4', '01.4.0', '0.4.0-alpha', '0.4.0-alpha.01',
                        '0.4.0\nother', '0.4.0+1.21.1', '../0.4.0']:
            with self.subTest(version=version), self.assertRaises(ValueError):
                publisher.release_type(version)

    def test_workflow_blank_release_uses_pushed_build_version(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / 'gradle.properties').write_text('modVersion=0.4.0+1.21.1\n')
            (root / 'CHANGELOG.md').write_text('## [0.4.0+1.21.1] - 2026-11-21\n\n### Changed\n\n- Stable release.\n')
            for extra in [[], ['--release', '']]:
                output = io.StringIO()
                args = ['prepare_modrinth.py', 'matrix', '--repo', str(root), '--minecraft', '1.21.1'] + extra
                with patch('sys.argv', args), contextlib.redirect_stdout(output):
                    publisher.main()
                self.assertEqual({'include': [{'minecraft': '1.21.1', 'java': 21}]}, json.loads(output.getvalue()))

    def test_stable_upload_preserves_release_classification(self):
        entry = self.entry(); entry['version'] = '0.4.0+1.21.1-fabric'
        existing = self.existing(); existing['version_number'] = entry['version']
        existing['version_type'] = 'release'
        result = publisher.check_remote([entry], 'guardian', 'test-token', self.remote([existing]))
        self.assertEqual('release', result['entries'][0]['release_type'])
        self.assertTrue(result['entries'][0]['skip'])
        existing['version_type'] = 'alpha'
        with self.assertRaisesRegex(ValueError, 'metadata conflicts'):
            publisher.check_remote([entry], 'guardian', 'test-token', self.remote([existing]))

    def test_stable_bundle_keeps_exact_notes_and_release_type(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / 'gradle.properties').write_text('modVersion=0.4.0+1.21.1\n')
            (root / 'CHANGELOG.md').write_text('## [0.4.0+1.21.1] - 2026-11-21\n\n### Changed\n\n- Stable release.\n', encoding='utf-8')
            for loader in publisher.LOADERS:
                folder = publisher.folder(root, '1.21.1', loader); folder.mkdir(parents=True)
                (folder / 'runtime.jar').write_bytes(b'stable fixture')
            digest = hashlib.sha512(b'stable fixture').hexdigest()
            with patch.object(publisher, 'jar_metadata', return_value=digest):
                publisher.prepare(root, root / 'bundle', '1.21.1', '0.4.0')
                entries = publisher.load_bundle(root / 'bundle', '0.4.0', '1.21.1')
                self.assertEqual({'release'}, {e['release_type'] for e in entries})
                manifest = root / 'bundle/1.21.1/manifest.json'
                value = json.loads(manifest.read_text()); value['entries'][0]['release_type'] = 'alpha'
                manifest.write_text(json.dumps(value))
                with self.assertRaisesRegex(ValueError, 'Wrong release type'):
                    publisher.load_bundle(root / 'bundle', '0.4.0', '1.21.1')

    def test_pending_project_plans_new_version_without_remote_writes(self):
        result = publisher.check_remote([self.entry()], 'guardian', 'test-token', self.remote())
        self.assertEqual('processing', result['project_status'])
        self.assertFalse(result['entries'][0]['skip'])
        self.assertNotIn('test-token', json.dumps(result))

    def test_access_check_does_not_download_version_history(self):
        routes = []
        def get(route, token):
            routes.append(route)
            return {'id': 'project1', 'project_type': 'mod', 'status': 'processing',
                    'source_url': 'https://github.com/BareMinimumStudios/guardian'}
        result = publisher.check_remote([], 'guardian', 'test-token', get)
        self.assertEqual(['/project/guardian'], routes)
        self.assertEqual([], result['entries'])

    def test_prepared_notes_are_portable_and_changed_notes_refused(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / 'gradle.properties').write_text('modVersion=0.4.0-alpha.99+1.21.1\n')
            (root / 'CHANGELOG.md').write_text('## [0.4.0-alpha.99+1.21.1] - 2026-11-19\n\n### Added\n\n- Named items: caf\u00e9\n', encoding='utf-8')
            for loader in publisher.LOADERS:
                folder = publisher.folder(root, '1.21.1', loader)
                folder.mkdir(parents=True)
                (folder / 'runtime.jar').write_bytes(b'fixture runtime')
            digest = hashlib.sha512(b'fixture runtime').hexdigest()
            with patch.object(publisher, 'jar_metadata', return_value=digest):
                publisher.prepare(root, root / 'bundle', '1.21.1', '0.4.0-alpha.99')
                self.assertEqual(2, len(publisher.load_bundle(root / 'bundle', '0.4.0-alpha.99', '1.21.1')))
                notes = root / 'bundle/1.21.1/RELEASE-NOTES.md'
                self.assertNotIn(b'\r', notes.read_bytes())
                notes.write_text('Wrong release notes\n', encoding='utf-8')
                with self.assertRaisesRegex(ValueError, 'Release notes changed'):
                    publisher.load_bundle(root / 'bundle', '0.4.0-alpha.99', '1.21.1')

    def test_identical_upload_is_skipped(self):
        self.assertTrue(publisher.check_remote([self.entry()], 'guardian', 'test-token', self.remote([self.existing()]))['entries'][0]['skip'])

    def test_changed_bytes_refuse_replacement(self):
        value = self.existing(); value['files'][0]['hashes']['sha512'] = 'different'
        with self.assertRaisesRegex(ValueError, 'different bytes'):
            publisher.check_remote([self.entry()], 'guardian', 'test-token', self.remote([value]))

    def test_wrong_loader_or_missing_dependencies_refused(self):
        for mutation in ('loader', 'dependencies'):
            value = self.existing()
            if mutation == 'loader': value['loaders'] = ['neoforge']
            else: value['dependencies'] = []
            with self.assertRaises(ValueError):
                publisher.check_remote([self.entry()], 'guardian', 'test-token', self.remote([value]))

    def test_wrong_project_refused(self):
        with self.assertRaisesRegex(ValueError, 'source URL'):
            publisher.check_remote([self.entry()], 'guardian', 'test-token', self.remote(source='https://github.com/BareMinimumStudios/remnant'))

    def test_missing_secret_and_blocked_project_refused(self):
        with self.assertRaisesRegex(ValueError, 'organization secret'):
            publisher.check_remote([self.entry()], 'guardian', '', self.remote())
        with self.assertRaisesRegex(ValueError, 'status'):
            publisher.check_remote([self.entry()], 'guardian', 'test-token', self.remote(status='withheld'))

    def test_ambiguous_remote_version_refused(self):
        with self.assertRaisesRegex(ValueError, 'Ambiguous'):
            publisher.check_remote([self.entry()], 'guardian', 'test-token', self.remote([self.existing(), self.existing()]))

    def test_exact_dated_notes_required(self):
        with tempfile.TemporaryDirectory() as temp:
            repo = Path(temp); p = repo / 'CHANGELOG.md'
            p.write_text('## [Unreleased]\n\nChanges\n')
            with self.assertRaises(ValueError): publisher.release_notes(repo, '0.4.0-alpha.99')
            p.write_text('## [0.4.0-alpha.99+1.21.1] - 2026-11-19\n\n### Added\n\n- Recovery\n\n## [older]\nPrivate older notes\n')
            self.assertEqual('### Added\n\n- Recovery\n', publisher.release_notes(repo, '0.4.0-alpha.99'))

    def test_unsafe_or_unknown_target_refused(self):
        with self.assertRaises(ValueError): publisher.profile(Path('.'), '../../other')
        with self.assertRaises(ValueError): publisher.release_notes(Path('.'), '0.4.0-alpha.99\nother')

    def test_neoforge_dependencies_do_not_include_fabric(self):
        self.assertEqual(('kotlin-for-forge', 'fzzy-config'), publisher.DEPENDENCIES['neoforge'])

    def test_fixture_or_duckdb_jar_refused(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'bad.jar'
            with ZipFile(path, 'w') as archive:
                archive.writestr('guardian/portprobe/NativeChecks.class', b'private')
            with self.assertRaisesRegex(ValueError, 'Private test code'):
                publisher.jar_metadata(path, '1.21.1', 'fabric', '0.4.0-alpha.99+1.21.1', 21)
            with ZipFile(path, 'w') as archive:
                archive.writestr('META-INF/jars/duckdb.jar', b'wrong backend')
            with self.assertRaisesRegex(ValueError, 'SQLite only'):
                publisher.jar_metadata(path, '1.21.1', 'fabric', '0.4.0-alpha.99+1.21.1', 21)


if __name__ == '__main__':
    unittest.main()
