#!/usr/bin/env python3
"""Safe application-process restart check. This is NOT a device reboot test."""
import argparse
import json
import os
from pathlib import Path
import time
import urllib.request
import m3_hardware_test as h

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--before',choices=['PLAY','PAUSE','STOP'],required=True)
parser.add_argument('--after',choices=['PLAY','PAUSE','STOP'],required=True)
parser.add_argument('--label',required=True)
args=parser.parse_args()
if not args.label.replace('-','').isalnum(): raise SystemExit('Invalid evidence label')
h.identify()
def state(): return json.loads(h.adb('shell','run-as',h.PACKAGE,'cat','files/queue-state.json'))
for _ in range(40):
    before=state()
    if before['intent']==args.before: break
    time.sleep(.2)
else: raise RuntimeError('Expected pre-restart intent was not observed')
h.adb('shell','am','force-stop',h.PACKAGE)
h.adb('shell','am','start','-n',h.PACKAGE+'/dev.siyovush.parentcast.tv.ui.ParentCastHome')
op=urllib.request.build_opener(urllib.request.ProxyHandler({}))
for _ in range(40):
 try:
  with op.open('http://127.0.0.1:8765/api/health',timeout=2) as r:
   if r.status==200:break
 except Exception:pass
 time.sleep(.3)
else:raise RuntimeError('Application server did not return after process restart')
time.sleep(2)
after=state();assert after['intent']==args.after
assert after['items']==before['items'] and after['currentIndex']==before['currentIndex']
assert abs(after['positionMs']-before['positionMs'])<=6000
report={'kind':'APP_PROCESS_RESTART_NOT_REBOOT','status':'PASS','beforeIntent':args.before,'restoredIntent':args.after,'sameOrderedQueue':True,'sameCurrentIndex':True,'savedPositionBeforeMs':before['positionMs'],'savedPositionAfterMs':after['positionMs'],'items':len(after['items'])}
Path('artifacts/m3-home-kiosk/'+args.label+'-safe.json').write_text(json.dumps(report,indent=2));print(json.dumps(report))
