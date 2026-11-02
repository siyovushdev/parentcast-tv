import { execFileSync } from "node:child_process";
import fs from "node:fs";

const base = "http://192.168.68.121:8765";
const profile = "app/build/m1-browser-profile";
const port = fs.readFileSync(`${profile}/DevToolsActivePort`, "utf8").split("\n")[0];
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));

async function target() {
    const pages = await fetch(`http://127.0.0.1:${port}/json/list`).then(response => response.json());
    const page = pages.find(item => item.type === "page" && item.url.startsWith(base));
    if (!page) throw new Error("ParentCast tab not found");
    return page;
}

const socket = new WebSocket((await target()).webSocketDebuggerUrl);
await new Promise((resolve, reject) => {
    socket.addEventListener("open", resolve, {once: true});
    socket.addEventListener("error", reject, {once: true});
});
let sequence = 0;
const pending = new Map();
socket.addEventListener("message", event => {
    const message = JSON.parse(event.data);
    if (!message.id) return;
    const request = pending.get(message.id);
    if (!request) return;
    pending.delete(message.id);
    if (message.error) request.reject(new Error(message.error.message));
    else request.resolve(message.result);
});

function cdp(method, params = {}) {
    const id = ++sequence;
    return new Promise((resolve, reject) => {
        pending.set(id, {resolve, reject});
        socket.send(JSON.stringify({id, method, params}));
    });
}

async function evaluate(expression) {
    const response = await cdp("Runtime.evaluate", {
        expression,
        returnByValue: true,
        awaitPromise: true,
    });
    if (response.exceptionDetails) throw new Error("Browser evaluation failed");
    return response.result.value;
}

async function waitFor(check, message, timeoutMs = 90000) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
        if (await evaluate(check)) return;
        await delay(500);
    }
    throw new Error(message);
}

async function setValue(id, value) {
    await evaluate(`(() => {
        const element = document.getElementById(${JSON.stringify(id)});
        if (!element) throw new Error("Missing input");
        element.value = ${JSON.stringify(value)};
    })()`);
}

async function click(selector) {
    await evaluate(`(() => {
        const element = document.querySelector(${JSON.stringify(selector)});
        if (!element) throw new Error("Missing control");
        element.click();
    })()`);
}

async function snapshot() {
    return evaluate("state");
}

async function screenshot(path) {
    const capture = await cdp("Page.captureScreenshot", {format: "png"});
    fs.writeFileSync(path, Buffer.from(capture.data, "base64"));
}

function privateValue(command, input) {
    return execFileSync("python3", ["-c", command], {encoding: "utf8", input}).trim();
}

try {
    await waitFor("document.readyState === 'complete'", "Panel did not load", 30000);
    if (await evaluate("document.getElementById('controlSection').hidden")) {
        const pin = privateValue("from tools import m1_hardware_test as h; h.adb('shell', 'input', 'keyevent', 'KEYCODE_MENU'); print(h.create_pin_if_needed() or '')");
        if (!pin) throw new Error("PIN setup requires PARENTCAST_CURRENT_PIN for an existing PIN");
        const code = privateValue("import sys; from tools.m1_hardware_test import begin_pairing; print(begin_pairing(sys.stdin.read()))", pin);
        await setValue("deviceLabel", "Ubuntu Chrome M1");
        await setValue("pin", pin);
        await setValue("pairingCode", code);
        await click("#pairButton");
        await waitFor("!document.getElementById('controlSection').hidden && state !== null", "Browser pairing failed", 60000);
    }
    console.log(JSON.stringify({browserPairing: "PASS"}));

    if ((await snapshot()).items.length) {
        await click('[data-playback="STOP"]');
        await waitFor("state.status === 'STOPPED'", "STOP was not reflected in the panel");
    }
    const beforeAdd = (await snapshot()).items.length;
    await setValue("links", "https://youtu.be/aqz-KE-bpKQ\nhttps://youtu.be/eRsGyueVLvQ");
    await click("#addButton");
    await waitFor(`state.items.length === ${beforeAdd + 2}`, "Two links were not added");
    await waitFor("state.status === 'STOPPED'", "Adding links started playback");
    console.log(JSON.stringify({addWithoutAutoplay: "PASS"}));

    const beforeLoss = (await snapshot()).items.length;
    await cdp("Network.enable");
    await cdp("Network.emulateNetworkConditions", {
        offline: true,
        latency: 0,
        downloadThroughput: 0,
        uploadThroughput: 0,
    });
    await setValue("links", "https://youtu.be/jNQXAC9IVRw");
    await click("#addButton");
    await waitFor("!document.getElementById('retryCommandButton').hidden", "Retry control did not appear");
    await cdp("Network.emulateNetworkConditions", {
        offline: false,
        latency: 0,
        downloadThroughput: -1,
        uploadThroughput: -1,
    });
    await click("#retryCommandButton");
    await waitFor(`state.items.length === ${beforeLoss + 1} && document.getElementById('retryCommandButton').hidden`, "Saved command retry failed");
    console.log(JSON.stringify({lostResponseRetry: "PASS", addedExactlyOnce: true}));

    const pendingBefore = (await snapshot()).items.slice((await snapshot()).currentIndex + 1);
    if (pendingBefore.length < 2) throw new Error("Not enough pending items for queue checks");
    const first = pendingBefore[0].queueItemId;
    const second = pendingBefore[1].queueItemId;
    const currentSnapshot = await snapshot();
    const current = currentSnapshot.items[currentSnapshot.currentIndex].queueItemId;
    await click(`button[data-action="move-down"][data-id="${first}"]`);
    await waitFor(`state.items[state.currentIndex + 1].queueItemId === ${JSON.stringify(second)}`, "Move did not change pending order");
    await click(`button[data-action="delete"][data-id="${first}"]`);
    await waitFor(`!state.items.some(item => item.queueItemId === ${JSON.stringify(first)})`, "Delete did not remove pending item");
    await click("#clearPendingButton");
    await waitFor(`state.items[state.currentIndex].queueItemId === ${JSON.stringify(current)} && state.items.length === state.currentIndex + 1`, "Clear pending changed current playback");
    console.log(JSON.stringify({moveDeleteClearPending: "PASS"}));

    await setValue("playNowLink", "https://youtu.be/jNQXAC9IVRw");
    await click("#playNowButton");
    await waitFor("state.status === 'PLAYING'", "Browser play-now did not reach real PLAYING", 120000);
    await setValue("links", "https://youtu.be/aqz-KE-bpKQ");
    await click("#addButton");
    await waitFor("state.items.length > state.currentIndex + 1", "Next VOD was not added");
    const playing = await snapshot();
    console.log(JSON.stringify({playing: playing.items[playing.currentIndex].videoId, title: playing.currentTitle, diagnostics: playing.diagnostics}));
    await screenshot("app/build/m1-ubuntu-panel.png");

    await click('[data-playback="PAUSE_RESUME"]');
    await waitFor("state.status === 'PAUSED'", "Pause did not reach Media3");
    const pausedAt = (await snapshot()).positionMs;
    await delay(3000);
    const pausedPosition = (await snapshot()).positionMs;
    if (Math.abs(pausedPosition - pausedAt) > 1000) throw new Error("Position advanced while paused");
    await click('[data-playback="PAUSE_RESUME"]');
    await waitFor("state.status === 'PLAYING'", "Resume did not reach PLAYING");
    console.log(JSON.stringify({pauseResume: "PASS"}));

    await waitFor("state.items[state.currentIndex].videoId !== 'jNQXAC9IVRw'", "Natural end did not advance to next selected VOD", 60000);
    await waitFor("state.status === 'PLAYING'", "Next VOD did not reach PLAYING", 120000);
    const nextSnapshot = await snapshot();
    console.log(JSON.stringify({naturalEndAndNext: "PASS", current: nextSnapshot.items[nextSnapshot.currentIndex].videoId}));

    await setValue("playNowLink", "https://youtu.be/eRsGyueVLvQ");
    await click("#playNowButton");
    await click('[data-playback="STOP"]');
    await waitFor("state.status === 'STOPPED'", "STOP during extraction was not applied");
    await delay(25000);
    if ((await snapshot()).status !== "STOPPED") throw new Error("Late extractor response restarted playback");
    console.log(JSON.stringify({stopDuringExtraction: "PASS", lateStart: false}));

    await click('[data-playback="PLAY"]');
    await waitFor("state.status === 'PLAYING'", "Playback for browser-close check did not start", 120000);
    await cdp("Emulation.setDeviceMetricsOverride", {
        width: 390,
        height: 844,
        deviceScaleFactor: 1,
        mobile: true,
    });
    await screenshot("app/build/m1-panel-mobile-layout.png");
    await cdp("Emulation.clearDeviceMetricsOverride");
    const leftPlaying = await snapshot();
    console.log(JSON.stringify({leftPlayingForCloseCheck: leftPlaying.items[leftPlaying.currentIndex].videoId}));
} finally {
    socket.close();
}
