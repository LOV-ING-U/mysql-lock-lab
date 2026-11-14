import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))

from protocol import (  # noqa: E402
    count_by_mode,
    mode_name,
    parse_sessions,
    short_table_name,
    trim_bitmap,
)


class ProtocolTest(unittest.TestCase):
    def test_mode_name(self):
        self.assertEqual('IX', mode_name(17))
        self.assertEqual('X,REC_NOT_GAP', mode_name(1059))
        self.assertEqual('X,REC_NOT_GAP,WAIT', mode_name(1315))
        self.assertEqual('X,NEXT_KEY', mode_name(35))
        self.assertEqual('X,GAP,INSERT_INTENTION,WAIT', mode_name(2851))

    def test_trim_bitmap_keeps_up_to_last_set_bit(self):
        self.assertEqual([0, 0, 0, 1], trim_bitmap(bytes([0b00001000, 0]), 16))
        self.assertEqual([], trim_bitmap(bytes([0, 0]), 16))

    def test_parse_sessions(self):
        self.assertEqual({12345: 'A', 12346: 'B'}, parse_sessions('12345:A,12346:B'))
        self.assertEqual({}, parse_sessions(''))

    def test_short_table_name(self):
        self.assertEqual('t', short_table_name('lab/t', 'lab'))
        self.assertIsNone(short_table_name('mysql/tables', 'lab'))

    def test_count_by_mode(self):
        self.assertEqual({'IX': 2}, count_by_mode([0, 2, 0, 0, 0]))


if __name__ == '__main__':
    unittest.main()
