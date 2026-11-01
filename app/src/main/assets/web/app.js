"use strict";

const DEVICE_KEY = "parentcast.device.v1";
const COMMANDS_KEY = "parentcast.commands.v1";
let device = readJson(DEVICE_KEY, null);
let pendingCommands = readJson(COMMANDS_KEY, {});
let state = null;
let refreshing = false;

const byId = id => document.getElementById(id);
const statusNames = {
    IDLE: "Очередь пуста",
    WAITING_FOR_SESSION: "Ожидание включения сессии",
    LOADING: "Получение потока",
    BUFFERING: "Буферизация",
    PLAYING: "Воспроизведение",
    PAUSED: "Пауза",
    STOPPED: "Остановлено",
    ENDED: "Очередь завершена",
    ERROR: "Ошибка воспроизведения",
};

function readJson(key, fallback) {
    try {
        const value = localStorage.getItem(key);
        return value ? JSON.parse(value) : fallback;
    } catch (_) {
        return fallback;
    }
}

function writeJson(key, value) {
    localStorage.setItem(key, JSON.stringify(value));
}

function commandKey() {
    if (crypto.randomUUID) return crypto.randomUUID();
    const bytes = crypto.getRandomValues(new Uint32Array(4));
    return Array.from(bytes, value => value.toString(16)).join("-");
}

function showPairing(show) {
    byId("pairingSection").hidden = !show;
    byId("controlSection").hidden = show;
}

async function api(path, method, body) {
    const headers = {"X-ParentCast-Client": "m1"};
    if (device) headers.Authorization = `Bearer ${device.token}`;
    const options = {method, headers, credentials: "omit", cache: "no-store"};
    if (body !== undefined) {
        headers["Content-Type"] = "application/json";
        options.body = JSON.stringify(body);
    }
    const response = await fetch(path, options);
    const text = await response.text();
    let data = {};
    try { data = JSON.parse(text); } catch (_) { data = {error: "INVALID_RESPONSE"}; }
    if (!response.ok) {
        const error = new Error(data.error || `HTTP ${response.status}`);
        error.status = response.status;
        error.data = data;
        throw error;
    }
    return data;
}

async function pair() {
    const label = byId("deviceLabel").value.trim() || "Browser";
    const pin = byId("pin").value;
    const code = byId("pairingCode").value.trim();
    byId("pairingMessage").textContent = "Сопряжение…";
    try {
        const result = await api("/api/pair", "POST", {label, pin, code});
        device = {deviceId: result.deviceId, token: result.token};
        writeJson(DEVICE_KEY, device);
        byId("pin").value = "";
        byId("pairingCode").value = "";
        byId("pairingMessage").textContent = "Устройство сопряжено";
        showPairing(false);
        await refreshState();
        await refreshDevices();
    } catch (error) {
        byId("pairingMessage").textContent = "Не удалось сопрячь. Проверьте PIN и код.";
    }
}

async function refreshState() {
    if (!device || refreshing) return;
    refreshing = true;
    try {
        state = await api("/api/state", "GET");
        renderState();
        setConnection(true);
    } catch (error) {
        if (error.status === 401) {
            device = null;
            localStorage.removeItem(DEVICE_KEY);
            state = null;
            showPairing(true);
        }
        setConnection(false);
    } finally {
        refreshing = false;
    }
}

function setConnection(online) {
    const badge = byId("connectionStatus");
    badge.textContent = online ? "Приставка на связи" : "Приставка недоступна";
    badge.className = `badge ${online ? "online" : "offline"}`;
}

function renderState() {
    if (!state) return;
    byId("playerStatus").textContent = statusNames[state.status] || state.status;
    byId("currentTitle").textContent = state.currentTitle || "Ролик не выбран";
    const current = state.currentIndex >= 0 ? state.currentIndex + 1 : 0;
    byId("queueSummary").textContent = `Элемент ${current}/${state.items.length}; позиция ${Math.floor(state.positionMs / 1000)} с; intent ${state.intent}; версия ${state.queueVersion}`;
    byId("updatedAt").textContent = `Обновлено плеером: ${new Date(state.updatedAtMs).toLocaleString()}`;
    byId("playbackError").textContent = state.error ? `${state.error.kind}: ${state.error.diagnostic || ""}` : "";
    byId("diagnostics").textContent = state.diagnostics || "";
    renderQueue();
}

function renderQueue() {
    const container = byId("queue");
    container.replaceChildren();
    if (!state.items.length) {
        const empty = document.createElement("p");
        empty.className = "muted";
        empty.textContent = "Очередь пуста";
        container.append(empty);
        return;
    }
    state.items.forEach((item, index) => {
        const row = document.createElement("div");
        row.className = `queue-item${index === state.currentIndex ? " current" : ""}`;
        const title = document.createElement("div");
        title.className = "video-id";
        title.textContent = `${index + 1}. ${item.videoId}${index === state.currentIndex ? " — текущий" : ""}`;
        row.append(title);
        if (index > state.currentIndex) {
            const controls = document.createElement("div");
            controls.className = "controls";
            controls.append(
                queueButton("↑", "move-up", item.queueItemId),
                queueButton("↓", "move-down", item.queueItemId),
                queueButton("Удалить", "delete", item.queueItemId),
            );
            row.append(controls);
        }
        container.append(row);
    });
}

function queueButton(text, action, id) {
    const button = document.createElement("button");
    button.className = "secondary";
    button.textContent = text;
    button.dataset.action = action;
    button.dataset.id = id;
    return button;
}

async function perform(key, path, payload) {
    const retryButton = byId("retryCommandButton");
    retryButton.hidden = true;
    let pending = pendingCommands[key];
    if (!pending) {
        pending = {path, payload};
        pendingCommands[key] = pending;
        writeJson(COMMANDS_KEY, pendingCommands);
    }
    try {
        const result = await api(pending.path, "POST", pending.payload);
        delete pendingCommands[key];
        writeJson(COMMANDS_KEY, pendingCommands);
        byId("commandMessage").textContent = "Команда принята; состояние уточняется у плеера";
        await refreshState();
        return result;
    } catch (error) {
        if (error.status === 409) {
            delete pendingCommands[key];
            writeJson(COMMANDS_KEY, pendingCommands);
            byId("commandMessage").textContent = "Очередь изменилась другим клиентом. Обновите и повторите действие.";
            await refreshState();
        } else if (error.status === 401) {
            delete pendingCommands[key];
            writeJson(COMMANDS_KEY, pendingCommands);
            device = null;
            localStorage.removeItem(DEVICE_KEY);
            state = null;
            showPairing(true);
            setConnection(false);
        } else if (error.status >= 400 && error.status < 500) {
            delete pendingCommands[key];
            writeJson(COMMANDS_KEY, pendingCommands);
            byId("commandMessage").textContent = `Команда отклонена: ${error.message}`;
        } else {
            retryButton.dataset.commandKey = key;
            retryButton.hidden = false;
            byId("commandMessage").textContent = "Ответ не получен. Повторите сохранённую команду — она не будет выполнена дважды.";
            setConnection(false);
        }
        throw error;
    }
}

async function addLinks() {
    const links = byId("links").value.split(/\r?\n/).map(value => value.trim()).filter(Boolean);
    if (!links.length) return;
    await perform(`add-${commandKey()}`, "/api/queue/add", {links, idempotencyKey: commandKey()});
    byId("links").value = "";
}

async function playNow() {
    const videoId = byId("playNowLink").value.trim();
    if (!videoId) return;
    await perform(`play-now-${commandKey()}`, "/api/queue/play-now", {videoId, idempotencyKey: commandKey()});
    byId("playNowLink").value = "";
}

async function playback(action) {
    await perform(`playback-${action}-${commandKey()}`, "/api/playback", {action, idempotencyKey: commandKey()});
}

async function queueAction(action, id) {
    if (!state) return;
    if (action === "delete") {
        await perform(`delete-${id}-${commandKey()}`, "/api/queue/delete", {
            queueItemId: id,
            queueVersion: state.queueVersion,
            idempotencyKey: commandKey(),
        });
    } else {
        await perform(`move-${action}-${id}-${commandKey()}`, "/api/queue/move", {
            queueItemId: id,
            direction: action === "move-up" ? "UP" : "DOWN",
            queueVersion: state.queueVersion,
            idempotencyKey: commandKey(),
        });
    }
}

async function clearPending() {
    if (!state) return;
    await perform(`clear-${commandKey()}`, "/api/queue/clear", {
        queueVersion: state.queueVersion,
        idempotencyKey: commandKey(),
    });
}

async function refreshDevices() {
    if (!device) return;
    try {
        const result = await api("/api/devices", "GET");
        const container = byId("devices");
        container.replaceChildren();
        result.devices.forEach(record => {
            const row = document.createElement("div");
            row.className = "device";
            const label = document.createElement("span");
            label.textContent = `${record.label}${record.revokedAtMs ? " — отозвано" : ""}`;
            row.append(label);
            if (!record.revokedAtMs) {
                const button = document.createElement("button");
                button.className = "secondary";
                button.textContent = "Отозвать";
                button.addEventListener("click", () => {
                    perform(`revoke-${record.deviceId}-${commandKey()}`, "/api/devices/revoke", {
                        deviceId: record.deviceId,
                        idempotencyKey: commandKey(),
                    }).then(refreshDevices);
                });
                row.append(button);
            }
            container.append(row);
        });
    } catch (_) { /* состояние устройств не критично для плеера */ }
}

function run(action) {
    action().catch(() => {});
}

byId("retryCommandButton").addEventListener("click", () => run(() => {
    const key = byId("retryCommandButton").dataset.commandKey;
    return key ? perform(key, "", {}) : Promise.resolve();
}));
byId("pairButton").addEventListener("click", () => run(pair));
byId("addButton").addEventListener("click", () => run(addLinks));
byId("playNowButton").addEventListener("click", () => run(playNow));
byId("clearPendingButton").addEventListener("click", () => run(clearPending));
document.querySelectorAll("[data-playback]").forEach(button => {
    button.addEventListener("click", () => run(() => playback(button.dataset.playback)));
});
byId("queue").addEventListener("click", event => {
    const button = event.target.closest("button[data-action]");
    if (button) run(() => queueAction(button.dataset.action, button.dataset.id));
});

showPairing(!device);
if (device) {
    showPairing(false);
    refreshState();
    refreshDevices();
}
setInterval(() => {
    if (device && !document.hidden) refreshState();
}, 2000);
