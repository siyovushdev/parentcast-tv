#!/usr/bin/env python3
import json
import os
import secrets
import subprocess
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

SERIAL = os.environ.get("H728_SERIAL", "192.168.68.121:5555")
HOST = os.environ.get("H728_HOST", "192.168.68.121")
PORT = int(os.environ.get("H728_PORT", "8765"))
BASE = f"http://{HOST}:{PORT}"
ORIGIN = BASE


def adb(*args, input_text=None):
    return subprocess.run(
        ["adb", "-s", SERIAL, *args],
        input=input_text,
        text=True,
        capture_output=True,
        check=True,
    ).stdout


def ui_root():
    adb("shell", "uiautomator", "dump", "/sdcard/window.xml")
    return ET.fromstring(adb("shell", "cat", "/sdcard/window.xml"))


def bounds(node):
    raw = node.attrib["bounds"].strip("[]").replace("][", ",").replace("-", ",")
    return tuple(map(int, raw.split(",")))


def find_id(root, resource_id):
    for node in root.iter("node"):
        if node.attrib.get("resource-id", "").endswith(f":id/{resource_id}"):
            return node
    raise RuntimeError(f"UI id not found: {resource_id}")


def click(resource_id):
    root = ui_root()
    node = find_id(root, resource_id)
    left, top, right, bottom = bounds(node)
    x, y = (left + right) // 2, (top + bottom) // 2
    adb("shell", "input", "tap", str(x), str(y))


def set_text(resource_id, value):
    click(resource_id)
    adb("shell", "input", "keyevent", "KEYCODE_MOVE_END", *(["KEYCODE_DEL"] * 12))
    adb("shell", "input", "text", value)
    wait_until(lambda: len(text(resource_id)) == len(value), f"Text was not applied: {resource_id}")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(0.5)


def text(resource_id):
    return find_id(ui_root(), resource_id).attrib.get("text", "")


def wait_until(condition, message):
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        if condition():
            return
        time.sleep(0.25)
    raise RuntimeError(message)


def api(path, method="GET", body=None, token=None, headers=None):
    request_headers = {
        "X-ParentCast-Client": "m1",
        "Origin": ORIGIN,
        "Referer": f"{ORIGIN}/",
        "Content-Type": "application/json",
    }
    if token:
        request_headers["Authorization"] = f"Bearer {token}"
    if headers:
        request_headers.update(headers)
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(BASE + path, data=data, method=method, headers=request_headers)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            payload = response.read().decode()
            return response.status, json.loads(payload) if payload else {}
    except urllib.error.HTTPError as error:
        payload = error.read().decode()
        try:
            parsed = json.loads(payload) if payload else {}
        except json.JSONDecodeError:
            parsed = {"error": payload}
        return error.code, parsed


def create_pin_if_needed():
    ids = {node.attrib.get("resource-id", "") for node in ui_root().iter("node")}
    if not any(resource_id.endswith(":id/pinInput") for resource_id in ids):
        return None
    has_existing_pin = any(resource_id.endswith(":id/newPinInput") for resource_id in ids)
    current_pin = os.environ.get("PARENTCAST_CURRENT_PIN") if has_existing_pin else None
    if has_existing_pin and not current_pin:
        return None
    pin = secrets.randbelow(900000) + 100000
    if has_existing_pin:
        set_text("pinInput", current_pin)
        set_text("newPinInput", str(pin))
    else:
        set_text("pinInput", str(pin))
    click("savePinButton")
    entered_pin = current_pin if has_existing_pin else str(pin)
    wait_until(lambda: text("pinInput") != "•" * len(entered_pin), "PIN was not saved")
    return pin


def begin_pairing(current_pin):
    set_text("pinInput", str(current_pin))
    click("pairButton")
    wait_until(lambda: text("pairStatus").startswith("Код: "), "Pairing code was not created")
    raw = text("pairStatus")
    return raw.split("Код: ", 1)[1].split(" ", 1)[0]


def pair_device(label, pin):
    code = begin_pairing(pin)
    status, result = api("/api/pair", "POST", {"label": label, "pin": pin, "code": code})
    if status != 200:
        raise RuntimeError(f"Pairing failed: {status} {result.get('error')}")
    return result["token"], result["deviceId"]


def state(token):
    status, result = api("/api/state", token=token)
    if status != 200:
        raise RuntimeError(f"State failed: {status} {result.get('error')}")
    return result


def post(path, token, payload):
    status, result = api(path, "POST", payload, token=token)
    return status, result


def add_link(token, link, key):
    return post("/api/queue/add", token, {"links": [link], "idempotencyKey": key})


def main():
    pin = create_pin_if_needed()
    if pin is None:
        raise RuntimeError("PIN controls are unavailable")
    token, device_id = pair_device("Ubuntu integration", pin)
    print(json.dumps({"paired": True, "deviceId": device_id}))

    status, _ = api("/api/state")
    print(json.dumps({"unauthorizedStatus": status}))

    key = f"add-{secrets.token_hex(8)}"
    first = add_link(token, "https://youtu.be/jNQXAC9IVRw", key)
    second = add_link(token, "https://youtu.be/jNQXAC9IVRw", key)
    print(json.dumps({"addFirst": first[0], "addSecond": second[0], "sameBody": first[1] == second[1]}))


if __name__ == "__main__":
    main()
