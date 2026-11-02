#!/usr/bin/env python3
"""Explicit H728-only UI helpers; no reboot, provisioning or secret output."""
import json
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

SERIAL = os.environ.get('H728_SERIAL')
PACKAGE = 'dev.siyovush.parentcast.tv.debug'

def adb(*args):
    if not SERIAL:
        raise RuntimeError('Set H728_SERIAL to the already identified target')
    result = subprocess.run(['adb', '-s', SERIAL, *args], text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError('ADB action failed; command arguments suppressed')
    return result.stdout

def identify():
    assert adb('shell', 'getprop', 'ro.soc.model').strip() == 'h728'
    assert adb('shell', 'getprop', 'ro.build.version.release').strip() == '14'

def ui():
    adb('shell', 'uiautomator', 'dump', '/sdcard/window.xml')
    return ET.fromstring(adb('shell', 'cat', '/sdcard/window.xml'))

def node(resource_id, root=None):
    for item in (root if root is not None else ui()).iter('node'):
        if item.attrib.get('resource-id', '').endswith(':id/' + resource_id):
            return item
    return None

def visible(resource_id):
    n = node(resource_id)
    return n is not None and n.attrib.get('bounds') != '[0,0][0,0]'

def click_node(n):
    x1,y1,x2,y2 = map(int, re.findall(r'\d+', n.attrib['bounds']))
    assert x2 > x1 and y2 > y1
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))

def click(resource_id, scroll=False):
    for _ in range(10 if scroll else 1):
        root = ui(); n = node(resource_id, root)
        if n is not None and n.attrib.get('bounds') != '[0,0][0,0]':
            click_node(n); return
        if scroll:
            assert any(n.attrib.get('package') == PACKAGE for n in root.iter('node'))
            adb('shell', 'input', 'swipe', '1740', '850', '1740', '300', '300')
    raise RuntimeError('UI control not found: ' + resource_id)

def wait_control(resource_id, timeout=25):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if visible(resource_id): return
        time.sleep(.3)
    raise RuntimeError('UI did not reach expected control: ' + resource_id)

def fill(resource_id, value):
    click(resource_id)
    adb('shell', 'input', 'keyevent', 'KEYCODE_MOVE_END', *(['KEYCODE_DEL'] * 12))
    adb('shell', 'input', 'text', value)

def open_gate():
    adb('shell', 'input', 'keyevent', '--longpress', 'KEYCODE_MENU')
    wait_control('pinGateInput')

def unlock():
    # The native ScrollView retains its prior offset; an off-screen modeStatus is not a denial.
    def parent_visible():
        root = ui()
        return node('pinGateInput', root) is None and any(node(i, root) is not None for i in
            ['modeStatus', 'panelStatus', 'pairStatus', 'debugStatus', 'backButton', 'linkInput'])
    if not parent_visible():
        open_gate()
        pin = Path('.dev/m2/current-test-pin').read_text().strip()
        assert re.fullmatch(r'\d{6,12}', pin)
        fill('pinGateInput', pin)
        click('button1')
        deadline = time.monotonic() + 25
        while time.monotonic() < deadline:
            if parent_visible(): break
            time.sleep(.3)
        else: raise RuntimeError('Parent access not confirmed; secret arguments suppressed')
    for _ in range(8):
        if visible('modeStatus'): return
        adb('shell', 'input', 'swipe', '1740', '300', '1740', '850', '300')
    raise RuntimeError('Parent settings reached, but top controls are off-screen')

def mode_text():
    n = node('modeStatus')
    return n.attrib.get('text', '') if n is not None else ''

if __name__ == '__main__':
    identify(); unlock()
    print(json.dumps({'parentPinAccepted': visible('modeStatus'), 'mode': mode_text()}, ensure_ascii=False))
