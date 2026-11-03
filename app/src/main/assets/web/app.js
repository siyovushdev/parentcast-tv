"use strict";

const DEVICE_KEY = "parentcast.device.v1";
const COMMANDS_KEY = "parentcast.commands.v1";
let device = readJson(DEVICE_KEY, null);
let pendingCommands = readJson(COMMANDS_KEY, {});
let state = null;
let refreshing = false;
let signingIn = false;
let preview = null;
let previewPoll = null;
let previewRequesting = false;
let previewPollRunning = false;
let selectedIndices = new Set();
let collections = [];
const busyActions = new Set();

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
    if (show) clearPreview();
}

async function api(path, method, body) {
    const headers = {"X-ParentCast-Client": "m1"};
    if (device) headers.Authorization = `Bearer ${device.token}`;
    const options = {method, headers, credentials: "omit", cache: "no-store"};
    if (body !== undefined) {
        headers["Content-Type"] = "application/json; charset=utf-8";
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
        if (response.status === 401) forgetDevice();
        throw error;
    }
    return data;
}

async function pair() {
    if (signingIn) return;
    signingIn = true;
    byId("pairButton").disabled = true;
    const label = byId("deviceLabel").value.trim() || "Browser";
    const pin = byId("pin").value;
    byId("pin").value = "";
    byId("pairingMessage").textContent = "Вход…";
    try {
        const result = await api("/api/pair", "POST", {label, pin});
        device = {deviceId: result.deviceId, token: result.token};
        pendingCommands = {};
        localStorage.removeItem(COMMANDS_KEY);
        writeJson(DEVICE_KEY, device);
        byId("pairingMessage").textContent = "Браузер авторизован";
        showPairing(false);
        await refreshState();
        await refreshDevices();
        await refreshCollections();
    } catch (error) {
        byId("pairingMessage").textContent = error.status === 401
            ? "PIN не принят. После 5 неверных попыток подождите 10 минут."
            : "Не удалось войти. Проверьте связь с приставкой и повторите вход.";
    } finally {
        signingIn = false;
        byId("pairButton").disabled = false;
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
            forgetDevice();
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
    byId("displayMessage").textContent = state.displayReady === false ? "На телевизоре открыт другой экран. Для просмотра откройте ParentCast; очередь и загрузки можно готовить здесь." : "";
    const current = state.currentIndex >= 0 ? state.currentIndex + 1 : 0;
    byId("queueSummary").textContent = `Элемент ${current}/${state.items.length}; позиция ${Math.floor(state.positionMs / 1000)} с; intent ${state.intent}; версия ${state.queueVersion}`;
    byId("updatedAt").textContent = `Обновлено плеером: ${new Date(state.updatedAtMs).toLocaleString()}`;
    byId("playbackError").textContent = state.error ? `${state.error.kind}: ${state.error.diagnostic || ""}` : "";
    byId("diagnostics").textContent = state.diagnostics || "";
    renderQueue();
    renderPrepared();
    if (state.connectUrls) byId("homeConnectUrls").textContent = "Адреса пульта: " + state.connectUrls.join(" · ");
    updateSelectionControls();
}

function renderPrepared() {
    const s = state && state.prepared;
    if (!s) return;
    const gib = bytes => (Number(bytes || 0) / 1073741824).toFixed(1);
    const used = Number(s.usedBytes || 0) < 1073741824 ? (Number(s.usedBytes || 0) / 1048576).toFixed(1) + " МиБ" : gib(s.usedBytes) + " ГиБ";
    byId("preparedSummary").textContent = `${s.enabled ? "Локальный режим" : "Потоковый режим"}${s.paused ? "; подготовка остановлена" : ""}. Готово ${s.ready} из ${s.count}.${s.storageError ? " Ошибка хранилища подготовки." : ""}${s.clearing ? " Удаляем файлы…" : ""}${s.operationError ? " Не удалось удалить файлы; повторите после проверки хранилища." : ""}`;
    byId("preparedStorage").textContent = `Скачано ${used} из ${gib(s.limitBytes)} ГиБ. Свободно на приставке ${gib(s.freeBytes)} ГиБ; резерв ${gib(s.reserveBytes)} ГиБ. Можно скачать ещё ${gib(s.additionalBytes)} ГиБ.`;
    const rows = byId("preparedEntries");
    rows.replaceChildren();
    const names = {WAITING: "В очереди загрузки", DOWNLOADING: "Скачивание", READY: "Готово", ERROR: "Ошибка", DELETED: "Файл удалён", DELETING: "Удаляем файл"};
    const phases = {INFO: "Получаем данные о ролике", VIDEO: "Загружаем видео", AUDIO: "Загружаем звук", VERIFY: "Проверяем загрузку"};
    const errors = {YOUTUBE_AUTH_REQUIRED: "YouTube требует авторизацию. Включите действующую тестовую сессию на ТВ и повторите подготовку", NO_SPACE: "Недостаточно места в лимите / свободного места", UNSUPPORTED: "Нужен публичный завершённый ролик", DOWNLOAD_FAILED: "Загрузка прервана. Проверьте сеть и нажмите «Повторить ошибки»", FILE_DAMAGED: "Файл повреждён. Повторите загрузку"};
    s.entries.forEach(e => {
        const row = document.createElement("div");
        row.className = "download-row";
        const label = document.createElement("p");
        const known = Number.isFinite(e.percent);
        const size = `${(e.bytes / 1048576).toFixed(1)}${Number.isFinite(e.totalBytes) ? " / " + (e.totalBytes / 1048576).toFixed(1) : ""} МиБ`;
        label.textContent = `${e.index}. ${e.title || e.videoId} — ${names[e.status] || e.status}${e.status === "DOWNLOADING" ? ": " + (phases[e.phase] || "Подготовка") : ""}; ${known ? e.percent + "% · " : ""}${size}${e.error ? "; " + (errors[e.error] || e.error) : ""}`;
        const progress = document.createElement("progress");
        progress.max = 100;
        if (known) progress.value = e.percent;
        else progress.removeAttribute("value");
        progress.hidden = !known && e.status !== "DOWNLOADING";
        progress.setAttribute("aria-label", `Готовность ролика ${e.index}`);
        row.append(label, progress);
        if (e.status === "READY" || e.status === "DOWNLOADING") row.append(preparedDeleteButton(e.videoId));
        rows.append(row);
    });
    const files = byId("preparedFiles"); files.replaceChildren();
    (s.files || []).forEach(e => {
        const row = document.createElement("p");
        row.textContent = `${e.title || e.videoId} — ${(e.bytes / 1048576).toFixed(1)} МиБ${e.inQueue ? "; в очереди" : "; вне очереди"}`;
        if (!e.deleting) row.append(preparedDeleteButton(e.videoId));
        files.append(row);
    });
    document.querySelectorAll("[data-prepared]").forEach(button => {
        if (button.dataset.prepared === "RETRY_DELETE") { button.hidden = !s.operationError; button.disabled = busyActions.has("preparation"); }
        else button.disabled = s.clearing || busyActions.has("preparation");
    });
    if (s.enabled && state.status === "LOADING") byId("playerStatus").textContent = "Ожидание полностью подготовленного файла";
}

function preparedDeleteButton(id) {
    const button = document.createElement("button");
    button.className = "secondary"; button.textContent = "Удалить файл";
    button.dataset.preparedDelete = id;
    button.disabled = Boolean(state && state.prepared && state.prepared.clearing) || busyActions.has("preparation");
    return button;
}

async function clearAllQueue() {
    if (!state || !confirm("Остановить воспроизведение и очистить всю очередь, включая историю? Скачанные файлы и сохранённые подборки останутся.")) return;
    return withBusy("clear-all", () => perform(`clear-all-${commandKey()}`, "/api/queue/clear-all", {queueVersion: state.queueVersion, idempotencyKey: commandKey()}));
}

async function preparationAction(action, videoId) {
    if (!state) return;
    if ((action === "DELETE" || action === "DELETE_ALL") && !confirm(action === "DELETE_ALL"
        ? "Остановить ролик и удалить все скачанные видео? Очередь, подборки, PIN и доступы браузеров останутся. Для просмотра потребуется новая подготовка."
        : "Удалить скачанный файл ролика? Если он текущий, воспроизведение остановится. Подготовка будет приостановлена. Очередь и подборки останутся.")) return;
    return withBusy("preparation", async () => {
        byId("preparedMessage").textContent = "Отправка команды…";
        try {
            const payload = {action, queueVersion: state.queueVersion, idempotencyKey: commandKey()};
            if (videoId) payload.videoId = videoId;
            if (action === "DELETE" || action === "DELETE_ALL") payload.stopPlayback = true;
            await perform(`preparation-${commandKey()}`, "/api/prepared", payload);
            byId("preparedMessage").textContent = "Команда подготовки принята";
            await refreshState();
        } catch (error) {
            byId("preparedMessage").textContent = `Команда не подтверждена: ${apiErrorText(error)}`;
            throw error;
        } finally { renderPrepared(); }
    });
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

function isQueueVersionConflict(error) {
    return error.status === 409 && error.data && error.data.error === "QUEUE_VERSION_CONFLICT";
}

function apiErrorText(error) {
    const messages = {
        QUEUE_VERSION_CONFLICT: "Очередь изменилась другим клиентом. Проверьте её и подтвердите действие заново.",
        IDEMPOTENCY_KEY_REUSED: "Ключ команды уже использован для другого действия. Обновите панель и подтвердите нужное действие заново.",
        COLLECTION_STORAGE_LIMIT: "Достигнут лимит сохранённых подборок. Удалите ненужную подборку и повторите сохранение.",
        PREVIEW_NOT_READY: "Список ещё загружается. Дождитесь завершения предпросмотра.",
        PREVIEW_NOT_APPLICABLE: "Этот предпросмотр нельзя применить. Получите допустимый список заново.",
        PREVIEW_NOT_FOUND: "Предпросмотр истёк или был удалён. Получите список заново.",
        COLLECTION_NOT_FOUND: "Подборка уже удалена. Обновите список сохранённых подборок.",
        PREVIEW_BUSY: "Приставка уже получает несколько списков. Дождитесь завершения или отмените другой предпросмотр и повторите.",
        COMMAND_STORAGE_LIMIT: "Достигнут лимит сохранённых команд. Повторите позже.",
        OPEN_PLAYER_ON_TV: "Для просмотра сначала откройте ParentCast на телевизоре. Очередь и подготовка доступны из пульта.",
        FILE_IN_USE: "Ролик используется плеером. Сначала остановите его и подтвердите удаление заново.",
        PREPARATION_BUSY_OR_STORAGE_ERROR: "Подготовка ещё занята или хранилище недоступно. Остановите подготовку, дождитесь завершения отмены и повторите.",
    };
    const code = error.data && error.data.error;
    return messages[code] || (error.status === 429 ? "Приставка временно занята. Повторите позже." : error.message);
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
        if (device) commandCompleted(pending.path, pending.payload, result);
        await refreshState();
        showPendingCommand();
        return result;
    } catch (error) {
        if (error.status === 409) {
            delete pendingCommands[key];
            writeJson(COMMANDS_KEY, pendingCommands);
            byId("commandMessage").textContent = apiErrorText(error);
            if (isQueueVersionConflict(error)) await refreshState();
        } else if (error.status === 401) {
            delete pendingCommands[key];
            writeJson(COMMANDS_KEY, pendingCommands);
            forgetDevice();
            setConnection(false);
        } else if (error.status >= 400 && error.status < 500) {
            delete pendingCommands[key];
            writeJson(COMMANDS_KEY, pendingCommands);
            byId("commandMessage").textContent = `Команда отклонена: ${apiErrorText(error)}`;
        } else {
            retryButton.dataset.commandKey = key;
            retryButton.hidden = false;
            byId("commandMessage").textContent = "Ответ не получен. Повторите сохранённую команду — она не будет выполнена дважды.";
            setConnection(false);
        }
        showPendingCommand();
        throw error;
    }
}

function showPendingCommand() {
    const key = Object.keys(pendingCommands)[0];
    const button = byId("retryCommandButton");
    button.hidden = !key;
    if (key) button.dataset.commandKey = key;
    updateSelectionControls();
}

function hasPending(path, field, value) {
    return Object.values(pendingCommands).some(command => command.path === path && command.payload[field] === value);
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

function forgetDevice() {
    device = null;
    state = null;
    pendingCommands = {};
    collections = [];
    localStorage.removeItem(DEVICE_KEY);
    localStorage.removeItem(COMMANDS_KEY);
    byId("retryCommandButton").hidden = true;
    byId("collections").replaceChildren();
    showPairing(true);
    setConnection(false);
}

function clearPreview() {
    if (previewPoll !== null) clearTimeout(previewPoll);
    previewPoll = null;
    preview = null;
    selectedIndices = new Set();
    byId("playlistPreview").hidden = true;
    byId("playlistCancelButton").hidden = true;
    byId("playlistEntries").replaceChildren();
    byId("playlistMessage").textContent = "";
}

function previewCanApply() {
    return preview && ["COMPLETE", "PARTIAL"].includes(preview.status);
}

function commandCompleted(path, payload, result) {
    if (path === "/api/playlists/preview") {
        clearPreview();
        preview = {previewId: result.previewId, status: "LOADING", entries: []};
        byId("playlistMessage").textContent = "Получение списка на приставке… Управление плеером доступно.";
        byId("playlistCancelButton").hidden = false;
        schedulePreviewPoll(0);
    } else if (path === "/api/playlists/cancel") {
        if (preview && preview.previewId === payload.previewId) {
            clearPreview();
            byId("playlistMessage").textContent = "Предпросмотр отменён. Очередь не изменена.";
        }
    } else if (path === "/api/playlists/apply") {
        byId("playlistMessage").textContent = `Добавлены выбранные элементы: ${payload.indices.length}. Воспроизведение не запускается этой командой.`;
    } else if (path === "/api/collections/save") {
        byId("collectionsMessage").textContent = "Подборка сохранена на приставке как фиксированный список.";
        run(refreshCollections);
    } else if (path === "/api/collections/delete") {
        byId("collectionsMessage").textContent = "Подборка удалена. Текущая очередь не изменена.";
        run(refreshCollections);
    } else if (path === "/api/collections/apply") {
        byId("collectionsMessage").textContent = "Фиксированный список добавлен в очередь. Воспроизведение не запускается этой командой.";
    }
}

function schedulePreviewPoll(delay = 1000) {
    if (previewPoll !== null) clearTimeout(previewPoll);
    previewPoll = setTimeout(() => {
        previewPoll = null;
        run(pollPreview);
    }, delay);
}

async function pollPreview() {
    if (!device || !preview || preview.status !== "LOADING") return;
    if (previewPollRunning) {
        schedulePreviewPoll();
        return;
    }
    const id = preview.previewId;
    previewPollRunning = true;
    try {
        const result = await api(`/api/playlists/preview/${encodeURIComponent(id)}`, "GET");
        if (!preview || preview.previewId !== id || !device) return;
        preview = result;
        renderPreview();
        if (preview.status === "LOADING") schedulePreviewPoll();
    } catch (error) {
        if (!preview || preview.previewId !== id || !device) return;
        if (error.status === 404 || error.status === 410) {
            clearPreview();
            byId("playlistMessage").textContent = "Предпросмотр истёк. Получите список заново; очередь не изменена.";
        } else {
            byId("playlistMessage").textContent = "Не удалось обновить предпросмотр. Полнота списка не подтверждена; повторяем запрос.";
            schedulePreviewPoll(3000);
        }
    } finally {
        previewPollRunning = false;
    }
}

const availabilityNames = {
    AVAILABLE: "Доступен в публичном списке; воспроизведение ещё не проверено",
    UNAVAILABLE: "Недоступен, удалён или скрыт",
    UNKNOWN: "Доступность не проверена",
    UNSUPPORTED: "Не поддерживается: эфир, премьера или ограничение доступа",
};

function formatDuration(seconds) {
    if (typeof seconds !== "number" || !Number.isFinite(seconds) || seconds < 0) return "Длительность неизвестна";
    const minutes = Math.floor(seconds / 60);
    return `${minutes}:${String(Math.floor(seconds % 60)).padStart(2, "0")}`;
}

function renderPreview() {
    if (!preview) return;
    const entries = Array.isArray(preview.entries) ? preview.entries : [];
    const terminalMessages = {
        LOADING: "Получение списка на приставке… Управление плеером доступно.",
        COMPLETE: "COMPLETE: список получен. Выберите ролики и явно подтвердите добавление или сохранение.",
        PARTIAL: "PARTIAL: получена только часть списка. Полный импорт не подтверждён.",
        LIMIT_EXCEEDED: "Превышен лимит 500 элементов. Импорт и сохранение этого предпросмотра запрещены; выберите меньший плейлист.",
        ERROR: "Ошибка получения плейлиста. Очередь не изменена.",
        CANCELLED: "Предпросмотр отменён. Очередь не изменена.",
    };
    byId("playlistMessage").textContent = [terminalMessages[preview.status] || "Неизвестный статус предпросмотра", preview.reason].filter(Boolean).join(" ");
    byId("playlistMessage").className = `message${["PARTIAL", "ERROR", "LIMIT_EXCEEDED"].includes(preview.status) ? " error" : ""}`;
    byId("playlistPreview").hidden = preview.status === "LOADING" || preview.status === "CANCELLED";
    byId("playlistCancelButton").hidden = preview.status === "CANCELLED";
    byId("playlistTitle").textContent = preview.title || "Плейлист";
    const reported = preview.reportedCount == null ? "неизвестно" : String(preview.reportedCount);
    const selectableCount = entries.filter(entry => entry.selectable).length;
    byId("playlistCount").textContent = `Получено: ${entries.length}; заявлено YouTube: ${reported}; допускаются к выбору: ${selectableCount}. Исходный порядок сохранён.`;
    byId("playlistEntries").replaceChildren();
    byId("playlistSelection").hidden = !previewCanApply();
    byId("playlistConfirm").hidden = !previewCanApply();
    byId("playlistPartialNotice").hidden = preview.status !== "PARTIAL";
    const validIndices = new Set(entries.filter(entry => entry.selectable).map(entry => entry.index));
    selectedIndices = new Set([...selectedIndices].filter(index => validIndices.has(index)));
    entries.forEach(entry => {
        const row = document.createElement("label");
        row.className = "playlist-entry";
        const input = document.createElement("input");
        input.type = "checkbox";
        input.dataset.index = String(entry.index);
        input.checked = selectedIndices.has(entry.index);
        input.disabled = !entry.selectable || !previewCanApply();
        const content = document.createElement("span");
        const title = document.createElement("strong");
        title.textContent = `${entry.index}. ${entry.title || "Без названия"}`;
        const meta = document.createElement("span");
        meta.className = "muted small entry-meta";
        meta.textContent = [entry.videoId, entry.channel, formatDuration(entry.durationSeconds), availabilityNames[entry.availability] || "Доступность не проверена"].filter(Boolean).join(" · ");
        content.append(title, meta);
        row.append(input, content);
        byId("playlistEntries").append(row);
    });
    updateSelectionControls();
}

function updateSelectionControls() {
    const selected = selectedIndices.size;
    byId("playlistSelectedCount").textContent = `Выбрано: ${selected}`;
    const previewId = preview ? preview.previewId : "";
    byId("playlistApplyButton").disabled = !previewCanApply() || !selected || busyActions.has("apply") || !state || hasPending("/api/playlists/apply", "previewId", previewId);
    byId("collectionSaveButton").disabled = !previewCanApply() || !selected || busyActions.has("save") || !byId("collectionName").value.trim() || hasPending("/api/collections/save", "previewId", previewId);
    byId("playlistPreviewButton").disabled = previewRequesting || busyActions.has("cancel") || Object.values(pendingCommands).some(command => command.path === "/api/playlists/preview");
    byId("playlistCancelButton").disabled = busyActions.has("cancel") || hasPending("/api/playlists/cancel", "previewId", previewId);
    document.querySelectorAll("button[data-collection-action]").forEach(button => {
        const id = button.dataset.collectionId;
        button.disabled = busyActions.has(`collection-${id}`) || hasPending(`/api/collections/${button.dataset.collectionAction}`, "collectionId", id);
    });
}

async function withBusy(action, operation) {
    if (busyActions.has(action)) return;
    busyActions.add(action);
    updateSelectionControls();
    try {
        return await operation();
    } finally {
        busyActions.delete(action);
        updateSelectionControls();
    }
}

async function startPreview() {
    if (previewRequesting) return;
    const url = byId("playlistUrl").value.trim();
    if (!url) {
        byId("playlistMessage").textContent = "Вставьте ссылку на публичный YouTube-плейлист.";
        return;
    }
    previewRequesting = true;
    updateSelectionControls();
    try {
        if (preview) await cancelPreview();
        byId("playlistMessage").textContent = "Запрашиваем предпросмотр…";
        await perform(`preview-${commandKey()}`, "/api/playlists/preview", {url, idempotencyKey: commandKey()});
    } catch (error) {
        byId("playlistMessage").textContent = error.status === 400
            ? "Предпросмотр отклонён: проверьте ссылку на публичный статический YouTube-плейлист."
            : error.status >= 400 && error.status < 500
                ? `Предпросмотр отклонён: ${apiErrorText(error)}`
                : "Ответ на запрос предпросмотра не получен. Используйте кнопку повтора сохранённой команды.";
        throw error;
    } finally {
        previewRequesting = false;
        updateSelectionControls();
    }
}

async function cancelPreview() {
    if (!preview) return;
    const previewId = preview.previewId;
    return withBusy("cancel", () => perform(`cancel-preview-${commandKey()}`, "/api/playlists/cancel", {previewId, idempotencyKey: commandKey()}));
}

function selectedPayload() {
    if (!previewCanApply() || !selectedIndices.size) throw new Error("NO_SELECTION");
    return {previewId: preview.previewId, indices: [...selectedIndices].sort((a, b) => a - b)};
}

async function applyPreview() {
    if (!state) return;
    const payload = {...selectedPayload(), queueVersion: state.queueVersion, idempotencyKey: commandKey()};
    return withBusy("apply", async () => {
        try {
            await perform(`apply-preview-${commandKey()}`, "/api/playlists/apply", payload);
        } catch (error) {
            byId("playlistMessage").textContent = isQueueVersionConflict(error)
                ? "Очередь изменилась. Проверьте актуальную очередь и нажмите «Добавить выбранные» снова для нового подтверждения."
                : `Добавление не подтверждено: ${apiErrorText(error)}${error.status >= 400 && error.status < 500 ? "" : ". При потере ответа повторите сохранённую команду."}`;
            throw error;
        }
    });
}

async function saveCollection() {
    const name = byId("collectionName").value.trim();
    if (!name) return;
    const payload = {...selectedPayload(), name, idempotencyKey: commandKey()};
    return withBusy("save", async () => {
        try {
            await perform(`save-collection-${commandKey()}`, "/api/collections/save", payload);
        } catch (error) {
            byId("collectionsMessage").textContent = `Сохранение не подтверждено: ${apiErrorText(error)}`;
            throw error;
        }
    });
}

async function refreshCollections() {
    if (!device) return;
    try {
        const result = await api("/api/collections", "GET");
        collections = result.collections;
        renderCollections();
    } catch (error) {
        if (device) byId("collectionsMessage").textContent = `Не удалось получить сохранённые подборки: ${error.message}`;
    }
}

function renderCollections() {
    const container = byId("collections");
    container.replaceChildren();
    if (!collections.length) {
        const empty = document.createElement("p");
        empty.className = "muted";
        empty.textContent = "Сохранённых подборок пока нет.";
        container.append(empty);
    }
    collections.forEach(collection => {
        const item = document.createElement("article");
        item.className = "saved-collection";
        const heading = document.createElement("h3");
        heading.textContent = collection.name;
        const details = document.createElement("details");
        const summary = document.createElement("summary");
        summary.textContent = `Фиксированный список: ${collection.videoIds.length} видео`;
        const ids = document.createElement("ol");
        collection.videoIds.forEach(id => {
            const row = document.createElement("li");
            row.textContent = id;
            ids.append(row);
        });
        details.append(summary, ids);
        const controls = document.createElement("div");
        controls.className = "controls";
        const add = document.createElement("button");
        add.textContent = "Добавить подборку в очередь";
        add.dataset.collectionAction = "apply";
        add.dataset.collectionId = collection.id;
        const remove = document.createElement("button");
        remove.className = "secondary";
        remove.textContent = "Удалить подборку";
        remove.dataset.collectionAction = "delete";
        remove.dataset.collectionId = collection.id;
        controls.append(add, remove);
        item.append(heading, details, controls);
        container.append(item);
    });
    updateSelectionControls();
}

async function collectionAction(action, collectionId) {
    if (action === "apply" && !state) return;
    const payload = {collectionId, idempotencyKey: commandKey()};
    if (action === "apply") payload.queueVersion = state.queueVersion;
    return withBusy(`collection-${collectionId}`, async () => {
        try {
            await perform(`collection-${action}-${commandKey()}`, `/api/collections/${action}`, payload);
        } catch (error) {
            byId("collectionsMessage").textContent = isQueueVersionConflict(error)
                ? "Очередь изменилась. Проверьте её и подтвердите добавление подборки ещё раз."
                : `Действие с подборкой не подтверждено: ${apiErrorText(error)}`;
            throw error;
        }
    });
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
byId("clearAllButton").addEventListener("click", () => run(clearAllQueue));
byId("clearPendingButton").addEventListener("click", () => run(clearPending));
byId("playlistPreviewButton").addEventListener("click", () => run(startPreview));
byId("playlistCancelButton").addEventListener("click", () => run(cancelPreview));
byId("playlistApplyButton").addEventListener("click", () => run(applyPreview));
byId("collectionSaveButton").addEventListener("click", () => run(saveCollection));
byId("collectionsRefreshButton").addEventListener("click", () => run(refreshCollections));
byId("collectionName").addEventListener("input", updateSelectionControls);
byId("playlistEntries").addEventListener("change", event => {
    const input = event.target.closest('input[type="checkbox"][data-index]');
    if (!input || input.disabled) return;
    const index = Number(input.dataset.index);
    if (input.checked) selectedIndices.add(index);
    else selectedIndices.delete(index);
    updateSelectionControls();
});
byId("playlistSelectAllButton").addEventListener("click", () => {
    if (!previewCanApply()) return;
    selectedIndices = new Set(preview.entries.filter(entry => entry.selectable).map(entry => entry.index));
    renderPreview();
});
byId("playlistSelectNoneButton").addEventListener("click", () => {
    selectedIndices.clear();
    renderPreview();
});
byId("collections").addEventListener("click", event => {
    const button = event.target.closest("button[data-collection-action]");
    if (button) run(() => collectionAction(button.dataset.collectionAction, button.dataset.collectionId));
});
document.querySelectorAll("[data-playback]").forEach(button => {
    button.addEventListener("click", () => run(() => playback(button.dataset.playback)));
});
byId("queue").addEventListener("click", event => {
    const button = event.target.closest("button[data-action]");
    if (button) run(() => queueAction(button.dataset.action, button.dataset.id));
});

["preparedEntries", "preparedFiles"].forEach(id => byId(id).addEventListener("click", event => {
    const button = event.target.closest("button[data-prepared-delete]");
    if (button) run(() => preparationAction("DELETE", button.dataset.preparedDelete));
}));
document.querySelectorAll("[data-prepared]").forEach(button => {
    button.addEventListener("click", () => run(() => preparationAction(button.dataset.prepared)));
});

showPairing(!device);
showPendingCommand();
if (device) {
    showPairing(false);
    refreshState();
    refreshDevices();
    refreshCollections();
}
setInterval(() => {
    if (device && !document.hidden) refreshState();
}, 2000);
