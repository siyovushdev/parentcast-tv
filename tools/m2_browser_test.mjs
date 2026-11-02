// Real Ubuntu Chrome + H728 acceptance. The tab must already be paired.
// CHROME_DEBUG_PORT=<port> PLAYLIST_URL=<public playlist URL> node tools/m2_browser_test.mjs
// Optional: PARENTCAST_URL, ALLOW_PARTIAL=1, REPLAY_SAVED=1, CANCEL_PREVIEW=1.
import fs from "node:fs";
import path from "node:path";

const base = process.env.PARENTCAST_URL || "http://192.168.68.121:8765";
const playlistUrl = process.env.PLAYLIST_URL;
if (!playlistUrl) throw new Error("PLAYLIST_URL is required");
const profile = "app/build/m2-browser-profile";
const port = process.env.CHROME_DEBUG_PORT || fs.readFileSync(`${profile}/DevToolsActivePort`, "utf8").split("\n")[0];
const output = "app/build/m2-browser";
fs.mkdirSync(output, {recursive: true});
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const pages = await fetch(`http://127.0.0.1:${port}/json/list`).then(response => response.json());
const page = pages.find(item => item.type === "page" && item.url.startsWith(base));
if (!page) throw new Error("An existing ParentCast tab was not found");
const socket = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((resolve, reject) => {
    socket.addEventListener("open", resolve, {once: true});
    socket.addEventListener("error", reject, {once: true});
});
let sequence = 0;
let appliedPayload = null;
const mutationProtocolValues = [];
const pending = new Map();
socket.addEventListener("message", event => {
    const message = JSON.parse(event.data);
    if (message.method === "Network.requestWillBeSent") {
        const request = message.params.request;
        if (request.url.startsWith(`${base}/api/`) && request.method === "POST") {
            const clientHeader = Object.entries(request.headers).find(([name]) => name.toLowerCase() === "x-parentcast-client");
            mutationProtocolValues.push(clientHeader ? clientHeader[1] : null);
        }
        // Store the command body only; never collect or print Authorization headers.
        if (request.url === `${base}/api/playlists/apply` && request.postData) {
            appliedPayload = JSON.parse(request.postData);
        }
    }
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
    const result = await cdp("Runtime.evaluate", {expression, awaitPromise: true, returnByValue: true});
    if (result.exceptionDetails) throw new Error("Browser evaluation failed");
    return result.result.value;
}

async function waitFor(check, message, timeout = 180000) {
    const deadline = Date.now() + timeout;
    while (Date.now() < deadline) {
        if (await evaluate(check)) return;
        await delay(300);
    }
    throw new Error(message);
}

async function click(selector) {
    await evaluate(`(() => {
        const element = document.querySelector(${JSON.stringify(selector)});
        if (!element || element.disabled || element.hidden) throw new Error("Control unavailable");
        element.click();
    })()`);
}

async function fill(id, value) {
    await evaluate(`(() => {
        const element = document.getElementById(${JSON.stringify(id)});
        element.value = ${JSON.stringify(value)};
        element.dispatchEvent(new Event("input", {bubbles: true}));
    })()`);
}

async function screenshot(name) {
    const result = await cdp("Page.captureScreenshot", {format: "png", captureBeyondViewport: true});
    fs.writeFileSync(path.join(output, name), Buffer.from(result.data, "base64"));
}

function assert(condition, message) {
    if (!condition) throw new Error(message);
}

const queueIdentity = snapshot => snapshot.items.map(item => `${item.queueItemId}:${item.videoId}`).join("|");
try {
    await waitFor("document.readyState === 'complete' && typeof state !== 'undefined'", "Panel did not load", 30000);
    assert(await evaluate("!document.getElementById('controlSection').hidden"), "The browser must already be paired; no PIN is read by this tool");
    await waitFor("state !== null", "No player state", 30000);
    const before = await evaluate("state");
    await cdp("Network.enable");
    await fill("playlistUrl", playlistUrl);
    await click("#playlistPreviewButton");
    await waitFor("preview && preview.status !== 'LOADING'", "Playlist listing did not finish");
    const listed = await evaluate("preview");
    assert(mutationProtocolValues.length > 0 && mutationProtocolValues.every(value => value === "m1"), "Browser mutation protocol header is incompatible with existing M1 server security");
    const selectable = listed.entries.filter(entry => entry.selectable);
    console.log(JSON.stringify({stage: "realPlaylistPreview", status: listed.status, received: listed.entries.length, reportedCount: listed.reportedCount, selectable: selectable.length, reason: listed.reason || null}));
    assert(listed.status === "COMPLETE" || (process.env.ALLOW_PARTIAL === "1" && listed.status === "PARTIAL"), "The primary preview did not complete; PARTIAL is never accepted as a complete import");
    assert(listed.entries.length <= 500, "Import exceeded 500 entries");
    assert(selectable.length >= 2, "At least two selectable VOD entries are required");
    const chosen = selectable.slice(0, 2);
    const videoIds = chosen.map(entry => entry.videoId);
    assert(queueIdentity(await evaluate("state")) === queueIdentity(before), "Preview changed the queue before confirmation");
    for (const entry of chosen) await click(`#playlistEntries input[data-index="${entry.index}"]`);
    assert(queueIdentity(await evaluate("state")) === queueIdentity(before), "Selection changed the queue before confirmation");
    await evaluate("document.getElementById('playlistHeading').scrollIntoView()");
    await screenshot("m2-ubuntu-preview.png");
    console.log(JSON.stringify({stage: "previewAndSelection", result: "PASS", indices: chosen.map(entry => entry.index), videoIds}));

    const name = `M2 acceptance ${new Date().toISOString()}`;
    await fill("collectionName", name);
    await click("#collectionSaveButton");
    await waitFor(`collections.some(item => item.name === ${JSON.stringify(name)})`, "The fixed collection was not saved");
    const saved = await evaluate(`collections.find(item => item.name === ${JSON.stringify(name)})`);
    assert(saved.videoIds.join("|") === videoIds.join("|"), "Saved collection order differs from the selection");
    assert(queueIdentity(await evaluate("state")) === queueIdentity(before), "Saving changed the queue");
    console.log(JSON.stringify({stage: "savedFixedCollection", result: "PASS", collectionId: saved.id, videoIds: saved.videoIds}));

    await click("#playlistApplyButton");
    await waitFor(`state.items.length === ${before.items.length + chosen.length}`, "Selected entries were not added atomically");
    const added = await evaluate("state");
    assert(added.items.slice(-chosen.length).map(item => item.videoId).join("|") === videoIds.join("|"), "Imported order differs from the source order");
    assert(added.intent === before.intent, "Import changed playback intent");
    if (before.items.length) assert(added.items[added.currentIndex].queueItemId === before.items[before.currentIndex].queueItemId, "Import interrupted the current item");
    if (before.intent === "STOP") assert(added.status !== "PLAYING" && added.status !== "LOADING", "Import started playback");
    console.log(JSON.stringify({stage: "atomicApplyWithoutAutoplay", result: "PASS", appended: videoIds, queueVersion: added.queueVersion}));

    assert(appliedPayload, "Apply body was not observed for idempotency replay");
    await evaluate(`api('/api/playlists/apply', 'POST', ${JSON.stringify(appliedPayload)}).then(() => refreshState())`);
    await delay(500);
    const repeated = await evaluate("state");
    assert(queueIdentity(repeated) === queueIdentity(added), "A repeated command added duplicates");
    assert(repeated.queueVersion === added.queueVersion, "A repeated command changed queueVersion");
    console.log(JSON.stringify({stage: "repeatSameCommand", result: "PASS"}));

    if (process.env.REPLAY_SAVED === "1") {
        await click(`button[data-collection-action="apply"][data-collection-id="${saved.id}"]`);
        await waitFor(`state.items.length === ${added.items.length + saved.videoIds.length}`, "Saved collection replay did not append the fixed list");
        const replayed = await evaluate("state");
        assert(replayed.items.slice(-saved.videoIds.length).map(item => item.videoId).join("|") === saved.videoIds.join("|"), "Saved collection replay changed order");
        assert(replayed.intent === before.intent, "Replaying a saved collection changed playback intent");
        console.log(JSON.stringify({stage: "replaySavedCollection", result: "PASS", videoIds: saved.videoIds}));
    }
    await evaluate("document.getElementById('collectionsHeading').scrollIntoView()");
    await screenshot("m2-ubuntu-imported-saved.png");

    if (process.env.CANCEL_PREVIEW === "1") {
        const beforeCancel = await evaluate("state");
        const oldPreviewId = await evaluate("preview ? preview.previewId : null");
        await click("#playlistPreviewButton");
        await waitFor(`preview && preview.previewId !== ${JSON.stringify(oldPreviewId)} && !document.getElementById('playlistCancelButton').hidden`, "A new cancellable preview did not start", 30000);
        const wasLoading = await evaluate("preview.status === 'LOADING'");
        await click("#playlistCancelButton");
        await waitFor("preview === null || preview.status === 'CANCELLED'", "Preview cancellation was not reflected", 30000);
        await delay(2000);
        assert(queueIdentity(await evaluate("state")) === queueIdentity(beforeCancel), "Cancelling a preview changed the queue");
        console.log(JSON.stringify({stage: "cancelPreview", result: "PASS", cancelledWhileLoading: wasLoading}));
    }
    console.log(JSON.stringify({result: "PASS", source: "REAL_YOUTUBE_H728_UBUNTU_BROWSER", savedCollectionId: saved.id, savedName: saved.name, importedVideoIds: videoIds, screenshots: output}));
} finally {
    socket.close();
}
