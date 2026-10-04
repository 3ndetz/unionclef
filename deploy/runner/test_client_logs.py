"""Boundary/error-preservation tests without a client, Docker or a clock change."""
import unittest
import gzip
import hashlib
import json
from pathlib import Path
import tempfile
from unittest.mock import patch

from uctest.client_logs import ClientLogWindow, window_bytes


class ClientLogTests(unittest.TestCase):
    def test_append(self):
        self.assertEqual(window_bytes(b'old\n', b'old\nERROR new\n', []),
                         (b'ERROR new\n', ['latest.log']))

    def test_midnight_preserves_both_error_sides(self):
        result, sources = window_bytes(b'old\n', b'ERROR after\n', [
            ('2026-10-02-7.log.gz', b'old\nERROR before\n')])
        self.assertEqual(result, b'ERROR before\nERROR after\n')
        self.assertEqual(sources, ['2026-10-02-7.log.gz', 'latest.log'])

    def test_numeric_size_rotations(self):
        result, _ = window_bytes(b'old\n', b'end\n', [
            ('2026-10-02-9.log.gz', b'old\nfirst\n'),
            ('2026-10-02-10.log.gz', b'ERROR middle\n')])
        self.assertEqual(result, b'first\nERROR middle\nend\n')

    def test_partial_error_line_is_not_lost(self):
        result, _ = window_bytes(b'old\nER', b'old\nERROR across boundary\n', [])
        self.assertEqual(result, b'ERROR across boundary\n')

    def test_equal_line_count_is_not_same_file(self):
        with self.assertRaises(ValueError):
            window_bytes(b'old\n', b'ERROR\n', [])

    def test_missing_intervening_archive(self):
        with self.assertRaises(ValueError):
            window_bytes(b'old\n', b'end\n', [
                ('2026-10-02-7.log.gz', b'old\nfirst\n'),
                ('2026-10-02-9.log.gz', b'last\n')])

    def test_missing_or_ambiguous_start(self):
        for archives in ([], [('2026-10-02-1.log.gz', b'old\na\n'),
                              ('2026-10-02-2.log.gz', b'old\nb\n')]):
            with self.assertRaises(ValueError):
                window_bytes(b'old\n', b'end\n', archives)

    def test_end_rotates_during_collection_without_duplication(self):
        result, sources = window_bytes(b'old\n', b'end\n', [
            ('2026-10-02-7.log.gz', b'old\nfirst\n'),
            ('2026-10-03-1.log.gz', b'end\nlater\n')])
        self.assertEqual(result, b'first\nend\n')
        self.assertEqual(sources, ['2026-10-02-7.log.gz', 'latest.log'])

    def test_empty_start_or_end_fails(self):
        for before, latest in ((b'', b'end\n'), (b'old\n', b'')):
            with self.assertRaises(ValueError):
                window_bytes(before, latest, [('2026-10-02-7.log.gz', b'old\n')])


class ClientLogCollectorTests(unittest.TestCase):
    name = '2026-10-03-7.log.gz'

    def specimen(self, listing=None, archive=None):
        probe = ClientLogWindow.__new__(ClientLogWindow)
        probe.container = 'owned-fixture'
        probe.before = b'old\nER'
        probe.old_archives = {self.name}
        probe._archives = listing or (lambda: [self.name])
        reads = []
        def read(name):
            reads.append(name)
            if name == 'latest.log':
                return b'ERROR after rollover\n'
            return archive() if archive else gzip.compress(b'old\nERROR before rollover\n')
        probe._read = read
        return probe, reads

    def test_reused_name_preserves_errors_on_both_sides(self):
        probe, reads = self.specimen()
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(probe.finish(tmp), ['ERROR before rollover', 'ERROR after rollover'])
            record = json.loads((Path(tmp) / 'client-window.json').read_text())
            self.assertEqual(record['sources'], [self.name, 'latest.log'])
            self.assertEqual(record['ending_sha256'], hashlib.sha256(b'ERROR after rollover\n').hexdigest())
            discovery = json.loads((Path(tmp) / 'archive-discovery.json').read_text())
            self.assertEqual(discovery['initial_names'], [self.name])
            self.assertEqual(len(discovery['attempts']), 1)
        self.assertEqual(reads, ['latest.log', self.name])

    def test_delayed_discovery_keeps_original_end_boundary(self):
        listing = iter([[], [self.name]])
        probe, reads = self.specimen(listing=lambda: next(listing))
        with tempfile.TemporaryDirectory() as tmp, patch('uctest.client_logs.time.sleep'):
            self.assertEqual(probe.finish(tmp), ['ERROR before rollover', 'ERROR after rollover'])
            discovery = json.loads((Path(tmp) / 'archive-discovery.json').read_text())
            self.assertIn('error', discovery['attempts'][0])
            self.assertEqual(discovery['attempts'][1]['prefix_matches'], [self.name])
        self.assertEqual(reads.count('latest.log'), 1)

    def test_incomplete_gzip_retained_before_ready_retry(self):
        raws = iter([b'\x1f\x8b', gzip.compress(b'old\nERROR before rollover\n')])
        probe, _ = self.specimen(archive=lambda: next(raws))
        with tempfile.TemporaryDirectory() as tmp, patch('uctest.client_logs.time.sleep'):
            self.assertEqual(len(probe.finish(tmp)), 2)
            self.assertEqual((Path(tmp) / 'archive-attempt-1' / self.name).read_bytes(), b'\x1f\x8b')
            self.assertTrue((Path(tmp) / 'client-case.log').exists())

    def test_permanently_missing_prefix_never_seals(self):
        probe, reads = self.specimen(listing=lambda: [])
        with tempfile.TemporaryDirectory() as tmp, patch('uctest.client_logs.time.sleep'):
            with self.assertRaises(FileNotFoundError):
                probe.finish(tmp)
            self.assertFalse((Path(tmp) / 'client-case.log').exists())
            self.assertFalse((Path(tmp) / 'client-window.json').exists())
            self.assertEqual(len(json.loads((Path(tmp) / 'archive-discovery.json').read_text())['attempts']), 3)
        self.assertEqual(reads, ['latest.log'])

    def test_ambiguous_prefix_does_not_retry_or_seal(self):
        probe, _ = self.specimen(listing=lambda: [self.name, '2026-10-04-1.log.gz'])
        with tempfile.TemporaryDirectory() as tmp, patch('uctest.client_logs.time.sleep') as sleep:
            with self.assertRaisesRegex(ValueError, 'ambiguous'):
                probe.finish(tmp)
            self.assertFalse((Path(tmp) / 'client-window.json').exists())
            sleep.assert_not_called()

    def test_intervening_gap_remains_failure(self):
        probe, _ = self.specimen(listing=lambda: [self.name, '2026-10-03-9.log.gz'])
        probe._read = lambda name: (b'end\n' if name == 'latest.log' else
            gzip.compress(b'old\nERROR start\n' if name == self.name else b'ERROR gap\n'))
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(ValueError, 'intervening'):
                probe.finish(tmp)
            self.assertFalse((Path(tmp) / 'client-window.json').exists())

    def test_append_needs_no_archive_discovery(self):
        probe, _ = self.specimen()
        probe._read = lambda name: b'old\nERROR append\n'
        probe._archives = lambda: self.fail('Append queried archives')
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(probe.finish(tmp), ['ERROR append'])


if __name__ == '__main__':
    unittest.main()
