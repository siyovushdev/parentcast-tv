#!/usr/bin/env python3
"""Live M2 saved-batch idempotency/conflict test. Removes only its own appended items."""
import concurrent.futures
import json
from pathlib import Path
import secrets
import threading
import urllib.error
import urllib.request

BASE = 'http://192.168.68.121:8765'


def main():
    token = json.loads(Path('.dev/m2/api-device.json').read_text())['token']
    def api(path, body=None):
        headers = {'Authorization': 'Bearer ' + token, 'Origin': BASE, 'X-ParentCast-Client': 'm1',
                   'Content-Type': 'application/json; charset=utf-8'}
        request = urllib.request.Request(BASE + path, headers=headers,
                    data=None if body is None else json.dumps(body).encode())
        try:
            response = urllib.request.build_opener(urllib.request.ProxyHandler({})).open(request, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            return response.status, json.loads(response.read())
    def state():
        code, value = api('/api/state')
        assert code == 200
        return value
    def key():
        return 'm2-hardware-' + secrets.token_hex(12)
    code, payload = api('/api/collections')
    assert code == 200
    collection = next(c for c in payload['collections'] if c['name'] == 'M2 — фиксированная программа')
    assert collection['videoIds'] == ['YYXdXT2l-Gg', 'yqm6MBt-yfY']
    before = state()
    assert before['intent'] == 'PLAY'
    current_id = before['items'][before['currentIndex']]['queueItemId']
    original_ids = {v['queueItemId'] for v in before['items']}
    body = {'collectionId': collection['id'], 'queueVersion': before['queueVersion'], 'idempotencyKey': key()}
    barrier = threading.Barrier(8)
    def repeat(_):
        barrier.wait()
        return api('/api/collections/apply', body)
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        replies = list(pool.map(repeat, range(8)))
    assert all(code == 200 for code, _ in replies)
    assert all(value == replies[0][1] for _, value in replies)
    after = state()
    assert len(after['items']) == len(before['items']) + 2
    assert after['queueVersion'] == before['queueVersion'] + 1
    assert after['items'][after['currentIndex']]['queueItemId'] == current_id and after['intent'] == 'PLAY'
    barrier = threading.Barrier(2)
    def race(_):
        command = {'collectionId': collection['id'], 'queueVersion': after['queueVersion'], 'idempotencyKey': key()}
        barrier.wait()
        return api('/api/collections/apply', command)
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        conflict = list(pool.map(race, range(2)))
    assert sorted(code for code, _ in conflict) == [200, 409]
    assert next(body for code, body in conflict if code == 409)['error'] == 'QUEUE_VERSION_CONFLICT'
    final = state()
    assert len(final['items']) == len(before['items']) + 4
    assert final['queueVersion'] == after['queueVersion'] + 1
    assert final['items'][final['currentIndex']]['queueItemId'] == current_id and final['intent'] == 'PLAY'
    # Undo only additions created by this test, preserving the actual imported program and current item.
    added = [item['queueItemId'] for item in final['items'] if item['queueItemId'] not in original_ids]
    for item_id in added:
        snapshot = state()
        assert item_id in [item['queueItemId'] for item in snapshot['items'][snapshot['currentIndex'] + 1:]]
        code, _ = api('/api/queue/delete', {'queueItemId': item_id, 'queueVersion': snapshot['queueVersion'], 'idempotencyKey': key()})
        assert code == 200
    clean = state()
    assert [item['queueItemId'] for item in clean['items']] == [item['queueItemId'] for item in before['items']]
    report = {'kind': 'LIVE_H728_HTTP', 'repeat8': 'PASS', 'oneVersionIncrement': True,
              'concurrentCodes': [200, 409], 'queueConflict': 'PASS', 'playbackIntentPreserved': True,
              'currentItemPreserved': True, 'ownTestAdditionsRemoved': len(added), 'fixedVideoIds': collection['videoIds']}
    Path('artifacts/m2-playlist-import/queue-acceptance-safe.json').write_text(json.dumps(report, indent=2))
    print(json.dumps(report))


if __name__ == '__main__':
    main()
