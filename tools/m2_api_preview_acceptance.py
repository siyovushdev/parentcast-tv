#!/usr/bin/env python3
"""Live M2 preview-only checks. Credentials are read privately and never printed.

Requires an already paired test device. No queue/playback commands are sent, except
apply/save requests after a real LIMIT_EXCEEDED result that must be rejected.
Run while other parents are not changing the queue. This is a network/hardware
acceptance tool, separate from synthetic unit tests.
"""
import argparse
import hashlib
import json
from pathlib import Path
import secrets
import time
import urllib.error
import urllib.request

LARGE_PUBLIC_PLAYLIST = "https://www.youtube.com/playlist?list=PLRBp0Fe2GpgmgoscNFLxNyBVSFVdYmFkq"
CANCEL_PUBLIC_PLAYLIST = "https://www.youtube.com/playlist?list=PL-osiE80TeTt2d9bfVyTiXJA-UTHn6WwU"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="http://192.168.68.121:8765")
    parser.add_argument("--credentials", type=Path, default=Path(".dev/m2/api-device.json"))
    parser.add_argument("--output", type=Path, default=Path("app/build/m2-api-safe.json"))
    parser.add_argument("--large-playlist", default=LARGE_PUBLIC_PLAYLIST)
    parser.add_argument("--cancel-observation-seconds", type=float, default=22)
    args = parser.parse_args()
    token = json.loads(args.credentials.read_text())["token"]
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    report = {"base": args.base, "checks": [], "kind": "LIVE_H728_HTTP_AND_YOUTUBE"}

    def api(path, body=None):
        headers = {"Authorization": "Bearer " + token, "X-ParentCast-Client": "m1", "Origin": args.base}
        if body is not None:
            body = dict(body, idempotencyKey="m2-preview-check-" + secrets.token_hex(12))
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        else:
            data = None
        request = urllib.request.Request(args.base + path, data=data, headers=headers)
        try:
            response = opener.open(request, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            return response.status, json.loads(response.read())

    def queue():
        code, state = api("/api/state")
        if code != 200:
            raise RuntimeError("Authenticated state unavailable; credentials were not printed")
        safe = {"queueVersion": state["queueVersion"], "items": state["items"], "currentIndex": state["currentIndex"], "intent": state["intent"]}
        return safe

    def check(name, condition, **evidence):
        record = {"check": name, "status": "PASS" if condition else "FAIL", **evidence}
        report["checks"].append(record)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        print(json.dumps(record, ensure_ascii=False), flush=True)
        if not condition:
            raise RuntimeError("Failed check: " + name)

    def unchanged(before, name):
        after = queue()
        check(name, after == before, queueVersion=after["queueVersion"],
              queueDigest=hashlib.sha256(json.dumps(after, sort_keys=True).encode()).hexdigest())

    initial = queue()
    invalid = [
        "http://127.0.0.1/playlist?list=PL1234567890abcdefghij",
        "https://youtube.com.evil.invalid/playlist?list=PL1234567890abcdefghij",
        "file:///etc/passwd?list=PL1234567890abcdefghij",
        "https://www.youtube.com/playlist?list=RDjNQXAC9IVRw",
        "https://www.youtube.com/playlist?list=WL",
        "https://www.youtube.com/playlist?list=PL1234567890abcdefghij&list=PLdifferent123456",
    ]
    codes = [api("/api/playlists/preview", {"url": url})[0] for url in invalid]
    check("invalid_links_ssrf_mix_rejected", all(code == 400 for code in codes), httpStatuses=codes)
    unchanged(initial, "invalid_links_do_not_change_queue")

    code, preview = api("/api/playlists/preview", {"url": args.large_playlist})
    check("large_public_preview_started", code == 202 and bool(preview.get("previewId")), httpStatus=code)
    preview_id = preview["previewId"]
    deadline = time.monotonic() + 130
    result = None
    while time.monotonic() < deadline:
        code, result = api("/api/playlists/preview/" + preview_id)
        if code != 200 or result.get("status") != "LOADING":
            break
        time.sleep(0.5)
    check("real_public_playlist_limit", code == 200 and result.get("status") == "LIMIT_EXCEEDED",
          httpStatus=code, previewStatus=result.get("status"), reportedCount=result.get("reportedCount"),
          receivedCount=len(result.get("entries", [])), source=args.large_playlist)
    unchanged(initial, "large_preview_does_not_change_queue")
    for route, extra in [("/api/playlists/apply", {}), ("/api/collections/save", {"name": "Rejected limit acceptance snapshot"})]:
        before = queue()
        code, result = api(route, {"previewId": preview_id, "indices": [1], "queueVersion": before["queueVersion"], **extra})
        check("limit_rejects_" + route.rsplit("/", 1)[-1], code == 409 and result.get("error") == "PREVIEW_NOT_APPLICABLE",
              httpStatus=code, error=result.get("error"))
        unchanged(before, "rejected_" + route.rsplit("/", 1)[-1] + "_does_not_change_queue")

    code, preview = api("/api/playlists/preview", {"url": CANCEL_PUBLIC_PLAYLIST})
    preview_id = preview.get("previewId")
    code, loading = api("/api/playlists/preview/" + preview_id)
    check("cancel_started_during_loading", code == 200 and loading.get("status") == "LOADING", previewStatus=loading.get("status"))
    code, cancelled = api("/api/playlists/cancel", {"previewId": preview_id})
    check("cancel_immediate", code == 200 and cancelled.get("status") == "CANCELLED", httpStatus=code, previewStatus=cancelled.get("status"))
    deadline = time.monotonic() + args.cancel_observation_seconds
    statuses = set()
    while time.monotonic() < deadline:
        time.sleep(1)
        code, result = api("/api/playlists/preview/" + preview_id)
        statuses.add(result.get("status"))
        if code != 200 or result.get("status") != "CANCELLED":
            break
    check("cancel_has_no_late_result", statuses == {"CANCELLED"}, observedStatuses=sorted(statuses), observationSeconds=args.cancel_observation_seconds)
    unchanged(initial, "entire_acceptance_does_not_change_queue")
    api("/api/playlists/cancel", {"previewId": preview_id})


if __name__ == "__main__":
    main()
