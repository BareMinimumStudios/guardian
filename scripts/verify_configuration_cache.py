"""Verify reuse, allowing one cold-cache Loom manifest creation to settle."""
from pathlib import Path
import re
import subprocess
import sys

REUSED = 'Configuration cache entry reused.'


def manifest_created(output):
    reasons = re.findall(
        r'^Calculating task graph as configuration cache cannot be reused because (.+)$',
        output.replace('\\', '/'), re.MULTILINE)
    return len(reasons) == 1 and re.fullmatch(
        r"the file system entry '[^'\r\n]*/caches/fabric-loom/mojang_versions_manifest\.json' has been created\.",
        reasons[0]) is not None


def verify(repo, run=subprocess.run, emit=print):
    wrapper = repo / ('gradlew.bat' if sys.platform == 'win32' else 'gradlew')
    command = [str(wrapper), 'build', '--no-daemon', '--warning-mode', 'all',
               '--max-workers=2', '--no-parallel']
    folder = repo / 'build'
    folder.mkdir(parents=True, exist_ok=True)
    for attempt in (1, 2):
        result = run(command, cwd=repo, stdout=subprocess.PIPE,
                     stderr=subprocess.STDOUT, text=True)
        output = result.stdout or ''
        emit(output)
        (folder / f'configuration-cache-verification-{attempt}.log').write_text(output, encoding='utf-8')
        (folder / 'configuration-cache-verification.log').write_text(output, encoding='utf-8')
        if result.returncode:
            return result.returncode
        if REUSED in {line.strip() for line in output.splitlines()}:
            return 0
        if attempt == 1 and manifest_created(output):
            emit('::notice::Loom created its Minecraft manifest on the cold build; checking cache reuse once more.')
            continue
        emit('::error::The build succeeded but configuration-cache reuse was not verified. See the verification logs.')
        return 1
    return 1


if __name__ == '__main__':
    sys.exit(verify(Path(__file__).resolve().parents[1]))
