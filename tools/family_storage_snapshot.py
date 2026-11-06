#!/usr/bin/env python3
"""Read-only H728 preservation evidence. No PIN/cookie/token values are collected.

Run on the PC connected to the confirmed device:
  python3 tools/family_storage_snapshot.py --adb /path/to/adb --serial HOST:5555 \
    --expected-hardware-serial HARDWARE_SERIAL --output .dev/family-library/before.json

Stores catalogue/queue hashes and existing track lengths, not media URL or credentials.
This checks retained metadata and file presence/size, not a fresh full media SHA scan.
"""
import argparse
import json
import os
import pathlib
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--adb', required=True)
parser.add_argument('--serial', required=True)
parser.add_argument('--expected-hardware-serial', required=True)
parser.add_argument('--output', required=True)
args = parser.parse_args()
package = 'dev.siyovush.parentcast.tv.debug'


def shell(*parts):
    return subprocess.check_output([args.adb, '-s', args.serial, 'shell', *parts], text=True, timeout=30).strip()


def private(*parts):
    return shell('run-as', package, *parts)


if shell('getprop', 'ro.serialno') != args.expected_hardware_serial:
    raise SystemExit('Target identity mismatch; no application data accessed')

files = private('find', 'files', 'no_backup', 'shared_prefs', '-maxdepth', '2', '-type', 'f').splitlines()
protected = sorted(path for path in files if path.startswith('shared_prefs/') or path in {
    'files/device-tokens.json', 'no_backup/saved-collections.json',
} or 'cookie' in path.lower())
# Hashes are calculated on Android; secret file contents never leave the application.
hashes = {}
for path in protected:
    # Only paths obtained from the app inventory are accepted; reject shell metacharacters.
    if not all(c.isalnum() or c in '/_.-' for c in path):
        raise SystemExit('Unexpected private file path')
    hashes[path] = private('sha256sum', path).split()[0]

queue = json.loads(private('cat', 'files/queue-state.json'))
index = json.loads(private('cat', 'no_backup/prepared-programme/index.json'))
tracks = []
for entry in index['entries']:
    if entry['status'] != 'READY':
        continue
    for track in entry['tracks']:
        name = track['name']
        if '/' in name or any(c in name for c in " ;&|$`'\"\\\n\r"):
            raise SystemExit('Unexpected media filename')
        filename = 'no_backup/prepared-programme/' + name
        actual = int(private('stat', '-c', '%s', filename))
        tracks.append({'videoId': entry['id'], 'name': name, 'bytes': track['bytes'],
                       'actualBytes': actual, 'recordedSha256': track['sha256']})

result = {'hardwareSerial': args.expected_hardware_serial,
          'protectedHashes': hashes, 'queue': queue, 'tracks': tracks,
          'scope': 'pre-migration internal library; recorded checksums and current lengths only'}
target = pathlib.Path(args.output)
target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
descriptor = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
with os.fdopen(descriptor, 'w') as output:
    json.dump(result, output)
print(json.dumps({'protectedFiles': len(hashes), 'readyTracks': len(tracks),
                  'bytesMatch': all(t['bytes'] == t['actualBytes'] for t in tracks)}))
