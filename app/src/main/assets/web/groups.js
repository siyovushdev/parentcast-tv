// Playlist identity/order is server state; folding a card is only a browser preference.
let programmeViewKey = null;
let groupRequestBusy = false;
const groupViewsKey = "parentcast.playlist-views.v1";
const savedGroupViews = readJson(groupViewsKey, {});
const groupViews = savedGroupViews && typeof savedGroupViews === "object" && !Array.isArray(savedGroupViews) ? savedGroupViews : {};
const groupSourceNames = {YOUTUBE_PLAYLIST: "YouTube · плейлист", VK_PLAYLIST: "VK · плейлист", COLLECTION: "Сохранённая подборка"};

function videoReady(item) {
    return item.ready === true || (state.prepared?.files || []).some(e => e.videoId === item.videoId) ||
        (state.prepared?.entries || []).some(e => e.videoId === item.videoId && e.status === "READY");
}
function videoCanWatch(item) {
    return videoReady(item) || (!state.prepared?.enabled && !item.local && !item.videoId.startsWith("vk:"));
}
function unavailableWatchLabel(item) {
    const entry = (state.prepared?.entries || []).find(e => e.videoId === item.videoId);
    const status = entry?.status || item.preparationStatus;
    if(status === "DOWNLOADING") return entry?.percent == null ? "Загружается…" : `Загружается · ${entry.percent}%`;
    if(status === "RETRY_WAIT") return "Ждём сеть";
    if(status === "ERROR") return "Ошибка подготовки";
    return state.prepared?.enabled ? "Ожидает загрузки" : "Сначала подготовьте";
}
function playlistRow(item, index, groupId) {
    const current = state.items[state.currentIndex]?.queueItemId === item.queueItemId && ((state.activeGroupId || null) === (groupId || null));
    const row = document.createElement("div");row.className = `queue-item${current ? " current" : ""}`;
    const text = document.createElement("div");
    const title = document.createElement("div");title.textContent = `${index + 1}. ${item.title || "Название ещё не получено"}${current ? " — текущий" : ""}`;
    const meta = document.createElement("small");meta.className = "muted video-id";
    meta.textContent = `${item.local ? "Своё видео" : item.videoId.startsWith("vk:") ? "VK" : "YouTube"} · ${formatDuration(item.durationSeconds)} · ${videoReady(item) ? "Готово офлайн" : unavailableWatchLabel(item)}${item.title ? "" : " · " + item.videoId}`;
    text.append(title,meta);appendVideoThumbnail(text,item.videoId,item.title);row.append(text);
    const watch = document.createElement("button");watch.className = "secondary";
    watch.dataset.groupAction = "watch-video";watch.dataset.groupId = groupId || "";watch.dataset.itemId = item.queueItemId;
    watch.textContent = videoCanWatch(item) ? (current && state.positionMs>0 && !state.finished ? `Продолжить с ${resumeTime(state.positionMs)}` : "Смотреть") : unavailableWatchLabel(item);
    watch.disabled = groupRequestBusy || !videoCanWatch(item);row.append(watch);
    return row;
}
function groupButton(text, action, id) {
    const b = document.createElement("button");b.className = "secondary";b.textContent = text;
    b.dataset.groupAction = action;b.dataset.groupId = id;b.disabled = groupRequestBusy;return b;
}
function renderProgrammeGroups() {
    const groups = state.groups || [];
    byId("editQueueOrder").hidden = groups.length > 0;
    byId("clearPendingButton").hidden = groups.length > 0;
    const key = JSON.stringify([groups,state.activeGroupId,state.currentIndex,state.prepared?.enabled,groupRequestBusy,
        state.items.map(e => [e.queueItemId,e.title,e.ready,e.preparationStatus,e.durationSeconds]),
        (state.prepared?.entries || []).map(e=>[e.videoId,e.status])]);
    if(programmeViewKey === key) {
        byId("queue").querySelectorAll('button[data-group-action="watch-video"]').forEach(button=>{
            const item=state.items.find(e=>e.queueItemId===button.dataset.itemId);
            if(item)button.textContent=!videoCanWatch(item) ? unavailableWatchLabel(item) :
                (state.items[state.currentIndex]?.queueItemId===item.queueItemId && ((state.activeGroupId || "") === (button.dataset.groupId || "")) && state.positionMs>0 && !state.finished ? `Продолжить с ${resumeTime(state.positionMs)}` : "Смотреть");
        });return;
    }
    programmeViewKey = key;
    const container = byId("queue");container.replaceChildren();
    const byVideo = new Map();state.items.forEach(e=>{if(!byVideo.has(e.videoId))byVideo.set(e.videoId,e);});
    const assigned = new Set(groups.flatMap(g=>g.videoIds));
    groups.forEach((group,index) => {
        const members = group.videoIds.map(id => byVideo.get(id)).filter(Boolean);
        const card = document.createElement("details");card.className = "programme-group";card.dataset.programmeGroup = group.id;
        card.open = typeof groupViews[group.id] === "boolean" ? groupViews[group.id] : groups.length === 1 || state.activeGroupId === group.id;
        card.addEventListener("toggle", () => {groupViews[group.id] = card.open;try {localStorage.setItem(groupViewsKey,JSON.stringify(groupViews));} catch(_) {}});
        const summary = document.createElement("summary");
        const name = document.createElement("strong");name.textContent = `${index+1}. ${group.name}${state.activeGroupId === group.id ? " — выбран" : ""}`;
        const info = document.createElement("span");info.className = "muted small";
        const providers=[...new Set(members.map(e=>e.local?"Свои файлы":e.videoId.startsWith("vk:")?"VK":"YouTube"))].join(" / ");
        info.textContent = `${groupSourceNames[group.source] || "Плейлист"}${group.source==="COLLECTION"?" · "+providers:""} · ${members.length} видео · готово ${members.filter(videoReady).length}/${members.length}`;
        summary.append(name,info);card.append(summary);
        const controls = document.createElement("div");controls.className = "controls";
        const play = groupButton("Смотреть плейлист сначала","play",group.id);play.disabled ||= !members[0] || !videoCanWatch(members[0]);if(members[0] && !videoCanWatch(members[0]))play.textContent="Сначала подготовьте первый ролик";
        const prepare = groupButton("Подготовить плейлист","prepare",group.id);
        const edit = groupButton("Изменить порядок видео","edit",group.id);
        const up = groupButton("↑ Плейлист","up",group.id);up.disabled ||= index === 0;
        const down = groupButton("↓ Плейлист","down",group.id);down.disabled ||= index === groups.length-1;
        const remove=groupButton("Удалить плейлист из программы","remove",group.id);
        controls.append(play,prepare,edit,up,down,remove);card.append(controls);
        const note = document.createElement("p");note.className = "muted small";
        note.textContent = "Видео идут в указанном порядке. В конце этого плейлиста просмотр остановится. Общие с другими плейлистами ролики используют один файл. Для просмотра без ожиданий дождитесь готовности всего плейлиста.";
        card.append(note);members.forEach((item,n) => card.append(playlistRow(item,n,group.id)));container.append(card);
    });
    for(const [provider,label] of [["YT","YouTube"],["VK","VK"],["LOCAL","Свои файлы"]]) {
        const items = state.items.filter(e => !assigned.has(e.videoId) && (e.local ? "LOCAL" : e.videoId.startsWith("vk:") ? "VK" : "YT") === provider);
        if(!items.length)continue;
        const card = document.createElement("details");card.className = "programme-group";card.open = !groups.length;
        const summary = document.createElement("summary");summary.textContent = `Видео без плейлиста · ${label} · ${items.length}`;card.append(summary);
        const note = document.createElement("p");note.className = "muted small";note.textContent = "Ранее добавленные видео сохраняются здесь. Чтобы восстановить именованный плейлист, добавьте его из сохранённой подборки: повторов видео не будет.";card.append(note);
        items.forEach((item,n)=>card.append(playlistRow(item,n,null)));container.append(card);
    }
    if(!state.items.length) {const p=document.createElement("p");p.textContent="Программа пуста. Добавьте плейлист или отдельные видео.";container.append(p);}
}
async function programmeAction(button) {
    if(groupRequestBusy || !state)return;
    const action=button.dataset.groupAction,id=button.dataset.groupId;
    const group=(state.groups || []).find(g=>g.id === id);
    if(action === "remove" && (!group || !confirm(`Удалить плейлист «${group.name}» из программы? Его просмотр остановится. Скачанные файлы, сохранённые подборки и общие видео других плейлистов останутся.`)))return;
    if(action === "edit") {startQueueOrder(id);return;}
    groupRequestBusy=true;programmeViewKey=null;renderQueue();
    try {
        if(action === "remove") {
            await perform("remove-playlist-"+commandKey(),"/api/queue/group-remove",{groupId:id,queueVersion:state.queueVersion,idempotencyKey:commandKey()});
        } else if(action === "prepare") {
            if(!group)return;
            await perform("prepare-playlist-"+commandKey(),"/api/prepared",{action:"ENABLE",groupId:id,queueVersion:state.queueVersion,idempotencyKey:commandKey()});
            byId("queueOrderMessage").textContent=`Готовим плейлист «${group.name}». Текущий просмотр не остановлен.`;
        } else if(action === "play" || action === "watch-video") {
            const item=action === "play" ? state.items.find(e=>e.videoId === group?.videoIds[0]) : state.items.find(e=>e.queueItemId === button.dataset.itemId);
            if(!item || !videoCanWatch(item))return;
            if(action === "watch-video" && state.items[state.currentIndex]?.queueItemId===item.queueItemId &&
                ((state.activeGroupId || "") === (id || "")) && state.positionMs>0 && !state.finished) {
                await perform("resume-video-"+commandKey(),"/api/playback",{action:"PLAY",idempotencyKey:commandKey()});return;
            }
            await perform("watch-playlist-"+commandKey(),"/api/queue/select",{queueItemId:item.queueItemId,groupId:id || undefined,queueVersion:state.queueVersion,positionMs:0,play:true,idempotencyKey:commandKey()});
        } else if(action === "up" || action === "down") {
            const ids=state.groups.map(g=>g.id),index=ids.indexOf(id),to=index+(action === "up"?-1:1);
            if(index<0 || to<0 || to>=ids.length)return;
            ids.splice(to,0,ids.splice(index,1)[0]);
            await perform("order-playlists-"+commandKey(),"/api/queue/group-order",{groupIds:ids,queueVersion:state.queueVersion,idempotencyKey:commandKey()});
        }
    } catch(error) {byId("queueOrderMessage").textContent=apiErrorText(error);}
    finally {groupRequestBusy=false;programmeViewKey=null;renderQueue();}
}
byId("queue").addEventListener("click",event=>{const button=event.target.closest("button[data-group-action]");if(button)run(()=>programmeAction(button));});
if(state)renderQueue();
