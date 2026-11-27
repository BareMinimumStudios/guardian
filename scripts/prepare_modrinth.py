#!/usr/bin/env python3
"""Prepare and verify Guardian uploads. Only mc-publish performs remote writes."""
import argparse
from datetime import date
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sys
import tomllib
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen
from zipfile import ZipFile

VERSIONS = ('1.21.1', '1.21.4', '1.21.5', '1.21.6', '1.21.7', '1.21.8',
            '1.21.9', '1.21.10', '1.21.11', '26.1.2', '26.2')
LOADERS = ('fabric', 'neoforge')
DEPENDENCIES = {'fabric': ('fabric-api', 'fabric-language-kotlin', 'fzzy-config'),
                'neoforge': ('kotlin-for-forge', 'fzzy-config')}
API = 'https://api.modrinth.com/v2'


def require(condition, message):
    if not condition:
        raise ValueError(message)


def properties(path):
    return dict(line.split('=', 1) for line in path.read_text().splitlines()
                if '=' in line and not line.startswith('#'))


def profile(repo, minecraft):
    require(minecraft in VERSIONS, 'Unsupported Minecraft target')
    return properties(repo / ('gradle.properties' if minecraft == '1.21.1'
                             else f'ports/targets/{minecraft}.properties'))


def release_type(release):
    match = re.fullmatch(r'(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)(?:-(alpha|beta|rc)\.(?:0|[1-9]\d*))?', release)
    require(match, 'Expected a stable version or numbered alpha, beta or rc prerelease')
    return 'release' if match.group(1) is None else 'alpha' if match.group(1) == 'alpha' else 'beta'


def release_notes(repo, release):
    release_type(release)
    text = (repo / 'CHANGELOG.md').read_text(encoding='utf-8')
    heading = r'^## \[' + re.escape(release) + r'(?:\+1\.21\.1)?\] - (\d{4}-\d{2}-\d{2})[ \t]*$'
    matches = list(re.finditer(heading, text, re.M))
    require(len(matches) == 1, 'Expected one dated changelog section for ' + release)
    date.fromisoformat(matches[0].group(1))
    notes = re.split(r'^## |^\[[^\]\n]+\]:', text[matches[0].end():], maxsplit=1, flags=re.M)[0].strip()
    require(notes, 'Release notes are empty')
    return notes + '\n'


def jar_metadata(path, minecraft, loader, version, java):
    require(path.is_file() and path.stat().st_size < 16 * 1024 * 1024, 'Missing or oversized jar: ' + str(path))
    with ZipFile(path) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), 'Duplicate jar entries')
        require(not any('portprobe' in n.lower() or 'guardian-test-assertions' in n for n in names), 'Private test code in jar')
        nested = [n for n in names if n.endswith('.jar')]
        require(len(nested) == 1 and 'sqlite-jdbc-' in nested[0], 'Core must bundle SQLite only')
        classes = [n for n in names if n.endswith('.class')]
        require(classes, 'Missing Java bytecode')
        for name in classes:
            with archive.open(name) as stream:
                header = stream.read(8)
            require(len(header) == 8 and header[:4] == b'\xca\xfe\xba\xbe'
                    and int.from_bytes(header[6:8], 'big') <= java + 44, 'Wrong Java bytecode version')
        if loader == 'fabric':
            meta = json.loads(archive.read('fabric.mod.json'))
            require(meta['id'] == 'guardian' and meta['version'] == version and meta['environment'] == 'server', 'Wrong Fabric metadata')
            require(meta['depends']['minecraft'] in (minecraft, '=' + minecraft), 'Wrong Fabric Minecraft version')
        else:
            meta = tomllib.loads(archive.read('META-INF/neoforge.mods.toml').decode())
            require(any(m['modId'] == 'guardian' and m['version'] == version for m in meta['mods']), 'Wrong NeoForge metadata')
            deps = meta['dependencies']['guardian']
            require(any(d['modId'] == 'minecraft' and d['versionRange'] == '[' + minecraft + ']' and d['side'] == 'SERVER' for d in deps), 'Wrong NeoForge Minecraft version')
            bundled = json.loads(archive.read('META-INF/jarjar/metadata.json'))['jars']
            require(len(bundled) == 1 and bundled[0]['path'] == nested[0], 'Wrong NeoForge nested dependency metadata')
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha512').hexdigest()


def folder(repo, minecraft, loader):
    if minecraft == '1.21.1':
        return repo / ('build/libs' if loader == 'fabric' else 'neoforge/build/libs')
    if minecraft == '26.2':
        return repo / f'ports/{loader}/build/libs'
    return repo / f'ports/build/{minecraft}/{loader}/libs'


def prepare(repo, out, minecraft, release):
    pins = profile(repo, minecraft)
    version = release + '+' + minecraft
    require(pins['modVersion'] == version, 'Profile version does not match requested release')
    java = int(pins.get('javaVersion', '21'))
    notes = release_notes(repo, release)
    entries = []
    sources = []
    for loader in LOADERS:
        artifact = 'guardian' if loader == 'fabric' else 'guardian-neoforge'
        source = folder(repo, minecraft, loader) / f'{artifact}-{version}.jar'
        sources.append(source)
        digest = jar_metadata(source, minecraft, loader, version, java)
        name = f'guardian-{version}-{loader}.jar'
        entries.append({'minecraft': minecraft, 'loader': loader, 'java': java,
                        'mod_version': version, 'version': version + '-' + loader, 'release_type': release_type(release),
                        'file': name, 'sha512': digest, 'dependencies': list(DEPENDENCIES[loader])})
    dest = out / minecraft
    dest.mkdir(parents=True, exist_ok=True)
    for source, entry in zip(sources, entries):
        shutil.copy2(source, dest / entry['file'])
    (dest / 'RELEASE-NOTES.md').write_text(notes, encoding='utf-8', newline='\n')
    notes_hash = hashlib.sha512(notes.encode('utf-8')).hexdigest()
    (dest / 'manifest.json').write_text(json.dumps({'release': release, 'notes_sha512': notes_hash, 'entries': entries}, indent=2) + '\n', encoding='utf-8')
    return entries


def load_bundle(bundle, release, minecraft='all', loader='all'):
    require(minecraft == 'all' or minecraft in VERSIONS, 'Unsupported target')
    require(loader == 'all' or loader in LOADERS, 'Unsupported loader')
    entries = []
    targets = VERSIONS if minecraft == 'all' else (minecraft,)
    for target in targets:
        root = bundle / target
        manifest = json.loads((root / 'manifest.json').read_text(encoding='utf-8'))
        require(manifest['release'] == release, 'Mixed release manifests')
        require(len(manifest['entries']) == 2 and {e['loader'] for e in manifest['entries']} == set(LOADERS), 'Incomplete loader pair')
        notes = (root / 'RELEASE-NOTES.md').read_bytes()
        require(notes.strip(), 'Missing release notes')
        require(hashlib.sha512(notes).hexdigest() == manifest.get('notes_sha512'), 'Release notes changed after preparation')
        for entry in manifest['entries']:
            require(entry['minecraft'] == target and entry['mod_version'] == release + '+' + target, 'Wrong manifest version')
            require(entry['version'] == entry['mod_version'] + '-' + entry['loader'], 'Wrong public version number')
            require(entry.get('release_type', release_type(release)) == release_type(release), 'Wrong release type')
            require(entry['java'] == (25 if target.startswith('26.') else 21), 'Wrong Java target')
            require(entry['dependencies'] == list(DEPENDENCIES[entry['loader']]), 'Wrong loader dependencies')
            path = root / entry['file']
            require(path.resolve().parent == root.resolve() and path.suffix == '.jar', 'Unsafe artifact path')
            digest = jar_metadata(path, target, entry['loader'], entry['mod_version'], entry['java'])
            require(digest == entry['sha512'], 'Artifact changed after preparation')
            if loader == 'all' or entry['loader'] == loader:
                entries.append({**entry, 'release_type': release_type(release), 'file': path.as_posix(), 'notes': (root / 'RELEASE-NOTES.md').as_posix()})
    return entries


def api_get(route, token):
    request = Request(API + route, headers={'Authorization': token, 'User-Agent': 'BareMinimumStudios/Guardian-publisher (github.com/BareMinimumStudios/guardian)'})
    try:
        with urlopen(request, timeout=30) as response:
            data = response.read(8 * 1024 * 1024 + 1)
        require(len(data) <= 8 * 1024 * 1024, 'Modrinth response exceeds budget')
        return json.loads(data)
    except HTTPError as error:
        raise ValueError(f'Modrinth preflight refused (HTTP {error.code}); check project ID and organization token access') from None
    except URLError:
        raise ValueError('Modrinth preflight could not connect; no uploads started') from None


def check_remote(entries, project, token, get=api_get):
    require(project and re.fullmatch(r'[A-Za-z0-9_-]+', project), 'Set MODRINTH_PROJECT_ID to Guardian\'s project ID or slug')
    require(token, 'MODRINTH_TOKEN is unavailable; allow Guardian access to the organization secret')
    meta = get('/project/' + quote(project, safe=''), token)
    require(meta['project_type'] == 'mod', 'Expected a Modrinth mod project')
    require((meta.get('source_url') or '').rstrip('/').lower() == 'https://github.com/bareminimumstudios/guardian', 'Guardian project source URL must point to https://github.com/BareMinimumStudios/guardian')
    require(meta['status'] not in ('rejected', 'withheld', 'archived'), 'Project status does not allow this upload plan')
    if not entries:
        return {'project_id': meta['id'], 'project_status': meta['status'], 'entries': []}
    required = {slug: get('/project/' + slug, token)['id'] for slug in sorted({d for e in entries for d in e['dependencies']})}
    versions = get('/project/' + quote(meta['id'], safe='') + '/version', token)
    by_number = {}
    for version in versions:
        by_number.setdefault(version['version_number'], []).append(version)
    for entry in entries:
        entry['release_type'] = release_type(entry['version'].split('+', 1)[0])
        existing = by_number.get(entry['version'], [])
        require(len(existing) <= 1, 'Ambiguous duplicate remote version: ' + entry['version'])
        entry['skip'] = bool(existing)
        if existing:
            version = existing[0]
            require(version['loaders'] == [entry['loader']] and version['game_versions'] == [entry['minecraft']] and version['version_type'] == entry['release_type'], 'Existing version metadata conflicts: ' + entry['version'])
            require(any(f['hashes'].get('sha512') == entry['sha512'] and f.get('primary') for f in version['files']), 'Existing version has different bytes; refusing replacement: ' + entry['version'])
            found = {d.get('project_id') for d in version['dependencies'] if d['dependency_type'] == 'required'}
            require({required[d] for d in entry['dependencies']} <= found, 'Existing version dependencies conflict')
        entry['dependency_input'] = '\n'.join(d + '(required){modrinth:' + d + '}' for d in entry['dependencies'])
    return {'project_id': meta['id'], 'project_status': meta['status'], 'entries': entries}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('access', 'matrix', 'prepare', 'verify', 'preflight'))
    parser.add_argument('--repo', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--bundle', type=Path, default=Path('build/modrinth'))
    parser.add_argument('--release', help='Defaults to the release in gradle.properties')
    parser.add_argument('--minecraft', default='all')
    parser.add_argument('--loader', default='all')
    parser.add_argument('--shared-base-artifact', action='store_true',
                        help='Skip the 1.21.1 build job when the current run already produced both jars')
    parser.add_argument('--project', default=os.environ.get('MODRINTH_PROJECT_ID', ''))
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    if args.command != 'access' and not args.release:
        args.release = properties(args.repo / 'gradle.properties')['modVersion'].split('+', 1)[0]
    if args.command == 'access':
        result = check_remote([], args.project, os.environ.get('MODRINTH_TOKEN', ''))
    elif args.command == 'matrix':
        release_notes(args.repo, args.release)
        require(args.minecraft == 'all' or args.minecraft in VERSIONS, 'Unsupported target')
        targets = VERSIONS if args.minecraft == 'all' else (args.minecraft,)
        for v in targets:
            require(profile(args.repo, v)['modVersion'] == args.release + '+' + v, 'Release/profile version mismatch')
        if args.shared_base_artifact:
            require(args.minecraft == 'all', 'Shared base artifacts require the full release matrix')
            targets = tuple(v for v in targets if v != '1.21.1')
        result = {'include': [{'minecraft': v, 'java': 25 if v.startswith('26.') else 21} for v in targets]}
    elif args.command == 'prepare':
        result = prepare(args.repo, args.bundle, args.minecraft, args.release)
    else:
        entries = load_bundle(args.bundle, args.release, args.minecraft, args.loader)
        result = entries if args.command == 'verify' else check_remote(entries, args.project, os.environ.get('MODRINTH_TOKEN', ''))
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    else:
        print(json.dumps(result, separators=(',', ':')))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, OSError) as error:
        print('Guardian publish check: ' + str(error), file=sys.stderr)
        sys.exit(1)
