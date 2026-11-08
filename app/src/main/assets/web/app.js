"use strict";

const DEVICE_KEY = "parentcast.device.v1";
const COMMANDS_KEY = "parentcast.commands.v1";
let device = readJson(DEVICE_KEY, null);
let pendingCommands = readJson(COMMANDS_KEY, {});
let state = null;
let renderedQueueKey = null;
let currentThumbnailId = null;
let preparedFilesViewKey = null;
function resumeTime(ms) { return formatDuration(Math.max(0,Math.floor((Number(ms)||0)/1000))); }
function appendVideoThumbnail(container,id,title) { if(typeof addVideoThumbnail === "function")addVideoThumbnail(container,id,title); }
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
        const fresh=await api("/api/state", "GET");
        if(!state || fresh.queueVersion>=state.queueVersion)state=fresh;
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
    byId("currentTitle").textContent = state.currentTitle || state.items[state.currentIndex]?.title || "Ролик не выбран";
    const currentItem=state.items[state.currentIndex];
    if(currentThumbnailId !== currentItem?.videoId) {
        currentThumbnailId=currentItem?.videoId;
        const container=byId("currentVideo");
        container.querySelectorAll?.(".video-thumbnail").forEach(node=>node.remove());
        if(currentItem)appendVideoThumbnail(container,currentItem.videoId,state.currentTitle || currentItem.title);
    }
    byId("resumePlayback").textContent=state.positionMs>0 && !state.finished ? `Продолжить с ${resumeTime(state.positionMs)}` : "Смотреть";
    byId("displayMessage").textContent = state.displayReady === false ? "На телевизоре открыт другой экран. Для просмотра откройте ParentCast; очередь и загрузки можно готовить здесь." : "";
    const current = state.currentIndex >= 0 ? state.currentIndex + 1 : 0;
    const activeGroup = (state.groups || []).find(g=>g.id === state.activeGroupId);
    byId("queueSummary").textContent = activeGroup ? `Плейлист «${activeGroup.name}»: ${activeGroup.videoIds.indexOf(state.items[state.currentIndex]?.videoId)+1}/${activeGroup.videoIds.length}. Позиция ${resumeTime(state.positionMs)}. В конце — остановка.` : `Элемент ${current}/${state.items.length}; позиция ${Math.floor(state.positionMs / 1000)} с; intent ${state.intent}; версия ${state.queueVersion}`;
    byId("updatedAt").textContent = `Обновлено плеером: ${new Date(state.updatedAtMs).toLocaleString()}`;
    byId("playbackError").textContent = state.error && (state.error.diagnostic || "").includes("PREPARATION_REQUIRED")
        ? "Для VK нажмите «Подготовить очередь / продолжить» и дождитесь готовности файла."
        : state.error && (state.error.diagnostic || "").includes("FILE_DAMAGED") ? "Скачанный файл повреждён. Требуется повторная подготовка ролика."
        : state.error ? `${state.error.kind}: ${state.error.diagnostic || ""}` : "";
    byId("diagnostics").textContent = state.diagnostics || "";
    renderQueue();
    renderPrepared();
    if(typeof renderTimeline === "function")renderTimeline();
    if (state.connectUrls) byId("homeConnectUrls").textContent = "Адреса пульта: " + state.connectUrls.join(" · ");
    updateSelectionControls();
    if (typeof renderFamilyState === "function") renderFamilyState();
    if (typeof renderExperience === "function") renderExperience();
}

function renderPrepared() {
    const s = state && state.prepared;
    if (!s) return;
    const gib = bytes => (Number(bytes || 0) / 1073741824).toFixed(1);
    const used = Number(s.usedBytes || 0) < 1073741824 ? (Number(s.usedBytes || 0) / 1048576).toFixed(1) + " МиБ" : gib(s.usedBytes) + " ГиБ";
    const noSpace = s.enabled && s.paused && !s.clearing && s.entries.some(e=>e.status === "ERROR" && e.error === "NO_SPACE");
    const quotaFirst = Number(s.limitBytes)-Number(s.usedBytes) <= Number(s.freeBytes)-Number(s.reserveBytes);
    const spaceWarning = noSpace ? `Загрузка остановлена: для следующего файла ${quotaFirst ? "не хватает места в лимите " + gib(s.limitBytes) + " ГиБ" : "не хватает свободного места с учётом резерва Android"}. Выберите другое хранилище или освободите место в настройках, затем продолжите загрузку. Готовые файлы сохранены.` : "";
    byId("preparationAlert").hidden = !noSpace;
    byId("preparationAlert").textContent = spaceWarning;
    const availableFiles=(s.files||[]).filter(e=>e.available!==false).length;
    const absentFiles=(s.files||[]).filter(e=>e.available===false).length;
    byId("preparedSummary").textContent = `${s.enabled ? "Локальный режим" : "Потоковый режим"}${s.onlinePriority ? "; загрузки уступают онлайн-просмотру" : s.paused ? "; подготовка остановлена" : ""}. Готово в текущем плане: ${s.ready} из ${s.count}. Готовых доступных файлов: ${availableFiles}.${absentFiles?" На отключённом носителе: "+absentFiles+".":""}${s.storageError ? " Ошибка хранилища подготовки." : ""}${s.clearing ? " Удаляем файлы…" : ""}${s.operationError ? " Не удалось удалить файлы; повторите после проверки хранилища." : ""}`;
    const available = Number(s.additionalBytes || 0) < 1073741824 ? (Number(s.additionalBytes || 0) / 1048576).toFixed(1) + " МиБ" : gib(s.additionalBytes) + " ГиБ";
    byId("preparedStorage").textContent = `В выбранном хранилище ${used} из ${gib(s.limitBytes)} ГиБ. Свободно на носителе ${gib(s.freeBytes)} ГиБ; резерв ${gib(s.reserveBytes)} ГиБ. Можно скачать ещё ${available}. Весь большой плейлист может не поместиться: готовые файлы не удаляются автоматически.`;
    const rows = byId("preparedEntries");
    const existingRows = rows.querySelectorAll ? new Map([...rows.querySelectorAll(".download-row")].map(row=>[row.dataset.videoId,row])) : null;
    const existingGroups = rows.querySelectorAll ? new Map([...rows.querySelectorAll(".download-group")].map(group=>[group.dataset.downloadGroup,group])) : null;
    const liveIds=new Set(),liveGroups=new Set(),groupPositions=new Map();
    if(!existingRows)rows.replaceChildren();
    const ownerFor=entry=>(state.groups||[]).find(g=>g.id===s.targetGroupId&&g.videoIds.includes(entry.videoId)) || (state.groups||[]).find(g=>g.videoIds.includes(entry.videoId));
    const groupParent=group=>{
        const id=group?.id||"loose";let box=existingGroups.get(id);
        if(!box){box=document.createElement("details");box.className="download-group card";box.dataset.downloadGroup=id;box.open=id===(s.targetGroupId||state.activeGroupId)||!group;const summary=document.createElement("summary");const body=document.createElement("div");body.className="download-group-items";box.append(summary,body);existingGroups.set(id,box);rows.append(box);}
        const plan=s.entries.filter(e=>(ownerFor(e)?.id||"loose")===id);
        const members=group?group.videoIds.map(id=>state.items.find(e=>e.videoId===id)).filter(Boolean):state.items.filter(e=>!(state.groups||[]).some(g=>g.videoIds.includes(e.videoId)));
        const ready=members.filter(item=>typeof videoReady==="function"?videoReady(item):item.ready===true).length;
        box.firstChild.textContent=`${group?.name||"Отдельные видео"} · ${ready} из ${members.length} скачано${id===s.targetGroupId?" · выбран для загрузки":""}`;
        const body=box.lastChild,empty=body.querySelector(".download-empty");
        if(!plan.length&&!empty){const note=document.createElement("p");note.className="download-empty muted small";note.textContent="Не включён в текущий план загрузки. Чтобы скачать, откройте плейлист в медиатеке и нажмите «Скачать плейлист».";body.append(note);}else if(plan.length)empty?.remove();
        liveGroups.add(id);return body;
    };
    if(existingGroups)for(const group of state.groups||[])groupParent(group);
    const parentFor = entry => !existingGroups || !(state.groups||[]).length ? rows : groupParent(ownerFor(entry));
    const names = {STORAGE_UNAVAILABLE: "Носитель отключён — подключите карту", STORAGE_BUSY: "Хранилище занято", WAITING: "В очереди загрузки", DOWNLOADING: "Скачивание", READY: "Готово", ERROR: "Ошибка", DELETED: "Файл удалён", DELETING: "Удаляем файл", RETRY_WAIT: "Ожидаем восстановления сети"};
    const phases = {INFO: "Получаем данные о ролике", VIDEO: "Загружаем видео", AUDIO: "Загружаем звук", VERIFY: "Проверяем загрузку"};
    const errors = {INTEGRITY_RETRY: "Проверка не пройдена. Скачиваем заново; не более двух повторов", MEDIA_CORRUPT: "Видео не прошло проверку. Подготовка остановлена", WRITE_VERIFY_FAILED: "Записанный файл отличается от полученных данных. Подготовка остановлена", STORAGE_UNAVAILABLE: "Подключите носитель; файл не удалён",STORAGE_READ_ONLY: "Носитель доступен только для чтения",STORAGE_BUSY: "Проверяем хранилище",STORAGE_ERROR: "Хранилище недоступно. Проверьте место и повторите подготовку",UNAVAILABLE: "Ролик больше недоступен у источника",YOUTUBE_AUTH_REQUIRED: "YouTube требует авторизацию. Включите действующую тестовую сессию на ТВ и повторите подготовку", NO_SPACE: "Недостаточно места в лимите / свободного места", UNSUPPORTED: "Нужен публичный завершённый ролик", NETWORK_RETRY: "Сеть прервалась. Повторим автоматически", DOWNLOAD_FAILED: "Загрузка прервана. Проверьте сеть и нажмите «Повторить ошибки»", FILE_DAMAGED: "Файл повреждён. Повторите загрузку"};
    s.entries.forEach(e => {
        const prior=existingRows?.get(e.videoId);
        const row = prior || document.createElement("div");
        row.className = "download-row";row.dataset.videoId=e.videoId;liveIds.add(e.videoId);
        const label = prior?.children[0] || document.createElement("p");
        const known = Number.isFinite(e.percent);
        const retrySeconds = Math.max(0,Math.ceil((e.retryAtMs - (state.serverTimeMs || Date.now())) / 1000));
        const retry = e.status === "RETRY_WAIT" ? `; попытка ${e.attempts}; ${retrySeconds ? "повтор через " + retrySeconds + " с" : "повтор готовится"}` : "";
        const size = `${(e.bytes / 1048576).toFixed(1)}${Number.isFinite(e.totalBytes) ? " / " + (e.totalBytes / 1048576).toFixed(1) : ""} МиБ`;
        const text = `Приоритет ${e.index} · ${e.title || e.videoId} · ${formatDuration(e.durationSeconds)} — ${names[e.status] || e.status}${e.status === "DOWNLOADING" ? ": " + (phases[e.phase] || "Подготовка") : ""}; ${known ? e.percent + "% · " : ""}${size}${retry}${e.error ? "; " + (errors[e.error] || e.error) : ""}`;
        if(prior&&label.firstChild?.nodeType===3)label.firstChild.textContent=text;else label.textContent=text;
        const progress = prior?.children[1] || document.createElement("progress");
        progress.max = 100;
        if (known) progress.value = e.percent;
        else progress.removeAttribute("value");
        progress.hidden = !known && e.status !== "DOWNLOADING";
        progress.setAttribute("aria-label", `Готовность ролика ${e.index}`);
        if(!prior){appendVideoThumbnail(label,e.videoId,e.title);row.append(label,progress);}
        let del=prior?.querySelector("button[data-prepared-delete]");
        if(e.status==="READY"||e.status==="DOWNLOADING"){if(!del){del=preparedDeleteButton(e.videoId);row.append(del);}del.disabled=!!s.clearing||busyActions.has("preparation");}else del?.remove();
        const parent=parentFor(e),position=groupPositions.get(parent)||0;groupPositions.set(parent,position+1);
        if(!existingRows)parent.append(row);else if(parent.children[position]!==row)parent.insertBefore(row,parent.children[position]||null);
    });
    if(existingRows)for(const [id,row] of existingRows)if(!liveIds.has(id))row.remove();
    if(existingGroups)for(const [id,group] of existingGroups)if(!liveGroups.has(id))group.remove();
    const files = byId("preparedFiles"),fileKey=JSON.stringify(s.files||[]);
    if(!files.querySelectorAll || preparedFilesViewKey!==fileKey){
    preparedFilesViewKey=fileKey;files.replaceChildren();
    (s.files || []).forEach(e => {
        const row = document.createElement("p");
        row.textContent = `${e.title || e.videoId} · ${formatDuration(e.durationSeconds)} — ${(e.bytes / 1048576).toFixed(1)} МиБ${e.inQueue ? "; в очереди" : "; вне очереди"}`;
        appendVideoThumbnail(row,e.videoId,e.title);
        if(e.available===false){const note=document.createElement("span");note.textContent=" · Подключите носитель";row.append(note);}
        else if (!e.deleting) row.append(preparedDeleteButton(e.videoId));
        files.append(row);
    });
    }
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
    if (typeof renderQueueEditor === "function" && renderQueueEditor()) { renderedQueueKey=null; return; }
    if(typeof renderProgrammeGroups === "function") { renderProgrammeGroups(); return; }
    const key=JSON.stringify([state.currentIndex,state.items.map(e=>[e.queueItemId,e.videoId,e.local,e.title])]);
    if(renderedQueueKey===key)return;
    renderedQueueKey=key;
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
        title.textContent = `${index + 1}. ${item.title || item.videoId} · ${formatDuration(item.durationSeconds)}${item.local ? " · своё видео" : ""}${index === state.currentIndex ? " — текущий" : ""}`;
        appendVideoThumbnail(title,item.videoId,item.title);
        row.append(title);
        const watch=queueButton("Смотреть", "select", item.queueItemId);
        row.append(watch);
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
        PLAYBACK_CHANGED: "Ролик или очередь изменились. Проверьте текущий ролик и перемотайте заново.",
        SEEK_UNAVAILABLE: "Сейчас перемотка недоступна. Дождитесь загрузки текущего ролика в плеер.",
        SEEK_FAILED: "Не удалось перемотать. Текущий просмотр сохранён; попробуйте ещё раз.",
        QUEUE_VERSION_CONFLICT: "Очередь изменилась другим клиентом. Проверьте её и подтвердите действие заново.",
        IDEMPOTENCY_KEY_REUSED: "Ключ команды уже использован для другого действия. Обновите панель и подтвердите нужное действие заново.",
        COLLECTION_STORAGE_LIMIT: "Достигнут лимит сохранённых подборок. Удалите ненужную подборку и повторите сохранение.",
        PREVIEW_NOT_READY: "Список ещё загружается. Дождитесь завершения предпросмотра.",
        PREVIEW_NOT_APPLICABLE: "Этот предпросмотр нельзя применить. Получите допустимый список заново.",
        PREVIEW_NOT_FOUND: "Предпросмотр истёк или был удалён. Получите список заново.",
        COLLECTION_NOT_FOUND: "Подборка уже удалена. Обновите список сохранённых подборок.",
        PREVIEW_BUSY: "Приставка уже получает несколько списков. Дождитесь завершения или отмените другой предпросмотр и повторите.",
        COMMAND_STORAGE_LIMIT: "Достигнут лимит сохранённых команд. Повторите позже.",
        IMPORT_BUSY: "Другая передача ещё завершается. Повторите через несколько секунд.",
        USB_UNAVAILABLE_OR_PERMISSION_REQUIRED: "Флешка недоступна. Подключите её и выберите папку в родительском меню на ТВ.",
        BAD_MEDIA_INPUT: "Проверьте название и размер файла. Он должен помещаться в выбранное хранилище.",
        STORAGE_DESTINATION_CONFLICT: "В выбранном хранилище сохранён исходный файл. Подготовка не начата; автоматическая перезапись запрещена.",
        STORAGE_CHANGED: "Хранилище изменилось другим родителем. Проверьте выбор и подтвердите действие заново.",
        STORAGE_UNAVAILABLE: "Носитель недоступен. Подключите выбранную карту памяти.",
        STORAGE_READ_ONLY: "Носитель доступен только для чтения. Выберите другое хранилище.",
        STORAGE_BUSY: "Хранилище занято проверкой или переносом. Дождитесь завершения операции.",
        STOP_PLAYBACK_FIRST: "Сначала нажмите Стоп в пульте, затем повторите освобождение памяти.",
        LOCAL_FILE_NOT_FOUND: "Файл уже удалён. Обновите список своих видео.",
        VIDEO_NOT_READY: "Ролик ещё не готов. Подготовьте плейлист и дождитесь загрузки.",
        OPEN_PLAYER_ON_TV: "Для просмотра сначала откройте ParentCast на телевизоре. Очередь и подготовка доступны из пульта.",
        FILE_IN_USE: "Ролик используется плеером. Сначала остановите его и подтвердите удаление заново.",
        PREPARATION_BUSY_OR_STORAGE_ERROR: "Подготовка ещё занята или хранилище недоступно. Остановите подготовку, дождитесь завершения отмены и повторите.",
    };
    const code = error.data && error.data.error;
    return messages[code] || (error.status === 429 ? "Приставка временно занята. Повторите позже." : error.message);
}

async function perform(key, path, payload, afterApply) {
    const retryButton = byId("retryCommandButton");
    retryButton.hidden = true;
    let pending = pendingCommands[key];
    if (!pending) {
        pending = {path, payload};
        if (afterApply) pending.afterApply = afterApply;
        pendingCommands[key] = pending;
        writeJson(COMMANDS_KEY, pendingCommands);
    }
    try {
        const result = pending.appliedResult || await api(pending.path, "POST", pending.payload);
        if (pending.afterApply && typeof finishImportDownload === "function") {
            // Persist the accepted group before starting the separately confirmed preparation.
            // Lost responses retry the same selection/ENABLE keys, never a second import.
            pending.appliedResult = {addition:result.addition,queueVersion:result.queueVersion};
            writeJson(COMMANDS_KEY,pendingCommands);
            try { await finishImportDownload(pending); }
            catch (error) { error.playlistSaved = true; throw error; }
        }
        delete pendingCommands[key];
        writeJson(COMMANDS_KEY, pendingCommands);
        byId("commandMessage").textContent = "Команда принята; состояние уточняется у плеера";
        if (device) commandCompleted(pending.path, pending.payload, result);
        if (pending.path==="/api/playlists/preview" || pending.path==="/api/playlists/cancel")byId("commandMessage").textContent="";
        await refreshState();
        showPendingCommand();
        if (pending.afterApply) {
            byId("commandMessage").textContent = "Плейлист сохранён. Загрузка поставлена в очередь; просмотр не запущен.";
            if(typeof showFamilyPage === "function")showFamilyPage("downloads");
        }
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
        if(error.playlistSaved)byId("commandMessage").textContent="Плейлист сохранён, но начало загрузки не подтверждено. "+apiErrorText(error)+(error.status>=400&&error.status<500?" Проверьте хранилище и нажмите «Подготовить плейлист».":" Повторите сохранённую команду; список повторно не добавится.");
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
    if (!state) return;
    return withBusy("links-add", async () => {
        const input = byId("links").value;
        const links = input.split(/\r?\n/).map(value => value.trim()).filter(Boolean);
        if (!links.length) return;
        await perform(`add-${commandKey()}`, "/api/queue/add", {links, queueVersion: state.queueVersion, idempotencyKey: commandKey()});
        if (byId("links").value === input) byId("links").value = "";
    });
}

async function playNow() {
    return withBusy("play-now", async () => {
        const input = byId("playNowLink").value;
        const videoId = input.trim();
        if (!videoId) return;
        await perform(`play-now-${commandKey()}`, "/api/queue/play-now", {videoId, idempotencyKey: commandKey()});
        if (byId("playNowLink").value === input) byId("playNowLink").value = "";
    });
}

async function playback(action) {
    await perform(`playback-${action}-${commandKey()}`, "/api/playback", {action, idempotencyKey: commandKey()});
}

async function queueAction(action, id) {
    if (!state) return;
    if (action === "select") {
        await perform(`select-${id}-${commandKey()}`, "/api/queue/select", {queueItemId:id,queueVersion:state.queueVersion,play:true,idempotencyKey:commandKey()});
    } else if (action === "delete") {
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
    if(typeof clearThumbnails === "function")clearThumbnails();
    if(typeof clearExperienceAuth === "function")clearExperienceAuth();
    currentThumbnailId=null;
    if(typeof renderTimeline === "function")renderTimeline();
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
    if(typeof resetFamilyImport === "function")resetFamilyImport();
    byId("playlistPreview").hidden = true;
    byId("playlistCancelButton").hidden = true;
    byId("playlistEntries").replaceChildren();
    byId("playlistMessage").textContent = "";
}

function previewCanApply() {
    return preview && ["COMPLETE", "PARTIAL"].includes(preview.status);
}

function queueAdditionMessage(result) {
    const addition = result && result.addition;
    if (!addition) return "Команда добавления обработана. Воспроизведение не запущено.";
    if (addition.groupId) return `Плейлист добавлен в программу. Новых видео: ${addition.added}; общих с программой: ${addition.skipped}. Воспроизведение не запущено.`;
    if (addition.added === 0) return "Все выбранные ролики уже есть в очереди. Повторы не добавлены.";
    return `Добавлено новых: ${addition.added}. Пропущено повторов: ${addition.skipped}. Воспроизведение не запущено.`;
}

function commandCompleted(path, payload, result) {
    if(path==="/api/video/apply"&&typeof videoApplyFeedback==="function")videoApplyFeedback(result,payload);
    if (path === "/api/queue/add") {
        byId("commandMessage").textContent = queueAdditionMessage(result);
    } else if (path === "/api/playlists/preview") {
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
        byId("playlistMessage").textContent = queueAdditionMessage(result);
    } else if (path === "/api/collections/save") {
        byId("collectionsMessage").textContent = "Подборка сохранена на приставке как фиксированный список.";
        run(refreshCollections);
    } else if (path === "/api/collections/delete") {
        byId("collectionsMessage").textContent = "Подборка удалена. Текущая очередь не изменена.";
        run(refreshCollections);
    } else if (path === "/api/collections/apply") {
        byId("collectionsMessage").textContent = queueAdditionMessage(result);
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
    const total=Math.floor(seconds),hours=Math.floor(total/3600),minutes=Math.floor(total/60)%60;
    return `${hours?hours+":"+String(minutes).padStart(2,"0"):minutes}:${String(total%60).padStart(2,"0")}`;
}

function renderPreview() {
    if (!preview) return;
    const entries = typeof orderedPreviewEntries === "function" ? orderedPreviewEntries() : Array.isArray(preview.entries) ? preview.entries : [];
    const terminalMessages = {
        LOADING: "Получение списка на приставке… Управление плеером доступно.",
        COMPLETE: "Список получен. Выберите видео и порядок.",
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
    if(byId("playlistGroupName").dataset.previewId !== preview.previewId) {
        byId("playlistGroupName").value=preview.title || "Плейлист";byId("playlistGroupName").dataset.previewId=preview.previewId;
    }
    const reported = preview.reportedCount == null ? "неизвестно" : String(preview.reportedCount);
    const selectableCount = entries.filter(entry => entry.selectable).length;
    const unknownCount=entries.filter(entry=>entry.availability==="UNKNOWN").length;
    byId("playlistCount").textContent = `${preview.provider === "VK" ? "VK" : "YouTube"}: получено ${entries.length} / ${reported} · можно выбрать ${selectableCount} · недоступно ${entries.length-selectableCount}${unknownCount?" · доступность не проверена у "+unknownCount:""}`;
    byId("playlistEntries").replaceChildren();
    byId("playlistSelection").hidden = !previewCanApply();
    byId("playlistConfirm").hidden = !previewCanApply();
    byId("playlistPartialNotice").hidden = preview.status !== "PARTIAL";
    const validIndices = new Set(entries.filter(entry => entry.selectable).map(entry => entry.index));
    selectedIndices = new Set([...selectedIndices].filter(index => validIndices.has(index)));
    entries.forEach((entry, position) => {
        const row = document.createElement("div");
        row.className = "playlist-entry";
        const input = document.createElement("input");
        input.type = "checkbox";
        input.dataset.index = String(entry.index);
        input.checked = selectedIndices.has(entry.index);
        input.disabled = !entry.selectable || !previewCanApply();
        input.setAttribute?.("aria-label", "Выбрать: " + (entry.title || "Видео"));
        const content = document.createElement("span");
        const title = document.createElement("strong");
        title.textContent = `${position + 1}. ${preview.provider === "VK" ? "VK · " : ""}${entry.title || "Без названия"}`;
        const meta = document.createElement("span");
        meta.className = "muted small entry-meta";
        meta.textContent = [entry.videoId, entry.channel, formatDuration(entry.durationSeconds), availabilityNames[entry.availability] || "Доступность не проверена"].filter(Boolean).join(" · ");
        content.append(title, meta);
        if(entry.selectable)appendVideoThumbnail(content,entry.videoId,entry.title);
        row.append(input, content);
        if(typeof appendPreviewOrderControls === "function")appendPreviewOrderControls(row,entry,position);
        byId("playlistEntries").append(row);
    });
    updateSelectionControls();
    if(typeof syncImportFlow === "function")syncImportFlow();
}

function updateSelectionControls() {
    const selected = selectedIndices.size;
    const queued = new Set((state && state.items || []).map(item => item.videoId));
    const newIds = new Set((preview && preview.entries || []).filter(entry => selectedIndices.has(entry.index) && entry.selectable && !queued.has(entry.videoId)).map(entry => entry.videoId));
    byId("playlistSelectedCount").textContent = `Выбрано: ${selected}; новых: ${newIds.size}. Ролики из очереди повторно не добавляются.`;
    const groupMode = Array.isArray(state?.groups);
    const ordered = typeof orderedPreviewEntries === "function" ? orderedPreviewEntries() : preview?.entries || [];
    const ids = [...new Set(ordered.filter(e=>selectedIndices.has(e.index) && e.selectable).map(e=>e.videoId))];
    const sameGroup = groupMode && state.groups.some(g=>JSON.stringify(g.videoIds)===JSON.stringify(ids) && g.source===(preview?.provider === "VK" ? "VK_PLAYLIST" : "YOUTUBE_PLAYLIST") && g.name===byId("playlistGroupName").value.trim());
    byId("playlistApplyButton").textContent = groupMode ? sameGroup ? "Плейлист уже в программе" : "Добавить плейлист в программу" : selected && !newIds.size ? "Уже в очереди" : "Добавить выбранные";
    const previewId = preview ? preview.previewId : "";
    byId("playlistApplyButton").disabled = !previewCanApply() || !selected || (groupMode ? sameGroup : !newIds.size) || busyActions.has("apply") || !state || hasPending("/api/playlists/apply", "previewId", previewId);
    byId("collectionSaveButton").disabled = !previewCanApply() || !selected || busyActions.has("save") || !byId("collectionName").value.trim() || hasPending("/api/collections/save", "previewId", previewId);
    byId("playlistPreviewButton").disabled = previewRequesting || busyActions.has("cancel") || Object.values(pendingCommands).some(command => command.path === "/api/playlists/preview");
    byId("playlistCancelButton").disabled = busyActions.has("cancel") || hasPending("/api/playlists/cancel", "previewId", previewId);
    byId("addButton").disabled = !state || busyActions.has("links-add") || Object.values(pendingCommands).some(command => command.path === "/api/queue/add");
    byId("playNowButton").disabled = busyActions.has("play-now") || Object.values(pendingCommands).some(command => command.path === "/api/queue/play-now");
    if (typeof updateLocalAddControl === "function") updateLocalAddControl();
    document.querySelectorAll("button[data-collection-action]").forEach(button => {
        const id = button.dataset.collectionId;
        const collection = collections.find(item => item.id === id);
        const alreadyQueued = button.dataset.collectionAction === "apply" && collection && (groupMode
            ? state.groups.some(g=>g.source==="COLLECTION" && g.name===collection.name && JSON.stringify(g.videoIds)===JSON.stringify([...new Set(collection.videoIds)]))
            : collection.videoIds.every(videoId => queued.has(videoId)));
        if (button.dataset.collectionAction === "apply") button.textContent = alreadyQueued ? "Подборка уже в программе" : "Добавить как плейлист";
        button.disabled = Boolean(alreadyQueued) || busyActions.has(`collection-${id}`) || hasPending(`/api/collections/${button.dataset.collectionAction}`, "collectionId", id);
    });
    if(typeof syncImportFlow === "function")syncImportFlow();
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
        byId("playlistMessage").textContent = "Вставьте ссылку на плейлист YouTube или VK.";
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
            ? "Предпросмотр отклонён: проверьте ссылку на публичный статический плейлист YouTube/VK. Для VK вход в аккаунт не поддерживается."
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
    const payload={previewId: preview.previewId, indices: [...selectedIndices].sort((a, b) => a - b)};
    if(typeof orderedPreviewEntries === "function")payload.orderedIndices=orderedPreviewEntries().filter(e=>selectedIndices.has(e.index)).map(e=>e.index);
    return payload;
}

async function applyPreview(download = false) {
    if (!state) return;
    const payload = {...selectedPayload(), queueVersion: state.queueVersion, idempotencyKey: commandKey()};
    if(Array.isArray(state.groups))payload.groupName=byId("playlistGroupName").value.trim();
    return withBusy("apply", async () => {
        try {
            const after=download && typeof importDownloadPlan === "function" ? importDownloadPlan() : null;
            await perform(`apply-preview-${commandKey()}`, "/api/playlists/apply", payload, after);
            if(!download && typeof showFamilyPage === "function")showFamilyPage("library");
        } catch (error) {
            byId("playlistMessage").textContent = isQueueVersionConflict(error)
                ? "Очередь изменилась. Проверьте актуальную очередь и нажмите «Добавить выбранные» снова для нового подтверждения."
                : error.playlistSaved ? `Плейлист сохранён, но загрузка не началась: ${apiErrorText(error)}`
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
            row.textContent = (collection.titles?.[id] || id)+" · "+formatDuration(collection.durations?.[id]);
            appendVideoThumbnail(row,id,collection.titles?.[id]);
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
byId("playlistPreviewButton").addEventListener("click", () => run(typeof beginFamilyImport === "function" ? beginFamilyImport : startPreview));
byId("playlistCancelButton").addEventListener("click", () => run(cancelPreview));
byId("playlistApplyButton").addEventListener("click", () => run(applyPreview));
byId("collectionSaveButton").addEventListener("click", () => run(saveCollection));
byId("collectionsRefreshButton").addEventListener("click", () => run(refreshCollections));
byId("collectionName").addEventListener("input", updateSelectionControls);
byId("playlistGroupName").addEventListener("input",updateSelectionControls);
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
    if (button && !button.dataset.action.startsWith("draft-")) run(() => queueAction(button.dataset.action, button.dataset.id));
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
    if (device && !document.hidden) { refreshState(); if (typeof refreshLocalMedia === "function") refreshLocalMedia(); }
}, 2000);
