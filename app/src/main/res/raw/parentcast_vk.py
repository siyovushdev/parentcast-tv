"""ParentCast metadata adapter for the pinned yt-dlp VK playlist extractor.

No media download, authentication or new network endpoints. Exposes names, durations and
provider totals already present in the extractor's paginated response. MIT, ParentCast TV.
"""
from itertools import islice
from html import unescape
from yt_dlp.extractor.vk import VKUserVideosIE
from yt_dlp.utils import ExtractorError, parse_duration

__all__ = ['ParentCastVKUserVideosIE']


class ParentCastVKUserVideosIE(VKUserVideosIE, plugin_name='parentcast'):
    def _download_webpage(self, *args, **kwargs):
        page = super()._download_webpage(*args, **kwargs)
        if isinstance(page, str) and '/playlist/' in str(args[0]):
            self._pc_title = self._og_search_title(page, default=None)
        return page

    def _download_payload(self, *args, **kwargs):
        payload = super()._download_payload(*args, **kwargs)
        if args[0] == 'al_video' and isinstance(payload, list) and payload and isinstance(payload[0], dict):
            for section in payload[0].values():
                if not isinstance(section, dict) or not isinstance(section.get('list'), list):
                    continue
                total = section.get('total')
                if type(total) is int and total >= 0:
                    if self._pc_total is not None and self._pc_total != total:
                        self._pc_changed = True
                    self._pc_total = total
                for row in section['list']:
                    if not isinstance(row, list) or len(row) < 6 or not all(type(v) is int for v in row[:2]):
                        continue
                    ident = f'{row[0]}_{row[1]}'
                    title = unescape(row[3])[:500] if isinstance(row[3], str) else ''
                    duration = row[19] if len(row) > 19 and type(row[19]) is int else parse_duration(row[5])
                    self._pc_metadata[ident] = {'title': title, 'duration': duration}
        return payload

    def _entries(self, *args):
        for entry in super()._entries(*args):
            if self._pc_total is not None and self._pc_total > 500:
                return
            entry.update(self._pc_metadata.pop(entry.get('id'), {}))
            yield entry

    def _real_extract(self, url):
        self._pc_metadata, self._pc_total, self._pc_changed, self._pc_title = {}, None, False, None
        result = super()._real_extract(url)
        # Bounded even if the provider total disappears or changes. The parser rejects >500.
        entries = []
        try:
            for entry in islice(result['entries'], 501):
                entries.append(entry)
        except (ExtractorError, KeyError, TypeError, ValueError):
            # Keep earlier pages; never turn a later request/schema failure into COMPLETE.
            result['parentcast_page_error'] = True
        result['entries'] = entries
        result['parentcast_reported_count'] = self._pc_total
        result['parentcast_changed'] = self._pc_changed
        result['parentcast_schema'] = 1
        result['title'] = result.get('title') or self._pc_title
        return result
