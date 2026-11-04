"""Synthetic pages through the pinned extractor + our opt-in adapter; no VK network."""
import importlib.util
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'app/src/main/res/raw/ytdlp_2026_10_26'))
from yt_dlp.extractor.vk import VKUserVideosIE
from yt_dlp.utils import ExtractorError

spec = importlib.util.spec_from_file_location('parentcast_vk', ROOT / 'app/src/main/res/raw/parentcast_vk.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
Adapter = module.ParentCastVKUserVideosIE


def page(ids, total):
    rows = []
    for ident in ids:
        row = [-1, ident, None, f'Video &amp; {ident}', None, '1:56'] + [None] * 14
        row[19] = 116
        rows.append(row)
    return [{'playlist_2': {'list': rows, 'count': len(rows), 'total': total}}]


class AdapterTest(unittest.TestCase):
    def extract(self, pages):
        offsets = []
        def payload(_self, _path, _id, data):
            offsets.append(data['offset'])
            result = pages.pop(0)
            if isinstance(result, Exception):
                raise result
            return result
        def real_extract(ie, _url):
            return {'id': '-1_playlist_2', 'entries': ie._entries('-1', 'playlist_2')}
        with patch.object(VKUserVideosIE, '_download_payload', payload), patch.object(VKUserVideosIE, '_real_extract', real_extract), patch.object(VKUserVideosIE, 'to_screen') as output:
            result = Adapter()._real_extract('https://vkvideo.ru/playlist/-1_2/')
        return result, offsets, output

    def test_two_pages_keep_names_order_and_total(self):
        result, offsets, _ = self.extract([page([1, 2], 3), page([3], 3)])
        self.assertEqual([0, 2], offsets)
        self.assertEqual(['-1_1', '-1_2', '-1_3'], [e['id'] for e in result['entries']])
        self.assertEqual(['Video & 1', 'Video & 2', 'Video & 3'], [e['title'] for e in result['entries']])
        self.assertTrue(all(e['duration'] == 116 for e in result['entries']))
        self.assertEqual(3, result['parentcast_reported_count'])

    def test_pagination_failure_preserves_earlier_page_and_explicit_partial_flag(self):
        result, _, _ = self.extract([page([1, 2], 3), ExtractorError('second page failed')])
        self.assertEqual(2, len(result['entries']))
        self.assertEqual(3, result['parentcast_reported_count'])
        self.assertTrue(result['parentcast_page_error'])

    def test_zero_page_keeps_mismatch_and_unavailable_diagnostic(self):
        result, _, output = self.extract([page([1, 2], 3), page([], 3)])
        self.assertEqual(2, len(result['entries']))
        self.assertEqual(3, result['parentcast_reported_count'])
        self.assertIn('Skipping 1 unavailable', output.call_args.args[0])

    def test_exact_limit_and_over_limit_are_bounded(self):
        result, _, _ = self.extract([page(range(1, 501), 500)])
        self.assertEqual(500, len(result['entries']))
        result, offsets, _ = self.extract([page([1, 2], 501)])
        self.assertEqual(501, result['parentcast_reported_count'])
        self.assertEqual([], result['entries'])
        self.assertEqual([0], offsets)

    def test_changed_provider_count_is_explicit(self):
        result, _, _ = self.extract([page([1, 2], 4), page([3, 4], 5)])
        self.assertTrue(result['parentcast_changed'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
