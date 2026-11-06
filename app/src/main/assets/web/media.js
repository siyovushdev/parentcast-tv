"use strict";
let localFiles = [];
let localFilesViewKey = "";
const selectedLocal = new Set();
let localRefreshing = false;
let localTransfer = null;
let uploadCancelled = false;
let uploadJobId = null;
let usbItems = [];
const selectedUsb = new Set();
let usbFolders = [{key:"root",name:"Выбранная папка"}];
let queueOrder = null;
let draggedQueueId = null;

function mediaError(code) {
    return ({STORAGE_ERROR:"Ошибка хранилища. Проверьте свободное место и повторите.",UPLOAD_SIZE_MISMATCH:"Исходный файл изменился во время копирования. Выберите его заново.",NO_SPACE:"Недостаточно свободного места. Удалите ненужные медиа и повторите.",UNSUPPORTED_MEDIA:"Файл повреждён или этот контейнер не поддерживается.",UNSUPPORTED_CODEC:"Приставка не поддерживает кодек этого файла.",UPLOAD_INCOMPLETE:"Передача оборвалась. Выберите файл и повторите копирование.",IMPORT_FAILED:"Не удалось скопировать файл. Проверьте связь и повторите.",IMPORT_INTERRUPTED:"Копирование было прервано. Повторно выберите исходный файл.",USB_REMOVED_OR_UNAVAILABLE:"Флешка отключена или недоступна. Подключите её и повторно выберите файлы.",IMPORT_CANCELLED:"Копирование отменено."})[code] || code || "Неизвестная ошибка";
}
function mediaSize(n) { return n > 0 ? (n/1048576).toFixed(1)+" МиБ" : "размер неизвестен"; }
async function refreshLocalMedia() {
    if(!device || localRefreshing) return;
    localRefreshing=true;
    try {
        const data=await api("/api/local","GET");localFiles=data.files || [];
        const valid=new Set(localFiles.map(e=>e.videoId));for(const id of selectedLocal)if(!valid.has(id))selectedLocal.delete(id);
        const filesKey=JSON.stringify(localFiles);
        if(filesKey!==localFilesViewKey) {
        localFilesViewKey=filesKey;
        const container=byId("localLibrary");container.replaceChildren();
        if(!localFiles.length) { const text=document.createElement("p");text.textContent="Своих видео пока нет. Скопируйте первый файл выше.";container.append(text); }
        for(const file of localFiles) {
            const row=document.createElement("div");row.className="media-choice";
            const box=document.createElement("input");box.type="checkbox";box.checked=selectedLocal.has(file.videoId);box.dataset.localId=file.videoId;
            box.setAttribute("aria-label","Выбрать: "+file.title);
            const label=document.createElement("span");label.textContent=`${file.title} · ${formatDuration(file.durationSeconds)} · ${mediaSize(file.bytes)} · ${file.available === false ? "носитель недоступен — подключите карту" : "готово офлайн"}`;
            const del=document.createElement("button");del.className="secondary";del.textContent="Удалить файл";del.dataset.localDelete=file.videoId;del.disabled=file.available===false;
            appendVideoThumbnail(label,file.videoId,file.title);
            row.append(box,label,del);container.append(row);
        }
        }
        const jobs=byId("localImportJobs");jobs.replaceChildren();
        for(const job of (data.jobs || []).filter(j=>j.status!=="READY" && j.status!=="CANCELLED").slice(-10)) {
            const row=document.createElement("div");row.className="import-job";
            const label=document.createElement("p");const names={WAITING:"Ожидает передачи из браузера",QUEUED:"Ожидает копирования с USB",COPYING:"Копируем на приставку",VERIFYING:"Проверяем файл",ERROR:"Ошибка"};
            label.textContent=`${job.name} — ${names[job.status] || job.status}; ${mediaSize(job.bytes)} / ${mediaSize(job.size)}${job.error?"; "+mediaError(job.error):""}`;
            row.append(label);
            if(job.status!=="ERROR") {
                const progress=document.createElement("progress");progress.max=100;progress.value=Math.min(99,Math.floor(100*job.bytes/job.size));row.append(progress);
                const cancel=document.createElement("button");cancel.textContent="Отменить";cancel.className="secondary";cancel.dataset.importCancel=job.id;row.append(cancel);
            }
            jobs.append(row);
        }
        updateLocalAddControl();
    } catch(error) { byId("localMessage").textContent=apiErrorText(error); }
    finally { localRefreshing=false; }
}
function updateLocalAddControl() {
    const queued = new Set((state && state.items || []).map(item => item.videoId));
    const hasNew = [...selectedLocal].some(id => !queued.has(id));
    byId("localAddSelected").textContent = selectedLocal.size && !hasNew ? "Уже в программе" : "Добавить выбранные в программу";
    byId("localAddSelected").disabled = !state || !hasNew || busyActions.has("local-add") || Object.values(pendingCommands).some(command => command.path === "/api/local/add");
}
async function addLocalSelected() {
    if(!state || !selectedLocal.size)return;
    await withBusy("local-add",async()=> {
        const ids=localFiles.filter(e=>selectedLocal.has(e.videoId)).map(e=>e.videoId);
        try {
            const result=await perform("local-add-"+commandKey(),"/api/local/add",{videoIds:ids,queueVersion:state.queueVersion,idempotencyKey:commandKey()});
            byId("localMessage").textContent=queueAdditionMessage(result);
        } catch(error) { byId("localMessage").textContent=apiErrorText(error); }
    });
}
async function deleteLocal(id) {
    if(!state || !confirm("Удалить своё видео с приставки? Исходный файл на компьютере/флешке останется. Если ролик текущий, он остановится. Запись в программе останется и потребует удаления или нового выбора."))return;
    try {
        await perform("local-delete-"+commandKey(),"/api/local/delete",{videoId:id,queueVersion:state.queueVersion,stopPlayback:true,idempotencyKey:commandKey()});
        await refreshLocalMedia();
    } catch(error) { byId("localMessage").textContent=apiErrorText(error); }
}
function transferFile(file,job,index,count) {
    return new Promise((resolve,reject)=> {
        const xhr=new XMLHttpRequest();localTransfer=xhr;
        xhr.open("POST","/api/local/upload/"+encodeURIComponent(job.id));
        xhr.setRequestHeader("Content-Type","application/octet-stream");xhr.setRequestHeader("X-ParentCast-Client","m1");xhr.setRequestHeader("Authorization","Bearer "+device.token);
        xhr.upload.onprogress=event=> {
            const p=event.lengthComputable?Math.min(99,Math.floor(event.loaded*100/event.total)):0;
            byId("localUploadProgress").value=p;byId("localUploadMessage").textContent=`${index}/${count}: ${file.name} — передача ${p}%. Затем проверим файл.`;
        };
        xhr.onload=()=> {
            let data;try { data=JSON.parse(xhr.responseText); }catch(_) { data={}; }
            if(xhr.status>=200&&xhr.status<300&&data.status==="READY")resolve(data);
            else reject(new Error(mediaError(data.error)||`HTTP ${xhr.status}`));
        };
        xhr.onerror=()=>reject(new Error("Связь прервалась. Файл не объявлен готовым. Повторите копирование."));
        xhr.onabort=()=>reject(new Error("Копирование отменено."));
        xhr.send(file);
    });
}
async function uploadLocalFiles() {
    const files=Array.from(byId("localFilesInput").files || []);
    if(!files.length || localTransfer)return;
    if(files.length>20 || files.some(f=>f.size<=0 || f.size>53687091200)) { byId("localUploadMessage").textContent="Выберите от 1 до 20 видео, каждое до 50 ГиБ и в пределах свободного места.";return; }
    uploadCancelled=false;byId("uploadLocalButton").disabled=true;byId("cancelUploadButton").hidden=false;byId("localUploadProgress").hidden=false;
    try {
        for(let i=0;i<files.length;i++) {
            if(uploadCancelled)break;
            const file=files[i];byId("localUploadMessage").textContent=`${i+1}/${files.length}: ${file.name} — начинаем копирование`;
            const job=await perform("local-start-"+commandKey(),"/api/local/start",{name:file.name.slice(0,200),size:file.size,idempotencyKey:commandKey()});uploadJobId=job.id;
            if(uploadCancelled) { await cancelImport(job.id);break; }
            await transferFile(file,job,i+1,files.length);localTransfer=null;uploadJobId=null;
            byId("localUploadProgress").value=100;await refreshLocalMedia();
        }
        byId("localUploadMessage").textContent=uploadCancelled?"Копирование отменено.":"Видео проверены и сохранены на приставке. Отметьте нужные и добавьте в программу.";
        if(!uploadCancelled)byId("localFilesInput").value="";
    } catch(error) { byId("localUploadMessage").textContent=error.message; }
    finally { localTransfer=null;uploadJobId=null;byId("uploadLocalButton").disabled=false;byId("cancelUploadButton").hidden=true;await refreshLocalMedia(); }
}
async function cancelImport(id) {
    try { await perform("import-cancel-"+commandKey(),"/api/local/cancel",{jobId:id,idempotencyKey:commandKey()});await refreshLocalMedia(); }
    catch(error) { byId("localMessage").textContent=apiErrorText(error); }
}
async function refreshUsb(reset=false) {
    if(reset)usbFolders=[{key:"root",name:"Выбранная папка"}];
    const folder=usbFolders[usbFolders.length-1];selectedUsb.clear();byId("usbImportButton").disabled=true;
    try {
        const data=await api(folder.key==="root"?"/api/usb":"/api/usb/"+encodeURIComponent(folder.key),"GET");usbItems=data.entries || [];
        const container=byId("usbFiles");container.replaceChildren();
        byId("usbMessage").textContent=usbFolders.map(e=>e.name).join(" / ")+(usbItems.length?"":" — видео не найдены");
        for(const item of usbItems) {
            const row=document.createElement("label");row.className="media-choice";
            if(item.folder) {
                const button=document.createElement("button");button.className="secondary";button.textContent="📁 "+item.name;button.dataset.usbFolder=item.key;row.append(button);
            } else {
                const box=document.createElement("input");box.type="checkbox";box.dataset.usbKey=item.key;box.disabled=item.size<=0 || item.size>53687091200;
                const label=document.createElement("span");label.textContent=item.name+" · "+mediaSize(item.size);row.append(box,label);
            }
            container.append(row);
        }
    } catch(error) { byId("usbMessage").textContent=apiErrorText(error);byId("usbFiles").replaceChildren(); }
    byId("usbBackButton").hidden=usbFolders.length<=1;
}
async function importUsbSelected() {
    if(!selectedUsb.size)return;
    byId("usbImportButton").disabled=true;
    try {
        await perform("usb-import-"+commandKey(),"/api/usb/import",{keys:Array.from(selectedUsb),idempotencyKey:commandKey()});
        selectedUsb.clear();byId("usbMessage").textContent="Копируем выбранные видео. Не вынимайте флешку до завершения проверки.";await refreshLocalMedia();
    } catch(error) { byId("usbMessage").textContent=apiErrorText(error); }
}

function startQueueOrder(groupId) {
    if(!state)return;
    const group=typeof groupId === "string" ? (state.groups || []).find(g=>g.id === groupId) : null;
    if(group) {
        const items=group.videoIds.map(id=>state.items.find(e=>e.videoId === id)).filter(Boolean);
        queueOrder={groupId:group.id,groupName:group.name,version:state.queueVersion,currentIndex:-1,items,ids:items.map(e=>e.queueItemId),currentItemId:state.items[state.currentIndex]?.queueItemId,renderKey:null};
    } else queueOrder={version:state.queueVersion,currentIndex:state.currentIndex,items:state.items.map(e=>({...e})),ids:state.items.slice(state.currentIndex+1).map(e=>e.queueItemId),renderKey:null};
    byId("queueOrderTools").hidden=false;byId("editQueueOrder").hidden=true;renderQueue();
}
function moveDraft(id,position) {
    if(!queueOrder)return;
    const from=queueOrder.ids.indexOf(id);if(from<0)return;
    const to=Math.max(0,Math.min(queueOrder.ids.length-1,position));queueOrder.ids.splice(to,0,queueOrder.ids.splice(from,1)[0]);renderQueue();
}
function episodeNumber(title) {
    const match=String(title || "").match(/(?:[#№]\s*|(?:серия|выпуск|урок|episode|lesson)\s*[:#№.\-]?\s*)(\d{1,6})(?!\d)/iu);
    return match ? Number(match[1]) : null;
}
function sortDraft() {
    if(!state||!queueOrder)return;
    const mode=byId("queueSortMode").value;const items=new Map(state.items.map(e=>[e.queueItemId,e]));
    const ready=new Set([...(state.prepared?.files || []).map(e=>e.videoId),...(state.prepared?.entries || []).filter(e=>e.status==="READY").map(e=>e.videoId)]);
    const title=id=>items.get(id)?.title || items.get(id)?.videoId || "";
    if(mode==="reverse")queueOrder.ids.reverse();
    else if(mode==="episode")queueOrder.ids.sort((a,b)=> {
        const first=episodeNumber(items.get(a)?.title),second=episodeNumber(items.get(b)?.title);
        return first===null ? (second===null ? 0 : 1) : second===null ? -1 : first-second;
    });
    else queueOrder.ids.sort((a,b)=>mode==="ready"?Number(items.get(b)?.ready===true || (items.get(b)?.ready===undefined && Boolean(items.get(b)?.local)) || ready.has(items.get(b)?.videoId))-Number(items.get(a)?.ready===true || (items.get(a)?.ready===undefined && Boolean(items.get(a)?.local)) || ready.has(items.get(a)?.videoId)):title(a).localeCompare(title(b),"ru",{numeric:true})*(mode==="title-desc"?-1:1));
    renderQueue();
}
function renderQueueEditor() {
    if(!queueOrder || !state)return false;
    const stale=queueOrder.version!==state.queueVersion;
    byId("queueOrderMessage").textContent=(queueOrder.groupId?`Плейлист «${queueOrder.groupName}». `:"")+(stale?"Программа изменилась. Отмените редактирование и проверьте новый список.":"Изменения ещё не сохранены.");
    byId("queueOrderApply").disabled=stale||busyActions.has("reorder");
    const key=JSON.stringify([queueOrder.ids,stale]);
    if(queueOrder.renderKey===key)return true;
    queueOrder.renderKey=key;
    const container=byId("queue");container.replaceChildren();
    const map=new Map(queueOrder.items.map(e=>[e.queueItemId,e]));
    const order=queueOrder.items.slice(0,queueOrder.currentIndex+1).map(e=>e.queueItemId).concat(queueOrder.ids);
    order.forEach((id,index)=> {
        const item=map.get(id);if(!item)return;
        const row=document.createElement("div");row.className="queue-item";
        const title=document.createElement("div");title.textContent=`${index+1}. ${item.title||item.videoId} · ${formatDuration(item.durationSeconds)}${(queueOrder.groupId ? id===queueOrder.currentItemId : index===queueOrder.currentIndex)?" — текущий":""}`;appendVideoThumbnail(title,item.videoId,item.title);row.append(title);
        const pendingIndex=queueOrder.ids.indexOf(id);
        if(pendingIndex>=0) {
            row.draggable=!stale;row.dataset.orderId=id;
            const controls=document.createElement("div");controls.className="controls";
            const up=queueButton("↑","draft-up",id);const down=queueButton("↓","draft-down",id);
            const input=document.createElement("input");input.type="number";input.min=1;input.max=queueOrder.ids.length;input.value=pendingIndex+1;input.className="sort-position";input.dataset.orderPosition=id;input.setAttribute("aria-label","Позиция ожидающего ролика");
            const move=queueButton("На место","draft-position",id);controls.append(up,down,input,move);row.append(controls);
        }
        container.append(row);
    });
    return true;
}
async function applyQueueOrder() {
    if(!queueOrder || !state || queueOrder.version!==state.queueVersion)return;
    await withBusy("reorder",async()=> {
        try {
            const payload={queueVersion:queueOrder.version,idempotencyKey:commandKey()};
            if(queueOrder.groupId) {payload.groupId=queueOrder.groupId;payload.videoIds=queueOrder.ids.map(id=>queueOrder.items.find(e=>e.queueItemId===id).videoId);}
            else payload.queueItemIds=queueOrder.ids.slice();
            await perform("reorder-"+commandKey(),queueOrder.groupId?"/api/queue/group-order":"/api/queue/reorder",payload);
            cancelQueueOrder();byId("queueOrderMessage").textContent="Новый порядок сохранён. Воспроизведение не изменилось.";
        } catch(error) { byId("queueOrderMessage").textContent=apiErrorText(error); }
    });
}
function cancelQueueOrder() { queueOrder=null;if(typeof programmeViewKey!=="undefined")programmeViewKey=null;byId("queueOrderTools").hidden=true;byId("editQueueOrder").hidden=false;renderQueue(); }

byId("localRefreshButton").addEventListener("click",()=>run(refreshLocalMedia));
byId("uploadLocalButton").addEventListener("click",()=>run(uploadLocalFiles));
byId("cancelUploadButton").addEventListener("click",()=> { uploadCancelled=true;localTransfer?.abort();if(uploadJobId)run(()=>cancelImport(uploadJobId)); });
byId("localLibrary").addEventListener("change",event=> { const box=event.target.closest("input[data-local-id]");if(box){if(box.checked)selectedLocal.add(box.dataset.localId);else selectedLocal.delete(box.dataset.localId);updateLocalAddControl();} });
byId("localLibrary").addEventListener("click",event=> { const button=event.target.closest("button[data-local-delete]");if(button)run(()=>deleteLocal(button.dataset.localDelete)); });
byId("localSelectAll").addEventListener("click",()=> {localFiles.forEach(e=>selectedLocal.add(e.videoId));localFilesViewKey="";run(refreshLocalMedia);});
byId("localSelectNone").addEventListener("click",()=> {selectedLocal.clear();localFilesViewKey="";run(refreshLocalMedia);});
byId("localAddSelected").addEventListener("click",()=>run(addLocalSelected));
byId("localImportJobs").addEventListener("click",event=> {const button=event.target.closest("button[data-import-cancel]");if(button)run(()=>cancelImport(button.dataset.importCancel));});
byId("usbRefreshButton").addEventListener("click",()=>run(()=>refreshUsb(true)));
byId("usbBackButton").addEventListener("click",()=> {if(usbFolders.length>1)usbFolders.pop();run(()=>refreshUsb());});
byId("usbFiles").addEventListener("change",event=> {const box=event.target.closest("input[data-usb-key]");if(box){if(box.checked)selectedUsb.add(box.dataset.usbKey);else selectedUsb.delete(box.dataset.usbKey);byId("usbImportButton").disabled=!selectedUsb.size;}});
byId("usbFiles").addEventListener("click",event=> {const button=event.target.closest("button[data-usb-folder]");if(button){const item=usbItems.find(e=>e.key===button.dataset.usbFolder);if(item){usbFolders.push({key:item.key,name:item.name});run(()=>refreshUsb());}}});
byId("usbImportButton").addEventListener("click",()=>run(importUsbSelected));
byId("editQueueOrder").addEventListener("click",startQueueOrder);
byId("queueOrderCancel").addEventListener("click",cancelQueueOrder);
byId("queueSortButton").addEventListener("click",sortDraft);
byId("queueOrderApply").addEventListener("click",()=>run(applyQueueOrder));
byId("queue").addEventListener("click",event=> {
    const button=event.target.closest("button[data-action]");if(!button||!queueOrder)return;
    const id=button.dataset.id;const from=queueOrder.ids.indexOf(id);
    if(button.dataset.action==="draft-up")moveDraft(id,from-1);
    else if(button.dataset.action==="draft-down")moveDraft(id,from+1);
    else if(button.dataset.action==="draft-position") {const input=button.parentElement.querySelector("input[data-order-position]");const position=Number(input?.value);if(Number.isInteger(position))moveDraft(id,position-1);}
});
byId("queue").addEventListener("dragstart",event=> {const row=event.target.closest("[data-order-id]");if(row){draggedQueueId=row.dataset.orderId;event.dataTransfer?.setData("text/plain",draggedQueueId);}});
byId("queue").addEventListener("dragover",event=> {if(queueOrder)event.preventDefault();});
byId("queue").addEventListener("drop",event=> {event.preventDefault();const row=event.target.closest("[data-order-id]");if(row&&draggedQueueId&&queueOrder)moveDraft(draggedQueueId,queueOrder.ids.indexOf(row.dataset.orderId));draggedQueueId=null;});
if(device)run(refreshLocalMedia);
