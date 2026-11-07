// Deterministic browser-helper / Kotlin-route compatibility check. No device or network.
import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";
import {webcrypto} from "node:crypto";

const source = fs.readFileSync("app/src/main/assets/web/app.js", "utf8");
const security = fs.readFileSync("app/src/main/java/dev/siyovush/parentcast/tv/web/RequestSecurity.kt", "utf8");
const routes = fs.readFileSync("app/src/main/java/dev/siyovush/parentcast/tv/web/PlaylistApi.kt", "utf8");
const expectedClient = security.match(/headers\["x-parentcast-client"\]\s*!=\s*"([^"]+)"/)?.[1];
assert(expectedClient, "The accepted client protocol value must be identified from RequestSecurity");

const elements = new Map();
function element() {
    return {hidden: false, disabled: false, value: "", textContent: "", className: "", dataset: {},
        append() {}, replaceChildren() {}, addEventListener() {}};
}
const stored = new Map();
const requests = [];
let loseApplyResponse = false;
let nextFailure = null;
let heldPath = null;
let releaseHeld = null;
let additionResult = null;
const playerState = {queueVersion: 7, items: [], currentIndex: -1, positionMs: 0, intent: "STOP", status: "STOPPED", updatedAtMs: 0};
const context = vm.createContext({
    document: {
        getElementById(id) { if (!elements.has(id)) elements.set(id, element()); return elements.get(id); },
        createElement: element,
        querySelectorAll() { return []; },
    },
    localStorage: {
        getItem: key => stored.get(key) ?? null,
        setItem: (key, value) => stored.set(key, value),
        removeItem: key => stored.delete(key),
    },
    crypto: webcrypto,
    setInterval() {},
    setTimeout() {},
    clearTimeout() {},
    fetch: async (path, options) => {
        const header = Object.entries(options.headers).find(([key]) => key.toLowerCase() === "x-parentcast-client")?.[1];
        // Model the actual server protocol restriction; accepting all mock requests hid the m2/m1 regression.
        assert.equal(header, expectedClient, `Frontend protocol header incompatible with RequestSecurity for ${path}`);
        const body = options.body === undefined ? undefined : JSON.parse(options.body);
        requests.push({path, method: options.method, body});
        if (heldPath === path && options.method === "POST") {
            heldPath = null;
            await new Promise(resolve => { releaseHeld = resolve; });
        }
        if (nextFailure && nextFailure.path === path) {
            const failure = nextFailure;
            nextFailure = null;
            return {ok: false, status: failure.status, text: async () => JSON.stringify({error: failure.code})};
        }
        if (path === "/api/playlists/apply" && loseApplyResponse) {
            loseApplyResponse = false;
            throw new Error("Synthetic lost response");
        }
        const result = path === "/api/state" ? playerState : additionResult || {ok: true};
        return {ok: true, status: 200, text: async () => JSON.stringify(result)};
    },
});
vm.runInContext(source, context, {filename: "app.js"});
await vm.runInContext("api('/api/pair', 'POST', {label:'Fixture', pin:'123456'})", context);
vm.runInContext("device = {deviceId:'fixture', token:'synthetic-test-only'}; state = {queueVersion:7,items:[],currentIndex:-1,positionMs:0,intent:'STOP',status:'STOPPED',updatedAtMs:0};", context);

const postBodies = {
    "/api/playlists/preview": {url: "https://www.youtube.com/playlist?list=PLfixture", idempotencyKey: "preview-fixture"},
    "/api/playlists/cancel": {previewId: "preview-fixture", idempotencyKey: "cancel-fixture"},
    "/api/playlists/apply": {previewId: "preview-fixture", indices: [1, 3], queueVersion: 7, idempotencyKey: "apply-fixture"},
    "/api/collections/save": {previewId: "preview-fixture", indices: [1, 3], name: "Fixture", idempotencyKey: "save-fixture"},
    "/api/collections/apply": {collectionId: "fixed-fixture", queueVersion: 7, idempotencyKey: "replay-fixture"},
    "/api/collections/delete": {collectionId: "fixed-fixture", idempotencyKey: "delete-fixture"},
};
for (const [path, body] of Object.entries(postBodies)) {
    assert(routes.includes(`"${path}"`), `No Kotlin route for frontend endpoint ${path}`);
    await vm.runInContext(`api(${JSON.stringify(path)}, 'POST', ${JSON.stringify(body)})`, context);
    assert.deepEqual(requests.at(-1).body, body, `JSON changed in transit for ${path}`);
}
for (const path of ["/api/collections", "/api/playlists/preview/preview-fixture"]) {
    await vm.runInContext(`api(${JSON.stringify(path)}, 'GET')`, context);
    assert.equal(requests.at(-1).body, undefined);
}

loseApplyResponse = true;
await assert.rejects(vm.runInContext(`perform('fixture-command', '/api/playlists/apply', ${JSON.stringify(postBodies["/api/playlists/apply"])})`, context));
vm.runInContext("state.queueVersion = 999", context);
await vm.runInContext("perform('fixture-command', '', {})", context);
const repeated = requests.filter(request => request.path === "/api/playlists/apply").slice(-2);
assert.equal(repeated.length, 2);
assert.deepEqual(repeated[0].body, repeated[1].body, "A lost-response retry must use the original command");
assert.equal(repeated[1].body.queueVersion, 7, "A retry must not replace the original queueVersion with fresh state");
assert.equal(vm.runInContext("Object.keys(pendingCommands).length", context), 0);

for (const [code, expected] of [
    ["QUEUE_VERSION_CONFLICT", "Очередь изменилась"],
    ["COLLECTION_STORAGE_LIMIT", "лимит сохранённых подборок"],
    ["PREVIEW_NOT_READY", "Список ещё загружается"],
    ["PREVIEW_NOT_APPLICABLE", "предпросмотр нельзя применить"],
    ["IDEMPOTENCY_KEY_REUSED", "Ключ команды уже использован"],
]) {
    nextFailure = {path: "/api/playlists/apply", status: 409, code};
    await assert.rejects(vm.runInContext(`perform('failure-${code}', '/api/playlists/apply', ${JSON.stringify(postBodies["/api/playlists/apply"])})`, context));
    const message = elements.get("commandMessage").textContent;
    assert(message.includes(expected), `Incorrect message for ${code}`);
    assert.equal(message.includes("Очередь изменилась"), code === "QUEUE_VERSION_CONFLICT", `A non-queue conflict must not be described as a queue change: ${code}`);
}

vm.runInContext("preview = null; byId('playlistUrl').value = 'https://www.youtube.com/playlist?list=PLfixture'", context);
for (const [status, code, expected] of [
    [400, "BAD_REQUEST", "публичный статический плейлист YouTube/VK"],
    [429, "PREVIEW_BUSY", "Приставка уже получает несколько списков"],
]) {
    nextFailure = {path: "/api/playlists/preview", status, code};
    await assert.rejects(vm.runInContext("startPreview()", context));
    const message = elements.get("playlistMessage").textContent;
    assert(message.includes(expected), `Incorrect preview message for ${status}/${code}`);
    assert.equal(message.includes("публичный статический плейлист YouTube/VK"), status === 400, "Resource exhaustion must not be described as an invalid link");
}

vm.runInContext("preview = {previewId:'fixture',status:'COMPLETE',entries:[]}; selectedIndices = new Set([1]); byId('collectionName').value = 'Fixture'", context);
nextFailure = {path: "/api/playlists/apply", status: 409, code: "PREVIEW_NOT_READY"};
await assert.rejects(vm.runInContext("applyPreview()", context));
assert(elements.get("playlistMessage").textContent.includes("Список ещё загружается"));
assert(!elements.get("playlistMessage").textContent.includes("Очередь изменилась"));
nextFailure = {path: "/api/collections/save", status: 409, code: "COLLECTION_STORAGE_LIMIT"};
await assert.rejects(vm.runInContext("saveCollection()", context));
assert(elements.get("collectionsMessage").textContent.includes("лимит сохранённых подборок"));
nextFailure = {path: "/api/collections/apply", status: 409, code: "IDEMPOTENCY_KEY_REUSED"};
await assert.rejects(vm.runInContext("collectionAction('apply', 'fixture')", context));
assert(elements.get("collectionsMessage").textContent.includes("Ключ команды уже использован"));
assert(!elements.get("collectionsMessage").textContent.includes("Очередь изменилась"));
console.log("PASS: frontend protocol and routes; JSON; original retry/queueVersion; accurate 409/429 messages (synthetic, no network)");

// Duplicate clicks are blocked immediately, and a successful request reports server counts.
vm.runInContext("preview={previewId:'dedup',status:'COMPLETE',entries:[{index:1,videoId:'video000001',selectable:true}]}; selectedIndices=new Set([1]); state.items=[]", context);
heldPath = "/api/playlists/apply";
additionResult = {addition:{added:1,skipped:0}};
let start = requests.filter(r => r.path === heldPath).length;
const applying = vm.runInContext("applyPreview()", context);
const duplicateClick = vm.runInContext("applyPreview()", context);
assert.equal(elements.get("playlistApplyButton").disabled, true);
assert.equal(requests.filter(r=>r.path==="/api/playlists/apply").length, start+1);
releaseHeld(); await Promise.all([applying,duplicateClick]);
assert(elements.get("playlistMessage").textContent.includes("Добавлено новых: 1"));
vm.runInContext("state.items=[{videoId:'video000001'}]; updateSelectionControls()", context);
assert.equal(elements.get("playlistApplyButton").disabled, true);
assert.equal(elements.get("playlistApplyButton").textContent,"Уже в очереди");
assert.equal(vm.runInContext("queueAdditionMessage({addition:{added:0,skipped:38}})",context),"Все выбранные ролики уже есть в очереди. Повторы не добавлены.");
heldPath = "/api/queue/add";
additionResult = {addition:{added:0,skipped:1}};
vm.runInContext("byId('links').value='https://youtu.be/video000001'",context);
start = requests.filter(r=>r.path===heldPath).length;
const adding = vm.runInContext("addLinks()",context);
const addingAgain = vm.runInContext("addLinks()",context);
assert.equal(elements.get("addButton").disabled,true);
assert.equal(requests.filter(r=>r.path==="/api/queue/add").length,start+1);
releaseHeld(); await Promise.all([adding,addingAgain]);
assert(elements.get("commandMessage").textContent.includes("уже есть в очереди"));
console.log("PASS: repeated playlist/link clicks, busy controls, queued selection and accurate added/skipped messages (synthetic)");

vm.runInContext("preview={previewId:'vk',provider:'VK',status:'COMPLETE',reportedCount:61,title:'VK',entries:[{index:1,videoId:'vk:-227392256_456239129',title:'<script>title</script>',durationSeconds:116,availability:'UNKNOWN',selectable:true}]}; selectedIndices=new Set([1]); state.items=[]; renderPreview()",context);
assert(elements.get('playlistCount').textContent.includes('заявлено VK: 61'));
assert(elements.get('playlistSelectedCount').textContent.includes('новых: 1'));
vm.runInContext("state.items=[{videoId:'vk:-227392256_456239129'}];updateSelectionControls()",context);
assert.equal(elements.get('playlistApplyButton').textContent,'Уже в очереди');
assert.equal(elements.get('playlistApplyButton').disabled,true);
console.log('PASS: VK preview count, provider namespace and dedup controls (synthetic)');
