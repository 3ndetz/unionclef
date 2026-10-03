"""Boundary/error-preservation tests without a client, Docker or a clock change."""
import unittest

from uctest.client_logs import window_bytes


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


if __name__ == '__main__':
    unittest.main()
