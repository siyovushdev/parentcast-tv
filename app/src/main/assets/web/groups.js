// Playlist identity/order is server state; folding a card is only a browser preference.
let programmeViewKey = null;
let groupRequestBusy = false;
const groupViewsKey = "parentcast.playlist-views.v1";
const savedGroupViews = readJson(groupViewsKey, {});
const groupViews = savedGroupViews && typeof savedGroupViews === "object" && !Array.isArray(savedGroupViews) ? savedGroupViews : {};
const groupSourceNames = {YOUTUBE_PLAYLIST: "YouTube · плейлист", VK_PLAYLIST: "VK · плейлист", COLLECTION: "Сохранённая подборка"};

function videoReady(item) {
    if(item.preparationStatus === "STORAGE_UNAVAILABLE" || item.preparationStatus === "STORAGE_BUSY")return false;
    if(item.ready === false)return false;
    return item.ready === true || (state.prepared?.files || []).some(e => e.videoId === item.videoId && e.available!==false) ||
        (state.prepared?.entries || []).some(e => e.videoId === item.videoId && e.status === "READY");
}
function videoCanWatch(item) {
    if(item.preparationStatus === "STORAGE_UNAVAILABLE" || item.preparationStatus === "STORAGE_BUSY")return false;
    if(item.canStream===true)return true;
    return videoReady(item) || (!state.prepared?.enabled && !item.local && !item.videoId.startsWith("vk:"));
}
function unavailableWatchLabel(item) {
    const entry = (state.prepared?.entries || []).find(e => e.videoId === item.videoId);
    const status = entry?.status || item.preparationStatus;
    if(status === "STORAGE_UNAVAILABLE")return "Подключите карту";
    if(status === "STORAGE_BUSY")return "Проверяем хранилище";
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
    const resume=typeof videoResumeMs==="function"?videoResumeMs(item):current&&!state.finished?state.positionMs:0;
    watch.textContent = videoCanWatch(item) ? (resume>0 ? `Продолжить с ${resumeTime(resume)}` : "Смотреть") : unavailableWatchLabel(item);
    watch.disabled = groupRequestBusy || !videoCanWatch(item);row.append(watch);
    if(typeof enhanceVideoRow==="function")enhanceVideoRow(row,item,groupId);
    return row;
}
function groupButton(text, action, id) {
    const b = document.createElement("button");b.className = "secondary";b.textContent = text;
    b.dataset.groupAction = action;b.dataset.groupId = id;b.disabled = groupRequestBusy;return b;
}
let libraryGroupId = null;
function groupElement(tag,className,text) {const node=document.createElement(tag);if(className)node.className=className;if(text!==undefined)node.textContent=text;return node;}
function libraryMembers(group) {return group.videoIds.map(id=>state.items.find(e=>e.videoId===id)).filter(Boolean);}
function programmeCatalogue() {
    const groups=state.programme?.groups||state.groups||[],assigned=new Set(groups.flatMap(g=>g.videoIds));
    const ungrouped=state.programme?state.programme.looseVideoIds.map(id=>state.items.find(e=>e.videoId===id)).filter(Boolean):state.items.filter(e=>!assigned.has(e.videoId));
    return [...groups,...(ungrouped.length?[{id:"ungrouped",name:"Отдельные видео",source:"LOOSE",videoIds:ungrouped.map(e=>e.videoId)}]:[])];
}
function groupCover(group,members) {const box=groupElement("div","library-cover");if(group.coverImageId&&typeof appendCustomCover==="function")appendCustomCover(box,group.coverImageId,group.name);else if(group.coverVideoId||members[0])appendVideoThumbnail(box,group.coverVideoId||members[0].videoId,group.name);else box.textContent="Плейлист";return box;}
function renderProgrammeGroups() {
    const groups=programmeCatalogue();
    const query=(byId("librarySearch")?.value||"").trim().toLocaleLowerCase("ru");
    const readyOnly=byId("libraryFilter")?.value==="ready";
    const selected=groups.find(g=>g.id===libraryGroupId);
    if(libraryGroupId&&!selected)libraryGroupId=null;
    byId("editQueueOrder").hidden=!!selected&&selected.id!=="ungrouped" || (!selected&&(state.groups||[]).length>0);
    byId("clearPendingButton").hidden=(state.groups||[]).length>0;
    if(byId("libraryBack"))byId("libraryBack").hidden=!selected;
    const key=JSON.stringify([libraryGroupId,query,readyOnly,state.groups,state.programme?.groups,state.programme?.videos?.map(v=>[v.videoId,v.favorite,v.status]),state.programme?.looseVideoIds,state.activeGroupId,state.currentIndex,state.prepared?.enabled,groupRequestBusy,
        state.items.map(e=>[e.queueItemId,e.title,e.ready,e.preparationStatus,e.durationSeconds]),(state.prepared?.entries||[]).map(e=>[e.videoId,e.status])]);
    if(programmeViewKey===key) {
        byId("queue").querySelectorAll('button[data-group-action="watch-video"]').forEach(button=>{
            const item=state.items.find(e=>e.queueItemId===button.dataset.itemId);
            if(item)button.textContent=!videoCanWatch(item)?unavailableWatchLabel(item):
                (state.items[state.currentIndex]?.queueItemId===item.queueItemId&&((state.activeGroupId||"")===(button.dataset.groupId||""))&&state.positionMs>0&&!state.finished?`Продолжить с ${resumeTime(state.positionMs)}`:"Смотреть");
        });if(typeof updateExperienceRows==="function")updateExperienceRows();return;
    }
    programmeViewKey=key;
    const container=byId("queue");container.replaceChildren();
    if(!selected) {
        const grid=groupElement("div","library-grid");
        for(const group of groups) {
            const members=libraryMembers(group),ready=members.filter(videoReady).length;
            if(byId("libraryFilter")?.value==="favorite"&&!group.favorite&&!members.some(item=>state.programme?.videos?.find(v=>v.videoId===item.videoId)?.favorite))continue;
            if(readyOnly&&ready!==members.length)continue;
            if(query&&!group.name.toLocaleLowerCase("ru").includes(query)&&!members.some(e=>(e.title||"").toLocaleLowerCase("ru").includes(query)))continue;
            const card=groupElement("button","library-card");card.type="button";card.dataset.openGroup=group.id;
            const duration=members.every(e=>Number.isFinite(e.durationSeconds))?formatDuration(members.reduce((sum,e)=>sum+e.durationSeconds,0)):"длительность уточняется";
            card.append(groupCover(group,members),groupElement("h3","",`${group.favorite?"★ ":""}${group.name}`),groupElement("p","small muted",`${members.length} видео · ${duration}`));
            const bar=groupElement("div","library-progress"),fill=groupElement("span");fill.style.width=(members.length?100*ready/members.length:0)+"%";bar.append(fill);card.append(bar,groupElement("span","badge",ready===members.length?"Всё скачано":`${ready} из ${members.length} скачано`));grid.append(card);
        }
        container.append(grid);
        if(!grid.children.length)container.append(groupElement("p","empty-library",state.items.length?"Ничего не найдено. Измените поиск или фильтр.":"Здесь появятся ваши плейлисты. Нажмите «Добавить» и выберите видео для детей."));
        if(typeof enhanceLibrary==="function")enhanceLibrary(null,container);
        return;
    }
    const members=libraryMembers(selected),index=(state.groups||[]).findIndex(g=>g.id===selected.id),realGroup=selected.id!=="ungrouped";
    const head=groupElement("div","library-detail-head"),text=groupElement("div");
    text.append(groupElement("h2","",selected.name),groupElement("p","small muted",`${groupSourceNames[selected.source]||"Ваши видео"} · ${members.length} видео · скачано ${members.filter(videoReady).length}`));head.append(groupCover(selected,members),text);container.append(head);
    if(realGroup) {
        const controls=groupElement("div","library-detail-controls");
        const current=state.items[state.currentIndex],active=state.activeGroupId===selected.id&&members.some(e=>e.queueItemId===current?.queueItemId);
        if(active&&!state.finished&&state.positionMs>0){const resume=groupButton("Продолжить с "+resumeTime(state.positionMs),"watch-video",selected.id);resume.dataset.itemId=current.queueItemId;resume.disabled ||= !videoCanWatch(current);controls.append(resume);}
        const play=groupButton("Смотреть сначала","play",selected.id);play.disabled ||= !members[0]||!videoCanWatch(members[0]);controls.append(play,groupButton("Скачать плейлист","prepare",selected.id),groupButton("Изменить порядок","edit",selected.id));
        const more=groupElement("details"),body=groupElement("div","more-actions");more.append(groupElement("summary","","Ещё"),body);
        const up=groupButton("↑ Выше в медиатеке","up",selected.id);up.disabled ||= index<=0;
        const down=groupButton("↓ Ниже в медиатеке","down",selected.id);down.disabled ||= index===(state.groups||[]).length-1;
        body.append(up,down,groupButton("Убрать плейлист из программы","remove",selected.id));controls.append(more);container.append(controls);
    }
    container.append(groupElement("p","muted small","Это сохранённый порядок просмотра. Приоритет скачивания его не меняет. В конце выбранного плейлиста — остановка."));
    members.forEach((item,n)=>{if(!query||selected.name.toLocaleLowerCase("ru").includes(query)||(item.title||"").toLocaleLowerCase("ru").includes(query))container.append(playlistRow(item,n,realGroup?selected.id:null));});
    if(typeof enhanceLibrary==="function")enhanceLibrary(selected,container);
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
            const positionMs=action==="watch-video"&&typeof videoResumeMs==="function"?videoResumeMs(item):0;
            await perform("watch-playlist-"+commandKey(),"/api/queue/select",{queueItemId:item.queueItemId,groupId:id || undefined,queueVersion:state.queueVersion,positionMs,play:true,idempotencyKey:commandKey()});
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

byId("queue").addEventListener("click",event=>{const button=event.target.closest("button[data-open-group]");if(button){libraryGroupId=button.dataset.openGroup;programmeViewKey=null;renderQueue();}});
byId("libraryBack")?.addEventListener("click",()=>{if(queueOrder)cancelQueueOrder();libraryGroupId=null;programmeViewKey=null;renderQueue();});
byId("librarySearch")?.addEventListener("input",()=>{programmeViewKey=null;renderQueue();});
byId("libraryFilter")?.addEventListener("change",()=>{programmeViewKey=null;renderQueue();});
