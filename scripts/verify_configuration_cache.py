"""Verify reuse, allowing one newly stored entry to be checked."""
from pathlib import Path
import subprocess
import sys

REUSED = 'Configuration cache entry reused.'
STORED = 'Configuration cache entry stored.'


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
        lines = {line.strip() for line in output.splitlines()}
        if REUSED in lines:
            return 0
        if attempt == 1 and STORED in lines:
            emit('::notice::Gradle stored a fresh configuration cache entry. Checking reuse once more.')
            continue
        emit('::error::The build succeeded but configuration-cache reuse was not verified. See the verification logs.')
        return 1
    return 1


if __name__ == '__main__':
    sys.exit(verify(Path(__file__).resolve().parents[1]))
