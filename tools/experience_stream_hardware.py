#!/usr/bin/env python3
"""Opt-in real H728 stream/save acceptance. This script is not a YouTube fixture.

Requires a confirmed device, existing protected browser token, PAUSE playback and
paused preparation. Uses only explicit API commands; no install, reboot or settings
changes. An unexpected queue version stops cleanup rather than overwriting another
parent's work. Reports contain no PIN, token, cookies or signed media URLs.
"""
import argparse
import json
import os
import pathlib
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

PACKAGE = 'dev.siyovush.parentcast.tv.debug'


class AcceptanceError(Exception):
    pass


class ConcurrentChange(AcceptanceError):
    pass


class ApiError(AcceptanceError):
    def __init__(self, status, data):
        self.status, self.data = status, data
        code = data.get('error', '') if isinstance(data, dict) else ''
        super().__init__(code if isinstance(code, str) and re.fullmatch('[A-Z_0-9]{1,80}', code) else 'HTTP_' + str(status))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('url', 'device-file', 'adb', 'serial', 'expected-hardware-serial', 'video-url', 'output'):
        parser.add_argument('--' + name, required=True)
    args = parser.parse_args()
    url = urllib.parse.urlsplit(args.url)
    if url.scheme != 'http' or not url.hostname or url.username or url.password or url.path not in ('', '/') or url.query or url.fragment:
        raise AcceptanceError('Explicit plain LAN panel origin required')
    origin = args.url.rstrip('/')
    output = pathlib.Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    report = {'status': 'RUNNING', 'checks': {}, 'notes': ['Moving Media3 position does not prove audible HDMI output.'], 'temporaryGroupRemoved': False,
              'scope': 'Real device/API. No fresh hashes of all media; protected files hashed on Android.'}
    baseline = None
    expected = None
    own_group = None
    video_id = None
    group_name = 'Проверка stream+save ' + uuid.uuid4().hex
    concurrent = False
    cleanup_needed = False
    secret = None
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def record(name, status, detail=None):
        report['checks'][name] = {'status': status}
        if detail is not None:
            report['checks'][name]['detail'] = detail
        print(json.dumps({'check': name, 'status': status}, ensure_ascii=False), flush=True)

    def write_report():
        fd = os.open(output, os.O_CREAT | os.O_TRUNC | os.O_WRONLY, 0o600)
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, 'w') as stream:
            json.dump(report, stream, ensure_ascii=False, indent=2)

    def shell(*parts):
        return subprocess.check_output([args.adb, '-s', args.serial, 'shell', *parts], text=True, timeout=30,
                                       stderr=subprocess.DEVNULL).strip()

    def protected_hashes():
        files = shell('run-as', PACKAGE, 'find', 'files', 'no_backup', 'shared_prefs', '-maxdepth', '2', '-type', 'f').splitlines()
        protected = [p for p in files if p.startswith('shared_prefs/') or p in
                     ('files/device-tokens.json', 'no_backup/saved-collections.json') or 'cookie' in p.lower()]
        result = {}
        for name in protected:
            if not re.fullmatch('[A-Za-z0-9_/.-]+', name):
                raise AcceptanceError('Unexpected protected path')
            digest = shell('run-as', PACKAGE, 'sha256sum', name).split()[0]
            if not re.fullmatch('[a-f0-9]{64}', digest):
                raise AcceptanceError('Protected hash unavailable')
            result[name] = digest
        return result

    def ready_track_sizes(snapshot):
        internal = {e['videoId'] for e in snapshot.get('prepared', {}).get('files', []) if e.get('storageId') == 'internal'}
        index = json.loads(shell('run-as', PACKAGE, 'cat', 'no_backup/prepared-programme/index.json'))
        result = {}
        for entry in index.get('entries', []):
            if entry.get('status') != 'READY' or entry.get('id') not in internal:
                continue
            for track in entry['tracks']:
                name = track['name']
                if not re.fullmatch('[A-Za-z0-9_:.-]+', name) or not name.endswith('.mp4'):
                    raise AcceptanceError('Unexpected internal media path')
                path = 'no_backup/prepared-programme/' + name
                actual = int(shell('run-as', PACKAGE, 'stat', '-c', '%s', path))
                if actual != track['bytes']:
                    raise AcceptanceError('Internal ready track length mismatch')
                result[path] = (actual, track.get('sha256'))
        return result

    def api(path, body=None, timeout=35):
        headers = {'Authorization': 'Bearer ' + secret, 'X-ParentCast-Client': 'm1', 'Origin': origin}
        payload = None
        if body is not None:
            headers['Content-Type'] = 'application/json; charset=utf-8'
            payload = json.dumps(body).encode()
        request = urllib.request.Request(origin + path, data=payload, headers=headers, method='POST' if body is not None else 'GET')
        try:
            with opener.open(request, timeout=timeout) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            try:
                data = json.load(error)
            except Exception:
                data = {}
            raise ApiError(error.code, data) from None
        except (urllib.error.URLError, TimeoutError, OSError):
            raise AcceptanceError('HTTP_RESPONSE_NOT_CONFIRMED') from None

    def state():
        nonlocal concurrent
        current = api('/api/state')
        if expected is not None and current['queueVersion'] != expected:
            concurrent = True
            raise ConcurrentChange('QUEUE_CHANGED_OUTSIDE_CONFIRMED_TEST_COMMAND')
        return current

    def mutate(path, body, delta=0, timeout=35):
        nonlocal expected, concurrent
        state()  # Fresh check immediately before every mutation, including STOP.
        before = expected
        command = dict(body, queueVersion=before, idempotencyKey=uuid.uuid4().hex)
        result = api(path, command, timeout)
        after = api('/api/state')
        if after['queueVersion'] != before + delta:
            concurrent = True
            raise ConcurrentChange('UNEXPECTED_QUEUE_VERSION_AFTER_TEST_COMMAND')
        expected = after['queueVersion']
        return result, after

    def wait_for(predicate, seconds, label):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            current = state()
            if predicate(current):
                return current
            time.sleep(.5)
        raise AcceptanceError(label + '_TIMEOUT')

    def current_item(s):
        index = s.get('currentIndex', -1)
        return s.get('items', [])[index] if 0 <= index < len(s.get('items', [])) else None

    def stream_entry(s):
        streaming = s.get('streaming') or {}
        entry = streaming.get('current')
        if entry and entry.get('videoId') == video_id:
            return entry
        return next((e for e in streaming.get('entries', []) if e.get('videoId') == video_id), None)

    def queue_identity(s):
        return [(e['queueItemId'], e['videoId']) for e in s['items']]

    def assert_owned_structure(current):
        original_groups = {g['id']: g for g in baseline['groups']}
        current_original = [g for g in current['groups'] if g['id'] in original_groups]
        if current_original != baseline['groups']:
            raise ConcurrentChange('BASELINE_GROUPS_CHANGED')
        additions = [g for g in current['groups'] if g['id'] not in original_groups]
        if len(additions) != 1 or additions[0]['name'] != group_name or additions[0]['videoIds'] != [video_id]:
            raise ConcurrentChange('TEMPORARY_GROUP_NOT_EXCLUSIVELY_OWNED')
        original_ids = {e['videoId'] for e in baseline['items']}
        retained = [e for e in current['items'] if e['videoId'] in original_ids]
        if queue_identity({'items': retained}) != queue_identity(baseline):
            raise ConcurrentChange('BASELINE_QUEUE_CHANGED')
        added_items = [e for e in current['items'] if e['videoId'] not in original_ids]
        if any(e['videoId'] != video_id for e in added_items):
            raise ConcurrentChange('UNRELATED_QUEUE_ADDITION')
        return additions[0]['id']

    def restore_and_cleanup():
        nonlocal own_group, cleanup_needed
        current = state()
        own_group = assert_owned_structure(current)
        original = current_item(baseline)
        already_restored = (current_item(current)['queueItemId'] == original['queueItemId']
                            and current.get('activeGroupId') == baseline.get('activeGroupId')
                            and current.get('intent') == 'PAUSE' and current['positionMs'] == baseline['positionMs'])
        if not already_restored:
            if current_item(current)['videoId'] != video_id or current.get('activeGroupId') != own_group:
                raise ConcurrentChange('RESTORE_REQUIRES_OWN_TEST_PLAYBACK')
            # STOP only our test playback. Never interrupt an unrelated current programme.
            if current.get('intent') != 'STOP':
                _, current = mutate('/api/playback', {'action': 'STOP'}, 1)
            body = {'queueItemId': original['queueItemId'], 'positionMs': baseline['positionMs'], 'play': True}
            if baseline.get('activeGroupId'):
                body['groupId'] = baseline['activeGroupId']
            _, current = mutate('/api/queue/select', body, 1)
            if current.get('intent') == 'PLAY':
                _, current = mutate('/api/playback', {'action': 'PAUSE_RESUME'}, 1)
            elif current.get('intent') != 'PAUSE':
                raise ConcurrentChange('RESTORE_INTENT_CHANGED')
            current = wait_for(lambda s: s.get('seekable') and s.get('intent') == 'PAUSE', 35, 'RESTORE_PAUSE')
            if current['positionMs'] != baseline['positionMs']:
                _, current = mutate('/api/playback/seek', {'queueItemId': original['queueItemId'],
                    'playbackGeneration': current['playbackGeneration'], 'positionMs': baseline['positionMs']}, 1)
        assert_owned_structure(current)
        if current.get('activeGroupId') == own_group:
            raise ConcurrentChange('TEMPORARY_GROUP_STILL_ACTIVE')
        original_ids = {e['videoId'] for e in baseline['items']}
        shared = any(g['id'] != own_group and video_id in g['videoIds'] for g in current['groups'])
        if video_id in original_ids and not shared:
            # removeGroup would also delete a formerly loose queue item: detach membership only.
            _, current = mutate('/api/programme', {'action': 'MEMBERSHIP', 'videoIds': [video_id],
                'fromGroupId': own_group, 'toGroupId': None, 'mode': 'REMOVE'}, 1)
        else:
            if video_id not in original_ids and shared:
                raise ConcurrentChange('TEST_VIDEO_NOW_REFERENCED_BY_ANOTHER_GROUP')
            _, current = mutate('/api/queue/group-remove', {'groupId': own_group}, 1)
        own_group = None
        report['temporaryGroupRemoved'] = True
        cleanup_needed = False  # Later cleanup errors must not re-select or remove a vanished group.
        if video_id not in baseline_known:
            _, current = mutate('/api/prepared', {'action': 'DELETE', 'videoId': video_id}, 0)
            wait_for(lambda s: not s.get('prepared', {}).get('clearing'), 35, 'TEST_FILE_DELETE')
            record('temporaryNewFileCleanup', 'PASS')
        else:
            record('temporaryNewFileCleanup', 'NOT_RUN', 'Existing programme/partial file retained, including newly completed media.')
        # Do not DISABLE, RETRY or re-enable the old full plan; only restore its paused state.
        if baseline['prepared'].get('paused'):
            mutate('/api/prepared', {'action': 'PAUSE'}, 0)
        restored = state()
        if queue_identity(restored) != queue_identity(baseline) or restored['groups'] != baseline['groups']:
            raise AcceptanceError('BASELINE_PROGRAMME_NOT_RESTORED')
        if current_item(restored)['queueItemId'] != original['queueItemId'] or restored.get('activeGroupId') != baseline.get('activeGroupId') or restored['intent'] != 'PAUSE' or restored['positionMs'] != baseline['positionMs']:
            raise AcceptanceError('BASELINE_PAUSE_NOT_RESTORED')
        after_files = {e['videoId']: e for e in restored.get('prepared', {}).get('files', [])}
        if any(e['videoId'] not in after_files or after_files[e['videoId']]['bytes'] != e['bytes'] for e in baseline['prepared'].get('files', [])):
            raise AcceptanceError('BASELINE_READY_FILE_CHANGED')
        after_tracks = ready_track_sizes(restored)
        if any(after_tracks.get(path) != value for path, value in protected_tracks.items()):
            raise AcceptanceError('BASELINE_INTERNAL_TRACK_CHANGED')
        if protected_hashes() != protected:
            raise AcceptanceError('PROTECTED_FILE_HASH_CHANGED')
        cleanup_needed = False
        record('baselineProgrammePauseAndProtectedData', 'PASS')

    try:
        if shell('getprop', 'ro.serialno') != args.expected_hardware_serial:
            raise AcceptanceError('HARDWARE_SERIAL_MISMATCH')
        sdk = shell('getprop', 'ro.build.version.sdk')
        installed = shell('dumpsys', 'package', PACKAGE)
        version = re.search(r'\bversionCode=(\d+)', installed)
        if sdk != '34' or not version or int(version.group(1)) != 17:
            raise AcceptanceError('EXPECTED_H728_ANDROID14_APK17')
        record('confirmedDevice', 'PASS', {'androidSdk': sdk, 'versionCode': 17})
        token_path = pathlib.Path(args.device_file)
        if token_path.stat().st_mode & 0o077:
            raise AcceptanceError('TOKEN_FILE_MUST_BE_PRIVATE')
        device = json.loads(token_path.read_text())
        secret = device['token']
        if not isinstance(secret, str) or not secret:
            raise AcceptanceError('TOKEN_FILE_INVALID')
        baseline = api('/api/state')
        if baseline.get('intent') != 'PAUSE' or not current_item(baseline):
            raise AcceptanceError('BASELINE_MUST_BE_PAUSED_WITH_CURRENT_VIDEO')
        if not current_item(baseline).get('ready'):
            raise AcceptanceError('NOT_RUN_BASELINE_CURRENT_MUST_BE_READY_FOR_RESTORE')
        if not baseline.get('prepared', {}).get('paused'):
            raise AcceptanceError('NOT_RUN_OTHER_PREPARATION_MUST_ALREADY_BE_PAUSED')
        on_disk = json.loads(shell('run-as', PACKAGE, 'cat', 'files/queue-state.json'))
        saved_programme = on_disk.get('programme') or {}
        if saved_programme.get('playNext') is not None or saved_programme.get('stopAfterCurrent'):
            raise AcceptanceError('NOT_RUN_EXISTING_ONE_SHOT_PLAYBACK_PLAN')
        expected = baseline['queueVersion']
        protected = protected_hashes()
        protected_tracks = ready_track_sizes(baseline)
        baseline_known = {e['videoId'] for e in baseline['items']}
        baseline_known.update(e['videoId'] for e in baseline['prepared'].get('files', []))
        baseline_known.update(e['videoId'] for e in baseline['prepared'].get('entries', []))
        baseline_known.update(e['videoId'] for e in baseline.get('streaming', {}).get('entries', []))
        report['baseline'] = {'items': len(baseline['items']), 'groups': len(baseline['groups']),
                              'positionMs': baseline['positionMs'], 'readyFiles': len(baseline['prepared'].get('files', []))}
        response, _ = mutate('/api/video/preview', {'url': args.video_url})
        preview = response['preview']
        deadline = time.monotonic() + 130
        while preview['status'] == 'LOADING' and time.monotonic() < deadline:
            state()
            time.sleep(.5)
            preview = api('/api/video/preview/' + urllib.parse.quote(preview['previewId']))['preview']
        if preview['status'] != 'COMPLETE':
            raise AcceptanceError('REAL_PREVIEW_NOT_COMPLETE')
        video_id = preview['videoId']
        report['videoId'] = video_id
        record('realSingleVideoPreview', 'PASS', {'qualityHeights': [q['height'] for q in preview['qualities']]})
        ready_before = any(e['videoId'] == video_id for e in baseline['prepared'].get('files', [])) or any(e['videoId'] == video_id and e.get('ready') for e in baseline['items'])
        if ready_before:
            record('earlyStreaming', 'NOT_RUN', 'Video was already READY at baseline; no stream proof possible.')
            report['status'] = 'NOT_RUN'
            return 2
        quality = min(preview['qualities'], key=lambda q: q['height'])
        storage = api('/api/storage')
        state()
        command = {'previewId': preview['previewId'], 'action': 'WATCH_SAVE', 'qualityId': quality['id'],
                   'storageId': storage['selectedId'], 'storageVersion': storage['storageVersion'], 'groupName': group_name}
        report['notes'].append('Acceptance creates ordinary history/receipt/undo records; it compares restored programme, files and playback, not those journals.')
        cleanup_needed = True
        try:
            result, current = mutate('/api/video/apply', command, 2, 130)
        except ApiError as error:
            # A failed preparation may still have atomically saved our group. Recover only that exact delta.
            current = api('/api/state')
            if current['queueVersion'] == expected and current['groups'] == baseline['groups'] and queue_identity(current) == queue_identity(baseline):
                cleanup_needed = False
                raise error
            own_group = assert_owned_structure(current)
            if current['queueVersion'] != expected + 1 or current.get('intent') != baseline['intent']:
                concurrent = True
                raise ConcurrentChange('FAILED_APPLY_CONTEXT_CHANGED') from None
            expected = current['queueVersion']
            raise error
        own_group = assert_owned_structure(current)
        if result.get('error') or current_item(current)['videoId'] != video_id:
            raise AcceptanceError('WATCH_SAVE_NOT_ACCEPTED')
        playing = wait_for(lambda s: s.get('status') == 'PLAYING' and current_item(s)['videoId'] == video_id, 60, 'FIRST_PLAYING')
        first = playing['positionMs']
        advanced = wait_for(lambda s: s.get('status') == 'PLAYING' and s['positionMs'] >= first + 700, 15, 'EARLY_PROGRESS')
        entry = stream_entry(advanced)
        downloaded = entry.get('downloadedBytes') if entry else None
        total = entry.get('totalBytes') if entry else None
        early = (isinstance(downloaded, (int, float)) and isinstance(total, (int, float))
                 and 0 <= downloaded < total and not advanced.get('localPlayback')
                 and not current_item(advanced).get('ready'))
        record('earlyStreaming', 'PASS' if early else 'NOT_OBSERVED', {'positionAdvancedMs': advanced['positionMs'] - first,
                'downloadPercent': entry.get('percent') if entry else None, 'downloadedBytes': downloaded, 'totalBytes': total})
        if advanced.get('seekable') and advanced.get('durationMs', 0) > 4000:
            target = min(3000, advanced['durationMs'] - 1000)
            mutate('/api/playback/seek', {'queueItemId': current_item(advanced)['queueItemId'],
                'playbackGeneration': advanced['playbackGeneration'], 'positionMs': target}, 1)
            wait_for(lambda s: s.get('status') == 'PLAYING' and target <= s['positionMs'] <= target + 3500, 15, 'SEEK_PLAYING')
            record('streamSeek', 'PASS')
        else:
            record('streamSeek', 'NOT_RUN', 'No confirmed seekable timeline yet.')
        started = time.monotonic()
        _, stopped = mutate('/api/playback', {'action': 'STOP'}, 1)
        elapsed = time.monotonic() - started
        record('stopResponsiveness', 'PASS' if stopped['intent'] == 'STOP' and elapsed < 5 else 'FAIL', {'seconds': round(elapsed, 3)})
        ready = wait_for(lambda s: any(e['videoId'] == video_id and e.get('ready') for e in s['items']), 180, 'FULL_READY')
        item = next(e for e in ready['items'] if e['videoId'] == video_id)
        mutate('/api/queue/select', {'queueItemId': item['queueItemId'], 'groupId': own_group, 'positionMs': 0, 'play': True}, 1)
        local = wait_for(lambda s: s.get('status') == 'PLAYING' and s.get('localPlayback'), 30, 'LOCAL_REPLAY')
        start_position = local['positionMs']
        local = wait_for(lambda s: s.get('localPlayback') and s['positionMs'] >= start_position + 700, 10, 'LOCAL_PROGRESS')
        record('verifiedReadyLocalReplay', 'PASS', {'positionAdvancedMs': local['positionMs'] - start_position})
        restore_and_cleanup()
        required = ('earlyStreaming', 'streamSeek', 'stopResponsiveness', 'verifiedReadyLocalReplay', 'baselineProgrammePauseAndProtectedData')
        report['status'] = 'PASS' if all(report['checks'].get(name, {}).get('status') == 'PASS' for name in required) else 'INCOMPLETE'
        return 0 if report['status'] == 'PASS' else 2
    except ConcurrentChange as error:
        concurrent = True
        report['status'] = 'BLOCKED_CONCURRENT_CHANGE'
        report['notes'].append(str(error))
        return 3
    except Exception as error:
        report['status'] = 'FAIL'
        report['notes'].append(str(error) if isinstance(error, AcceptanceError) else type(error).__name__)
        return 1
    finally:
        if cleanup_needed and baseline is not None:
            if concurrent:
                report['notes'].append('No cleanup over a concurrent/ambiguous change. Review the uniquely named temporary group and playback manually.')
            elif video_id is not None:
                try:
                    restore_and_cleanup()
                except Exception as error:
                    report['notes'].append('RESTORATION_NEEDS_FOLLOW_UP: ' + (str(error) if isinstance(error, AcceptanceError) else type(error).__name__))
        write_report()
        print(json.dumps({'status': report['status'], 'report': str(output), 'temporaryGroupRemoved': report['temporaryGroupRemoved']}, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception as error:
        print(json.dumps({'status': 'NOT_RUN', 'error': str(error) if isinstance(error, AcceptanceError) else type(error).__name__}), flush=True)
        sys.exit(2)
