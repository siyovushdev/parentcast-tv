#!/usr/bin/env python3
import concurrent.futures
import hashlib
import http.client
import json
import os
import secrets
import subprocess
import threading
import time
import urllib.error
import urllib.request

from tools import m1_hardware_test as h

PACKAGE = "dev.siyovush.parentcast.tv.debug"


def check(name, condition, **details):
    if not condition:
        raise RuntimeError(f"Failed: {name}")
    print(json.dumps({"check": name, "status": "PASS", **details}, ensure_ascii=False))


def command_key(prefix):
    return f"{prefix}-{secrets.token_hex(10)}"


def playback(token, action):
    return h.api("/api/playback", "POST", {"action": action, "idempotencyKey": command_key(action)}, token)


def add(token, link, key):
    return h.api("/api/queue/add", "POST", {"links": [link], "idempotencyKey": key}, token)


def screenshot(name):
    data = subprocess.check_output(["adb", "-s", h.SERIAL, "exec-out", "screencap", "-p"])
    path = f"app/build/{name}.png"
    with open(path, "wb") as output:
        output.write(data)
    return hashlib.sha256(data).hexdigest()


def wait_health():
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        try:
            if h.api("/api/health")[0] == 200:
                return
        except (OSError, urllib.error.URLError):
            pass
        time.sleep(0.5)
    raise RuntimeError("HTTP server did not restart")


def main():
    h.adb("shell", "input", "keyevent", "KEYCODE_MENU")
    pin = h.create_pin_if_needed()
    if pin is None:
        raise RuntimeError("PIN controls are unavailable")
    token_a, device_a = h.pair_device("Ubuntu API A", pin)
    before = h.state(token_a)
    if not os.environ.get("M1_SKIP_CONTINUITY"):
        check("browserClosedInitialState", before["status"] == "PLAYING", playerStatus=before["status"])

        h.adb("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(3)
        middle = h.state(token_a)
        first_frame = screenshot("h728-m1-browser-closed-1")
        time.sleep(5)
        after = h.state(token_a)
        second_frame = screenshot("h728-m1-browser-closed-2")
        check(
            "browserClosedPlaybackContinues",
            after["status"] == "PLAYING"
            and after["currentIndex"] == before["currentIndex"]
            and after["positionMs"] >= middle["positionMs"] + 4000
            and first_frame != second_frame,
            positionBeforeMs=middle["positionMs"],
            positionAfterMs=after["positionMs"],
            framesDiffer=first_frame != second_frame,
        )

    status, _ = playback(token_a, "STOP")
    check("stopBeforeSecurityChecks", status == 200)
    time.sleep(1)
    check("unauthorizedState", h.api("/api/state")[0] == 401, httpStatus=401)
    check("invalidToken", h.api("/api/state", token="invalid-test-token")[0] == 401, httpStatus=401)

    blocked_origin = h.api(
        "/api/playback",
        "POST",
        {"action": "PLAY", "idempotencyKey": command_key("evil-origin")},
        token_a,
        {"Origin": "https://evil.example", "Referer": "https://evil.example/"},
    )
    check("foreignOriginBlocked", blocked_origin[0] == 403, httpStatus=blocked_origin[0])
    blocked_host = h.api("/api/state", token=token_a, headers={"Host": "evil.example:8765"})
    check("rebindingHostBlocked", blocked_host[0] == 421, httpStatus=blocked_host[0])
    no_client = h.api(
        "/api/playback",
        "POST",
        {"action": "PLAY", "idempotencyKey": command_key("no-client")},
        token_a,
        {"X-ParentCast-Client": ""},
    )
    check("customHeaderRequired", no_client[0] == 403, httpStatus=no_client[0])

    request = urllib.request.Request(
        h.BASE + "/api/playback",
        data=b"{}",
        method="OPTIONS",
        headers={"Origin": h.ORIGIN, "X-ParentCast-Client": "m1"},
    )
    try:
        urllib.request.urlopen(request, timeout=10)
        raise RuntimeError("OPTIONS unexpectedly succeeded")
    except urllib.error.HTTPError as error:
        check(
            "preflightAndCorsBlocked",
            error.code == 403 and error.headers.get("Access-Control-Allow-Origin") is None,
            httpStatus=error.code,
            corsHeader=False,
        )
    check("blockedCommandsDidNotPlay", h.state(token_a)["status"] == "STOPPED")

    h.adb("shell", "input", "keyevent", "KEYCODE_MENU")
    token_b, device_b = h.pair_device("Ubuntu API B", pin)
    check("secondDevicePaired", h.state(token_b)["status"] == "STOPPED")
    revoke = h.api(
        "/api/devices/revoke",
        "POST",
        {"deviceId": device_b, "idempotencyKey": command_key("revoke-b")},
        token_a,
    )
    check("revokeSecondDevice", revoke[0] == 200)
    check("revokedTokenState", h.api("/api/state", token=token_b)[0] == 401, httpStatus=401)
    check("revokedTokenCommand", playback(token_b, "PLAY")[0] == 401, httpStatus=401)

    token_b2, _ = h.pair_device("Ubuntu API B2", pin)
    stale = h.state(token_b2)
    added = add(token_a, "https://youtu.be/aqz-KE-bpKQ", command_key("version-add"))
    conflict = h.api(
        "/api/queue/clear",
        "POST",
        {"queueVersion": stale["queueVersion"], "idempotencyKey": command_key("stale-clear")},
        token_b2,
    )
    check(
        "twoClientVersionConflict",
        added[0] == 200 and conflict[0] == 409 and conflict[1].get("error") == "QUEUE_VERSION_CONFLICT",
        httpStatus=conflict[0],
    )

    before_loss = h.state(token_a)
    body = {"links": ["https://youtu.be/jNQXAC9IVRw"], "idempotencyKey": command_key("lost-add")}
    encoded = json.dumps(body)
    connection = http.client.HTTPConnection(h.HOST, h.PORT, timeout=30)
    connection.request(
        "POST",
        "/api/queue/add",
        body=encoded,
        headers={
            "Authorization": f"Bearer {token_a}",
            "X-ParentCast-Client": "m1",
            "Origin": h.ORIGIN,
            "Referer": f"{h.ORIGIN}/",
            "Content-Type": "application/json",
        },
    )
    connection.sock.close()
    time.sleep(1)
    retry_status, retry_body = add(token_a, body["links"][0], body["idempotencyKey"])
    after_loss = h.state(token_a)
    check(
        "lostHttpResponseRetry",
        retry_status == 200
        and len(after_loss["items"]) == len(before_loss["items"]) + 1
        and after_loss["queueVersion"] == before_loss["queueVersion"] + 1,
        addedExactlyOnce=True,
    )
    check("idempotentStoredResponse", add(token_a, body["links"][0], body["idempotencyKey"])[1] == retry_body)

    concurrent_body = {"links": ["https://youtu.be/eRsGyueVLvQ"], "idempotencyKey": command_key("concurrent-add")}
    before_concurrent = h.state(token_a)
    barrier = threading.Barrier(8)

    def concurrent_add():
        barrier.wait()
        return add(token_a, concurrent_body["links"][0], concurrent_body["idempotencyKey"])

    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as executor:
        responses = list(executor.map(lambda _: concurrent_add(), range(8)))
    after_concurrent = h.state(token_a)
    check(
        "simultaneousIdenticalCommands",
        all(response[0] == 200 for response in responses)
        and len({json.dumps(response[1], sort_keys=True) for response in responses}) == 1
        and len(after_concurrent["items"]) == len(before_concurrent["items"]) + 1,
        addedExactlyOnce=True,
    )

    playback(token_a, "STOP")
    before_restart = h.state(token_a)
    h.adb("shell", "am", "force-stop", PACKAGE)
    h.adb("shell", "monkey", "-p", PACKAGE, "-c", "android.intent.category.LAUNCHER", "1")
    wait_health()
    after_restart = h.state(token_a)
    check(
        "restartPreservesStoppedQueue",
        after_restart["status"] == "STOPPED"
        and after_restart["intent"] == "STOP"
        and after_restart["items"] == before_restart["items"]
        and after_restart["currentIndex"] == before_restart["currentIndex"]
        and after_restart["positionMs"] == before_restart["positionMs"]
        and after_restart["queueVersion"] == before_restart["queueVersion"],
        intent=after_restart["intent"],
        queueVersion=after_restart["queueVersion"],
    )

    devices = h.api("/api/devices", token=token_a)[1]["devices"]
    revoked = 0
    devices = [
        device for device in devices
        if device["revokedAtMs"] == 0 and device["label"] != "Ubuntu Chrome M1"
    ]
    devices.sort(key=lambda device: device["deviceId"] == device_a)
    for device in devices:
        response = h.api(
            "/api/devices/revoke",
            "POST",
            {"deviceId": device["deviceId"], "idempotencyKey": command_key("cleanup")},
            token_a,
        )
        if response[0] != 200:
            raise RuntimeError("Could not revoke API test device")
        revoked += 1
    check("apiTestTokensRevoked", h.api("/api/state", token=token_a)[0] == 401, revokedDevices=revoked)


if __name__ == "__main__":
    main()
