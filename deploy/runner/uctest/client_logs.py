"""Retain exact client-log windows across Log4j daily/size rollover.

The byte prefix identifies the starting file, rather than a line count that
silently changes meaning after midnight. Missing history fails closed. This
reads logs only; it never restarts a client or changes its logging configuration.
"""
import gzip
import hashlib
import json
from pathlib import Path
import re

from . import process

_ARCHIVE = re.compile(r'^(\d{4}-\d{2}-\d{2})-(\d+)\.log\.gz$')


def window_bytes(before, latest, archives):
    """Return a conservative byte window and the chronological source names.

    archives is an ordered sequence of (name, decompressed bytes). Include an
    incomplete starting line in full so an error spanning the boundary survives.
    """
    if not before:
        raise ValueError('Empty initial client log cannot identify its history')
    offset = before.rfind(b'\n') + 1
    if latest.startswith(before):
        return latest[offset:], ['latest.log']
    matches = [index for index, (_, data) in enumerate(archives) if data.startswith(before)]
    if len(matches) != 1:
        raise ValueError('Missing or ambiguous starting client-log archive')
    selected = []
    sources = []
    previous = None
    for name, data in archives[matches[0]:]:
        # The ending snapshot may itself have rolled while archives were read.
        # Use only its frozen bytes; never duplicate that later archived file.
        if data.startswith(latest) and latest:
            break
        match = _ARCHIVE.fullmatch(name)
        if not match:
            raise ValueError('Unexpected client-log archive name: ' + name)
        date, number = match[1], int(match[2])
        if previous and date == previous[0] and number != previous[1] + 1:
            raise ValueError('Missing intervening client-log archive')
        previous = date, number
        selected.append(data)
        sources.append(name)
    if not selected or not latest:
        raise ValueError('Client-log end boundary is unavailable')
    return b''.join(selected)[offset:] + latest, sources + ['latest.log']


class ClientLogWindow:
    """Snapshot before a course; finish into its retained evidence directory."""

    def __init__(self, container):
        self.container = container
        self.old_archives = set(self._archives())
        self.before = self._read('latest.log')
        if not self.before:
            raise ValueError('Client log is empty before the course')

    def _read(self, name):
        return process.check_output(['docker', 'exec', self.container, 'cat',
                                     '/mc-data/logs/' + name], timeout=30)

    def _archives(self):
        listing = process.check_output(['docker', 'exec', self.container,
            'python3', '-c', 'import pathlib,json;print(json.dumps([p.name for p in pathlib.Path("/mc-data/logs").glob("*.log.gz")]))'],
            text=True, timeout=30)
        names = json.loads(listing)
        return sorted((name for name in names if _ARCHIVE.fullmatch(name)),
                      key=lambda name: (_ARCHIVE.fullmatch(name)[1], int(_ARCHIVE.fullmatch(name)[2])))

    def finish(self, directory):
        directory = Path(directory)
        directory.mkdir(parents=True, exist_ok=True)
        (directory / 'client-before.log').write_bytes(self.before)
        latest = self._read('latest.log')
        (directory / 'client.log').write_bytes(latest)
        archives = []
        if not latest.startswith(self.before):
            for name in self._archives():
                if name not in self.old_archives:
                    raw = self._read(name)
                    (directory / name).write_bytes(raw)
                    archives.append((name, gzip.decompress(raw)))
        window, sources = window_bytes(self.before, latest, archives)
        (directory / 'client-case.log').write_bytes(window)
        (directory / 'client-window.json').write_text(json.dumps(dict(
            starting_sha256=hashlib.sha256(self.before).hexdigest(),
            ending_sha256=hashlib.sha256(latest).hexdigest(), sources=sources,
            window_sha256=hashlib.sha256(window).hexdigest(),
            scope='Exact byte-prefix boundary; any incomplete initial line is included in full.'), indent=2))
        return window.decode('utf-8', errors='replace').splitlines()
