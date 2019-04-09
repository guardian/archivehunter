import unittest
from mock import MagicMock, patch

####
#### run in the built container like this: PYTHONPATH=$PWD nosetests .

class TestDoesFilepathMatch(unittest.TestCase):
    class MockItem(object):
        def __init__(self, content):
            self._content = content

        def get(self, key):
            return self._content[key]

    def test_should_match(self):
        from reintegrate_from_list import does_filepath_match

        item = self.MockItem({"gnm_asset_filename": "/path/to/some/media.mp4"})
        result = does_filepath_match(item, "/path/to/some/media.mp4")
        self.assertTrue(result)

    def test_should_fail_filename(self):
        from reintegrate_from_list import does_filepath_match

        item = self.MockItem({"gnm_asset_filename": "/path/to/some/othermedia.mp4"})
        result = does_filepath_match(item, "/path/to/some/media.mp4")
        self.assertFalse(result)

    def test_should_fail_filepath(self):
        from reintegrate_from_list import does_filepath_match

        item = self.MockItem({"gnm_asset_filename": "/path/to/a/media.mp4"})
        result = does_filepath_match(item, "/path/to/some/media.mp4")
        self.assertFalse(result)

    def test_should_fail_both(self):
        from reintegrate_from_list import does_filepath_match

        item = self.MockItem({"gnm_asset_filename": "/path/to/an/othermedia.mp4"})
        result = does_filepath_match(item, "/path/to/some/media.mp4")
        self.assertFalse(result)

    def test_should_fail_nopath(self):
        from reintegrate_from_list import does_filepath_match

        item = self.MockItem({"gnm_asset_filename": "othermedia.mp4"})
        result = does_filepath_match(item, "/path/to/some/media.mp4")
        self.assertFalse(result)


class TestFindInVidispine(unittest.TestCase):
    def test_find_in_vidispine(self):
        """
        find_in_vidispine should perform a VS search and then run does_filepath_match against the results.
        we should be left with only one final result.
        :return:
        """
        from gnmvidispine.vs_item import VSItem
        from gnmvidispine.vs_search import VSSearch, VSSearchResult

        mock_search_results = MagicMock(target=VSSearchResult)
        mock_search_results.totalItems = 1

        mock_item = MagicMock(target=VSItem)
        mock_item.populate = MagicMock()

        items_list = [mock_item]

        mock_search_results.results = MagicMock(return_value=items_list)
        mock_vssearch = MagicMock(target=VSSearch)
        mock_vssearch.execute = MagicMock(return_value=mock_search_results)
        mock_vssearch.addCriterion = MagicMock()

        with patch("reintegrate_from_list.get_search", return_value=mock_vssearch):
            with patch("reintegrate_from_list.does_filepath_match", return_value=True) as mock_filepath_match:
                from reintegrate_from_list import find_in_vidispine

                result = find_in_vidispine("myfile","username","password")
                mock_vssearch.execute.assert_called_once()
                mock_vssearch.addCriterion.assert_called_once()
                mock_filepath_match.assert_called_once()
                mock_item.populate.assert_called_once()

                self.assertEqual(result, mock_item)

    def test_find_in_vidispine_filter(self):
        """
        find_in_vidispine should only return items that have been filtered by does_filepath_match
        :return:
        """
        from gnmvidispine.vs_item import VSItem
        from gnmvidispine.vs_search import VSSearch, VSSearchResult

        mock_search_results = MagicMock(target=VSSearchResult)
        mock_search_results.totalItems = 2

        mock_item = MagicMock(target=VSItem)
        mock_item.populate = MagicMock()

        mock_item2 = MagicMock(target=VSItem)
        mock_item2.populate = MagicMock()

        items_list = [mock_item,mock_item2]

        mock_search_results.results = MagicMock(return_value=items_list)
        mock_vssearch = MagicMock(target=VSSearch)
        mock_vssearch.execute = MagicMock(return_value=mock_search_results)

        with patch("reintegrate_from_list.get_search", return_value=mock_vssearch):
            with patch("reintegrate_from_list.does_filepath_match", side_effect=[False, True]) as mock_filepath_match:
                from reintegrate_from_list import find_in_vidispine

                result = find_in_vidispine("myfile","username","password")
                mock_vssearch.execute.assert_called_once()
                mock_vssearch.addCriterion.assert_called_once()
                self.assertEqual(mock_filepath_match.call_count, 2)
                mock_item.populate.assert_called_once()
                mock_item2.populate.assert_called_once()

                self.assertEqual(result, mock_item2)

    def test_find_in_vidispine_toomany(self):
        """
        find_in_vidispine should not bother with an item that has >100 hits
        :return:
        """
        from gnmvidispine.vs_item import VSItem
        from gnmvidispine.vs_search import VSSearch, VSSearchResult

        mock_search_results = MagicMock(target=VSSearchResult)
        mock_search_results.totalItems = 150

        mock_item = MagicMock(target=VSItem)
        mock_item.populate = MagicMock()

        items_list = [mock_item]

        mock_search_results.results = MagicMock(return_value=items_list)
        mock_vssearch = MagicMock(target=VSSearch)
        mock_vssearch.execute = MagicMock(return_value=mock_search_results)

        with patch("reintegrate_from_list.get_search", return_value=mock_vssearch):
            with patch("reintegrate_from_list.does_filepath_match", return_value=True) as mock_filepath_match:
                from reintegrate_from_list import find_in_vidispine

                result = find_in_vidispine("myfile","username","password")
                mock_vssearch.execute.assert_called_once()
                mock_vssearch.addCriterion.assert_called_once()
                mock_filepath_match.assert_not_called()
                mock_item.populate.assert_not_called()

                self.assertEqual(result, None)

    def test_find_in_vidispine_multiple(self):
        """
        find_in_vidispine should alert and not return a value if multiple items match
        :return:
        """
        from gnmvidispine.vs_item import VSItem
        from gnmvidispine.vs_search import VSSearch, VSSearchResult

        mock_search_results = MagicMock(target=VSSearchResult)
        mock_search_results.totalItems = 2

        mock_item = MagicMock(target=VSItem)
        mock_item.populate = MagicMock()

        mock_item2 = MagicMock(target=VSItem)
        mock_item2.populate = MagicMock()

        items_list = [mock_item,mock_item2]

        mock_search_results.results = MagicMock(return_value=items_list)
        mock_vssearch = MagicMock(target=VSSearch)
        mock_vssearch.execute = MagicMock(return_value=mock_search_results)

        with patch("reintegrate_from_list.get_search", return_value=mock_vssearch):
            with patch("reintegrate_from_list.does_filepath_match", return_value=True) as mock_filepath_match:
                from reintegrate_from_list import find_in_vidispine

                result = find_in_vidispine("myfile","username","password")
                mock_vssearch.execute.assert_called_once()
                mock_vssearch.addCriterion.assert_called_once()
                self.assertEqual(mock_filepath_match.call_count, 2)
                mock_item.populate.assert_called_once()
                mock_item2.populate.assert_called_once()

                self.assertEqual(result, None)

