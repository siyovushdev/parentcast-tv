#!/usr/bin/env python3
"""Owner ADB recovery only. Restores the explicitly supplied existing HOME, changes no other apps."""
import argparse
import re
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True)
parser.add_argument('--home', required=True)
args = parser.parse_args()
if not re.fullmatch(r'[A-Za-z][A-Za-z0-9_.]*/[A-Za-z0-9_.]+', args.home):
    raise SystemExit('Invalid HOME component')
if args.home.startswith('dev.siyovush.parentcast.tv'):
    raise SystemExit('Recovery HOME must be the saved original launcher')
def adb(*commands):
    return subprocess.check_output(['adb', '-s', args.serial, *commands], text=True).strip()
if adb('shell', 'getprop', 'ro.soc.model') != 'h728':
    raise SystemExit('Target is not confirmed H728')
homes = adb('shell', 'cmd', 'package', 'query-activities', '--brief', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')
if args.home not in homes.splitlines() and args.home not in [s.strip() for s in homes.splitlines()]:
    raise SystemExit('Saved launcher is not an enabled HOME activity')
print(adb('shell', 'cmd', 'package', 'set-home-activity', '--user', '0', args.home))
print(adb('shell', 'am', 'start', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME', '-n', args.home))
resolved = adb('shell', 'cmd', 'package', 'resolve-activity', '--brief', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')
if args.home not in resolved:
    raise SystemExit('HOME recovery not confirmed; preserve ADB and inspect resolver')
print('Original HOME resolver and explicit launch confirmed; ADB retained')
