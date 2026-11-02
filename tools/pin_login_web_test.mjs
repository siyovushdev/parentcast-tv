// Browser auth contract against the actual panel script. Synthetic, no H728/network.
import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";
import {webcrypto} from "node:crypto";

const source = fs.readFileSync("app/src/main/assets/web/app.js", "utf8");
const html = fs.readFileSync("app/src/main/assets/web/index.html", "utf8");
assert(!/pairingCode|одноразов/i.test(html));
const deviceKey = "parentcast.device.v1";

function browser({stored = new Map(), failure = null, hold = false} = {}) {
    const elements = new Map(), requests = [];
    const element = () => ({value: "", textContent: "", hidden: false, disabled: false, dataset: {},
        append() {}, replaceChildren() {}, addEventListener() {}});
    let release;
    const pending = hold ? new Promise(resolve => { release = resolve; }) : Promise.resolve();
    const context = vm.createContext({
        document: {
            getElementById(id) {
                assert.notEqual(id, "pairingCode", "Panel must not require a removed input");
                if (!elements.has(id)) elements.set(id, element());
                return elements.get(id);
            },
            createElement: element, querySelectorAll: () => [],
        },
        localStorage: {getItem: k => stored.get(k) ?? null, setItem: (k, v) => stored.set(k, v), removeItem: k => stored.delete(k)},
        crypto: webcrypto, setInterval() {}, setTimeout() {}, clearTimeout() {},
        fetch: async (path, options) => {
            requests.push({path, body: options.body && JSON.parse(options.body), headers: options.headers});
            if (path === "/api/pair") await pending;
            if (failure === "network" && path === "/api/pair") throw new Error("Synthetic disconnect");
            const status = failure === "revoked" || (path === "/api/pair" && failure === 401) ? 401
                : path === "/api/pair" && failure === 503 ? 503 : 200;
            const result = path === "/api/pair" ? {deviceId: "browser-id", token: "browser-token"}
                : path === "/api/state" ? {items: [], currentIndex: -1, positionMs: 0, queueVersion: 0, intent: "STOP", status: "STOPPED", updatedAtMs: 0}
                : path === "/api/devices" ? {devices: []} : {collections: []};
            return {ok: status === 200, status, text: async () => JSON.stringify(status === 200 ? result : {error: "DENIED"})};
        },
    });
    vm.runInContext(source, context);
    const run = code => vm.runInContext(code, context);
    const enter = () => run("byId('pin').value = '739251'; byId('deviceLabel').value = 'Ubuntu'; pair()");
    return {elements, requests, stored, run, enter, release};
}

const first = browser();
await first.enter();
assert.deepEqual(first.requests.find(r => r.path === "/api/pair").body, {label: "Ubuntu", pin: "739251"});
assert.deepEqual(JSON.parse(first.stored.get(deviceKey)), {deviceId: "browser-id", token: "browser-token"});
assert(!JSON.stringify([...first.stored]).includes("739251"), "PIN must not be stored");
assert.equal(first.elements.get("pin").value, "");
assert.equal(first.elements.get("controlSection").hidden, false);

const reopened = browser({stored: new Map(first.stored)});
await new Promise(setImmediate);
assert(!reopened.requests.some(r => r.path === "/api/pair"), "Reload must reuse the saved token");
assert.equal(reopened.requests.find(r => r.path === "/api/state").headers.Authorization, "Bearer browser-token");
assert.equal(reopened.elements.get("pairingSection").hidden, true);

const slow = browser({hold: true});
const signingIn = slow.enter();
assert.equal(slow.elements.get("pin").value, "", "PIN is cleared before awaiting the response");
assert.equal(slow.elements.get("pairButton").disabled, true);
await slow.run("pair()");
assert.equal(slow.requests.filter(r => r.path === "/api/pair").length, 1);
slow.release();
await signingIn;
assert.equal(slow.elements.get("pairButton").disabled, false);

for (const failure of [401, 503, "network"]) {
    const rejected = browser({failure});
    await rejected.enter();
    assert(!rejected.stored.has(deviceKey));
    assert.equal(rejected.elements.get("pin").value, "");
    assert.equal(rejected.elements.get("pairButton").disabled, false);
    assert(!rejected.elements.get("pairingMessage").textContent.includes("код"));
    if (failure === 401) assert(rejected.elements.get("pairingMessage").textContent.includes("10 минут"));
}

const revoked = browser({stored: new Map(first.stored), failure: "revoked"});
await new Promise(setImmediate);
assert(!revoked.stored.has(deviceKey));
assert.equal(revoked.elements.get("pairingSection").hidden, false);
console.log("PASS: PIN-only payload; token persistence/reload; no PIN storage; duplicate clicks; failure/revocation login UI (synthetic)");
