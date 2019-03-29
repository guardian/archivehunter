from unittest import TestCase
from datetime import datetime


class TestFormatTimestamp(TestCase):
    def test_format_timestamp_normal(self):
        """
        format_timestamp should return a string ISO representation of the given timestamp
        :return:
        """
        from setup_media_item import format_timestamp
        testtime = datetime(2019,3,3,21,22,23)

        self.assertEqual(format_timestamp(testtime), "2019-03-03T21:22:23")

    def test_format_timestamp_null(self):
        """
        format_timestamp should return an empty string if no timestamp is provided
        :return:
        """
        from setup_media_item import format_timestamp
        testtime = None
        self.assertEqual(format_timestamp(testtime), "")

    def test_format_timestamp_invalidtype(self):
        """
        format_timestamp should raise type error if timestamp format is not correct
        :return:
        """
        from setup_media_item import format_timestamp
        testtime = "something else"
        with self.assertRaises(TypeError):
            format_timestamp(testtime)