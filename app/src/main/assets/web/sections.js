"use strict";
// Navigation is a browser preference. Switching pages never sends playback commands.
let familyPage = "library";
let importStep = 1;
let importSingleLink = "";
let previewOrder = [];
let previewOrderId = null;
let previewOrderWasReady = false;
let storageState = null;
let storageRefreshing = false;
let storageBusy = false;
let storageRenderKey = null;

function familyElement(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
}
// Templates below are static application markup. Provider/user strings use textContent.
function familyTemplate(markup) {
    const template = document.createElement("template");template.innerHTML=markup.trim();return template.content;
}
function familyButton(text, action, className="secondary") {
    const button=familyElement("button",className,text);button.type="button";button.dataset.familyAction=action;return button;
}
function initializeFamilyLayout() {
    const control=byId("controlSection");
    document.querySelector("header h1").textContent="ParentCast";
    const oldSections=[...control.querySelectorAll(":scope > section")];
    const cards=new Map(oldSections.map(card=>[card.querySelector("[data-section]")?.dataset.section || "remote",card]));
    control.querySelector(".section-nav")?.remove();control.querySelector(".section-tools")?.remove();
    const nav=familyElement("nav","app-nav");nav.setAttribute("aria-label","Основные разделы");
    for(const [page,symbol,name] of [["remote","▷","Пульт"],["library","▤","Медиатека"],["downloads","↓","Загрузки"],["settings","⚙","Настройки"]]) {
        const b=familyButton("", "page");b.dataset.page=page;b.append(familyElement("span","nav-symbol",symbol),document.createTextNode(name));b.firstChild.setAttribute("aria-hidden","true");nav.append(b);
    }
    const pages=familyElement("div","app-pages");
    for(const [page,title,subtitle] of [["remote","Пульт","Сейчас на телевизоре"],["library","Медиатека","Ваша программа, в вашем порядке"],["downloads","Загрузки","Очередность скачивания не меняет порядок просмотра"],["settings","Настройки","Хранилище и доступ семьи"],["add","Добавить в медиатеку","Выбор и порядок — до подтверждения"]]) {
        const panel=familyElement("section","app-page");panel.dataset.pagePanel=page;panel.hidden=page!=="library";
        const heading=familyElement("div","page-heading"),text=familyElement("div");text.append(familyElement("h1","",title),familyElement("p","muted",subtitle));heading.append(text);
        if(page==="remote" || page==="library")heading.append(familyButton("＋ Добавить","add",""));
        if(page==="add")heading.append(familyButton("Закрыть","close-add"));
        if(page==="settings")heading.append(familyTemplate('<label class="theme-label">Оформление<select id="themeSelect"><option value="system">Как на устройстве</option><option value="light">Светлое</option><option value="dark">Тёмное</option></select></label>'));
        panel.append(heading);pages.append(panel);
    }
    control.prepend(nav,pages);
    const page=id=>pages.querySelector(`[data-page-panel="${id}"]`);
    const unwrap=key=>{
        const card=cards.get(key),details=card.querySelector("details[data-section]");
        if(details){const heading=details.querySelector("summary h2");const body=details.querySelector(".section-body");body.querySelector("[data-section-close]")?.remove();card.replaceChildren(heading,...body.childNodes);}
        return card;
    };
    const fold=key=>{const card=cards.get(key);const details=card.querySelector("details[data-section]");if(details){details.open=false;details.querySelector("[data-section-close]")?.remove();}return card;};
    const remote=cards.get("remote");remote.querySelector("h2").hidden=true;
    page("remote").append(remote,familyTemplate('<section class="card next-video"><p class="eyebrow">Далее по программе</p><h3 id="nextVideoTitle">Ролик не выбран</h3><p id="nextVideoMeta" class="muted small"></p></section>'),fold("play-now"));
    const library=unwrap("queue");library.className="library-programme";library.querySelector("h2").hidden=true;
    const clearControls=library.querySelector(".row");const clearNote=library.querySelector("p");clearNote.remove();
    library.querySelectorAll(":scope > p.muted").forEach(e=>e.remove());
    page("library").append(familyTemplate('<section id="libraryResume" class="card resume-card" hidden><div><p class="eyebrow">Продолжить просмотр</p><h2 id="libraryResumeTitle"></h2><p id="libraryResumeMeta" class="muted small"></p></div><button type="button" data-family-playback="PLAY">Продолжить</button></section><div class="library-toolbar"><input id="librarySearch" type="search" placeholder="Найти плейлист или ролик" aria-label="Найти плейлист или ролик" autocomplete="off"><select id="libraryFilter" aria-label="Фильтр медиатеки"><option value="all">Все плейлисты</option><option value="ready">Полностью скачанные</option></select></div><button id="libraryBack" type="button" class="secondary" hidden>← Все плейлисты</button>'),library,fold("collections"));
    const downloads=unwrap("downloads");downloads.querySelector("h2").hidden=true;
    downloads.querySelectorAll(":scope > p.muted:not([id])").forEach(p=>p.remove());
    downloads.insertBefore(byId("preparationAlert"),byId("preparedSummary"));
    page("downloads").append(downloads);
    const cleanup=familyElement("details","card"),cleanupBody=familyElement("div","detail-body");cleanup.append(familyElement("summary","","Очистка и режим загрузок"),cleanupBody);
    cleanupBody.append(familyElement("h3","","Программа просмотра"),clearNote,clearControls,familyElement("h3","","Скачанные онлайн-видео"),familyElement("p","muted small","Готовые файлы не удаляются автоматически. Удаление файла и удаление из программы — разные действия."));
    const maintenance=familyElement("div","controls");for(const action of ["CLEANUP","RETRY_DELETE","DELETE_ALL","DISABLE"])maintenance.append(document.querySelector(`[data-prepared="${action}"]`));cleanupBody.append(maintenance);
    const storage=familyElement("section","card");storage.append(familyTemplate('<div class="row"><h2>Хранилище видео</h2><button id="storageRefresh" type="button" class="secondary">Обновить</button></div><p id="storageMessage" class="message" role="status"></p><div id="storageVolumes" class="storage-grid"></div><label>Для новых загрузок<select id="storageSelect"><option value="">Получаем хранилища…</option></select></label><div class="controls storage-actions"><button id="storageChoose" type="button">Выбрать хранилище</button><button id="storageMigrate" type="button" class="secondary">Скопировать скачанное сюда</button></div><p class="muted small">Сначала копирование и проверка. Исходные файлы сохраняются до отдельного подтверждения освобождения памяти.</p><div id="storageMigration" hidden><p id="storageMigrationText" role="status"></p><progress id="storageMigrationProgress" max="100"></progress><button id="storageCancel" type="button" class="secondary">Отменить перенос</button></div><button id="storageCleanup" type="button" class="secondary" hidden>Освободить память после проверенного переноса</button>'));
    const diagnostics=familyElement("details","card");diagnostics.append(familyElement("summary","","Диагностика"),byId("updatedAt"),byId("diagnostics"));
    page("settings").append(storage,cleanup,fold("devices"),fold("home"),diagnostics);
    const addition=unwrap("playlist"),local=unwrap("local"),links=fold("add-links");addition.className="import-online";addition.id="onlineImport";local.hidden=true;
    page("add").append(familyTemplate('<div class="source-tabs" aria-label="Откуда добавить"><button type="button" data-add-source="link" aria-pressed="true">YouTube / VK</button><button type="button" data-add-source="local" aria-pressed="false">Свои видео / USB</button></div>'),addition,local);
    addition.querySelector("h2").hidden=true;addition.querySelector(":scope > p.muted")?.remove();
    const source=familyElement("div","card");source.id="importSource";
    const sourceLabel=byId("playlistUrl").closest("label");sourceLabel.firstChild.textContent="Ссылка на видео или плейлист ";source.append(sourceLabel,byId("playlistPreviewButton"),links);
    const oldControls=byId("playlistCancelButton").parentElement;addition.insertBefore(byId("playlistCancelButton"),oldControls);oldControls.remove();
    addition.prepend(familyTemplate('<ol class="import-steps" aria-label="Этапы импорта"><li data-import-step="1">1. Источник</li><li data-import-step="2">2. Видео и порядок</li><li data-import-step="3">3. Сохранение</li></ol>'),source);
    byId("playlistPreviewButton").textContent="Посмотреть список";
    const selection=familyElement("div");selection.id="importSelectionStep";
    selection.append(familyTemplate('<div class="card"><label>Порядок просмотра<select id="playlistOrderMode"><option value="source">Как в источнике</option><option value="episode">По номеру серии / урока: 1 → 2 → 10</option><option value="reverse">Обратный порядок источника</option><option value="manual">Вручную</option></select></label><p id="playlistOrderHint" class="muted small"></p></div>'),byId("playlistSelection"));
    const availNote=byId("playlistPreview").querySelector(":scope > p.small");if(availNote)selection.append(availNote);
    selection.append(byId("playlistEntries"),familyTemplate('<div class="controls"><button id="importReviewButton" type="button" disabled>Далее: сохранение</button><button id="importSourceBack" type="button" class="secondary">Назад к ссылке</button></div>'));
    byId("playlistPreview").insertBefore(selection,byId("playlistConfirm"));
    const confirm=byId("playlistConfirm");confirm.prepend(familyTemplate('<div class="import-review"><label>Куда скачивать<select id="importStorage"><option value="">Получаем хранилища…</option></select></label><p id="importStorageHint" class="muted small"></p><p id="importFinalSummary"></p><details><summary>Окончательный порядок видео</summary><ol id="importFinalOrder" class="import-final-list"></ol></details></div>'));
    const actions=familyElement("div","controls");actions.append(familyTemplate('<button id="playlistDownloadButton" type="button" disabled>Сохранить и скачать</button>'),byId("playlistApplyButton"),familyTemplate('<button id="importSelectionBack" type="button" class="secondary">Назад к выбору</button>'));confirm.insertBefore(actions,byId("collectionName").closest("label"));
    confirm.insertBefore(familyElement("p","notice","Плейлист добавится в программу. Просмотр не запустится."),actions);
    const collectionBox=familyElement("details","card");collectionBox.append(familyElement("summary","","Сохранить подборку для повторного добавления"),byId("collectionName").closest("label"),byId("collectionSaveButton"),byId("collectionSnapshotNote"));confirm.append(collectionBox);
    addition.append(familyTemplate('<section id="singleVideoConfirm" class="card" hidden><h2>Добавить отдельное видео</h2><p id="singleVideoLink" class="muted"></p><p class="muted small">Название и длительность появятся после получения данных. Просмотр не запустится.</p><div class="controls"><button id="singleVideoApply" type="button">Добавить в программу</button><button id="singleVideoBack" type="button" class="secondary">Назад</button></div></section>'));
    local.querySelectorAll(":scope > p.muted").forEach(p=>{if(p.textContent.includes("10 ГиБ"))p.textContent="Поддерживаются MP4/MKV/WebM/3GP с кодеками приставки, без DRM. Размер файла ограничен свободным местом выбранного хранилища; резерв — 1 ГиБ. Исходные файлы не изменяются.";});
    local.prepend(familyTemplate('<p id="localStorageHint" class="notice"></p>'));
    const notice=familyElement("div","command-notice");notice.setAttribute("role","status");notice.setAttribute("aria-live","polite");notice.append(byId("commandMessage"),byId("retryCommandButton"));
    control.prepend(nav,notice,pages);
    control.append(familyTemplate('<aside class="now-bar" aria-label="Сейчас и далее"><button type="button" class="now-description" data-family-action="page" data-page="remote"><span id="nowStatus" class="eyebrow">Сейчас на ТВ</span><strong id="nowTitle">Ролик не выбран</strong><span id="nowNext" class="small muted"></span></button><div class="controls"><button id="nowToggle" type="button" data-family-playback="PLAY" class="secondary">Продолжить</button><button type="button" data-family-playback="STOP" class="secondary">Стоп</button></div></aside>'));
    control.classList.add("family-ready");
    const stored=readJson("parentcast.family-view.v1",{});const theme=["light","dark","system"].includes(stored.theme)?stored.theme:"system";byId("themeSelect").value=theme;setFamilyTheme(theme);
    showFamilyPage(["remote","library","downloads","settings"].includes(stored.page)?stored.page:"library",false);
}

function setFamilyTheme(theme){document.documentElement.dataset.theme=theme;}
function saveFamilyView(){try{writeJson("parentcast.family-view.v1",{page:familyPage,theme:byId("themeSelect").value});}catch(_){}}
function showFamilyPage(page,scroll=true){
    if(!["remote","library","downloads","settings","add"].includes(page))return;
    familyPage=page;document.querySelectorAll("[data-page-panel]").forEach(p=>p.hidden=p.dataset.pagePanel!==page);
    document.querySelectorAll(".app-nav [data-page]").forEach(b=>{if(b.dataset.page===page || (page==="add"&&b.dataset.page==="library"))b.setAttribute("aria-current","page");else b.removeAttribute("aria-current");});
    saveFamilyView();if(scroll)window.scrollTo({top:0,behavior:"instant"});
    if(page==="settings"||page==="add")run(refreshStorage);
}
function showAddSource(source){byId("onlineImport").hidden=source!=="link";byId("localSection").hidden=source!=="local";document.querySelectorAll("[data-add-source]").forEach(b=>b.setAttribute("aria-pressed",String(b.dataset.addSource===source)));}
function nextProgrammeItem(){
    if(!state)return null;
    if(state.programme&&Object.prototype.hasOwnProperty.call(state.programme,"nextVideoId"))
        return state.items.find(item=>item.videoId===state.programme.nextVideoId)||null;
    const item=state.items[state.currentIndex],group=(state.groups||[]).find(g=>g.id===state.activeGroupId);
    if(group){const index=group.videoIds.indexOf(item?.videoId);return index>=0?state.items.find(e=>e.videoId===group.videoIds[index+1])||null:null;}
    return state.items[state.currentIndex+1]||null;
}
function nextProgrammeGroup(){
    if(!state)return null;
    const id=state.programme&&Object.prototype.hasOwnProperty.call(state.programme,"nextGroupId")?state.programme.nextGroupId:state.activeGroupId;
    return (state.groups||[]).find(group=>group.id===id)||null;
}
function renderFamilyState(){
    if(!state||!byId("nowTitle"))return;
    const current=state.items[state.currentIndex],next=nextProgrammeItem(),group=(state.groups||[]).find(g=>g.id===state.activeGroupId);
    byId("nowStatus").textContent="Сейчас на ТВ · "+(statusNames[state.status]||state.status);
    byId("nowTitle").textContent=state.currentTitle||current?.title||"Ролик не выбран";
    byId("nowNext").textContent=next?"Далее: "+(next.title||"Название ещё не получено"):current?"В конце программы — остановка":"Добавьте выбранные вами видео";
    byId("nextVideoTitle").textContent=next?.title||(next?"Название ещё не получено":"В конце программы — остановка");
    byId("nextVideoMeta").textContent=next?[nextProgrammeGroup()?.name,formatDuration(next.durationSeconds),typeof videoReady==="function"&&videoReady(next)?"Скачано":"Ещё не скачано"].filter(Boolean).join(" · "):"";
    const canStart=!!current&&(typeof videoCanWatch!=="function"||videoCanWatch(current));
    const toggle=byId("nowToggle");toggle.textContent=state.intent==="PLAY"?"Пауза":canStart?"Продолжить":"Не готово";toggle.dataset.familyPlayback=state.intent==="PLAY"?"PAUSE_RESUME":"PLAY";toggle.disabled=!current||(state.intent!=="PLAY"&&!canStart);
    byId("resumePlayback").disabled=!canStart;
    byId("libraryResume").querySelector("button").disabled=!canStart;
    if(current&&!canStart&&state.intent!=="PLAY")byId("libraryResume").querySelector("button").textContent=typeof unavailableWatchLabel==="function"?unavailableWatchLabel(current):"Не готово";
    else byId("libraryResume").querySelector("button").textContent="Продолжить";
    byId("libraryResume").hidden=!current;byId("libraryResumeTitle").textContent=group?.name||current?.title||"Текущее видео";
    byId("libraryResumeMeta").textContent=[current?.title,"Позиция "+resumeTime(state.positionMs),statusNames[state.status]].filter(Boolean).join(" · ");
    if(!group)byId("queueSummary").textContent=current?`Видео ${state.currentIndex+1} из ${state.items.length}`:"Программа пуста";
    syncImportFlow();
}

function isPlaylistLink(text){try{const u=new URL(text);const host=u.hostname.toLowerCase();return ((host==="youtube.com"||host.endsWith(".youtube.com")||host==="youtu.be")&&u.searchParams.has("list"))||((host==="vkvideo.ru"||host==="vk.com"||host==="m.vk.com")&&(/^\/playlist\//.test(u.pathname)||u.searchParams.has("pl")||u.searchParams.has("z")));}catch(_){return false;}}
async function beginFamilyImport(){const link=byId("playlistUrl").value.trim();if(!link)return;importSingleLink="";if(isPlaylistLink(link)){if(typeof cancelVideoPreview==="function")await cancelVideoPreview();await startPreview();}else{if(preview)await cancelPreview();importSingleLink=link;byId("singleVideoLink").textContent=link;importStep=3;syncImportFlow();if(typeof beginVideoPreview==="function")await beginVideoPreview(link);}}
function resetFamilyImport(){previewOrder=[];previewOrderId=null;previewOrderWasReady=false;importStep=1;importSingleLink="";}
function ensurePreviewOrder(){if(!preview)return;const indices=(preview.entries||[]).map(e=>e.index);if(previewOrderId!==preview.previewId){previewOrderId=preview.previewId;previewOrder=indices;byId("playlistOrderMode").value="source";importStep=previewCanApply()?2:1;}else{const valid=new Set(indices);previewOrder=previewOrder.filter(i=>valid.has(i));for(const i of indices)if(!previewOrder.includes(i))previewOrder.push(i);if(previewCanApply()&&!previewOrderWasReady)importStep=2;}previewOrderWasReady=!!previewCanApply();}
function orderedPreviewEntries(){ensurePreviewOrder();const entries=new Map((preview?.entries||[]).map(e=>[e.index,e]));return previewOrder.map(i=>entries.get(i)).filter(Boolean);}
function changePreviewOrder(mode){if(!previewCanApply())return;const entries=preview.entries.slice();if(mode==="source")entries.sort((a,b)=>a.index-b.index);else if(mode==="reverse")entries.sort((a,b)=>b.index-a.index);else if(mode==="episode")entries.sort((a,b)=>{const an=episodeNumber(a.title),bn=episodeNumber(b.title);return an===null?(bn===null?0:1):bn===null?-1:an-bn;});else {renderPreview();return;}previewOrder=entries.map(e=>e.index);renderPreview();}
function movePreviewEntry(index,position){const at=previewOrder.indexOf(index);if(at<0||!Number.isInteger(position))return;previewOrder.splice(Math.max(0,Math.min(previewOrder.length-1,position)),0,previewOrder.splice(at,1)[0]);byId("playlistOrderMode").value="manual";renderPreview();}
function appendPreviewOrderControls(row,entry,index){
    if(!previewCanApply())return;
    const controls=familyElement("div","import-order-controls");
    for(const [action,label,disabled] of [["up","↑",index===0],["down","↓",index===previewOrder.length-1]]){const b=familyButton(label,"move-preview");b.dataset.previewIndex=entry.index;b.dataset.move=action;b.disabled=disabled;b.setAttribute("aria-label",`${label==="↑"?"Выше":"Ниже"}: ${entry.title||"Видео"}`);controls.append(b);}
    const input=familyElement("input");input.type="number";input.min=1;input.max=previewOrder.length;input.value=index+1;input.dataset.previewPosition=entry.index;input.setAttribute("aria-label","Новая позиция видео");
    const move=familyButton("На место","move-preview");move.dataset.previewIndex=entry.index;move.dataset.move="position";controls.append(input,move);row.append(controls);
}
function syncImportFlow(){
    if(!byId("importSource"))return;
    const single=!!importSingleLink;
    byId("importSource").hidden=importStep!==1;byId("singleVideoConfirm").hidden=!single;
    document.querySelectorAll("[data-import-step]").forEach(e=>{if(Number(e.dataset.importStep)===importStep)e.setAttribute("aria-current","step");else e.removeAttribute("aria-current");});
    if(single){byId("playlistPreview").hidden=true;return;}
    byId("playlistPreview").hidden=importStep===1||!preview||["LOADING","CANCELLED"].includes(preview.status);
    byId("importSelectionStep").hidden=importStep!==2;
    byId("playlistConfirm").hidden=importStep!==3||!previewCanApply();
    byId("importReviewButton").disabled=!previewCanApply()||!selectedIndices.size;
    const mode=byId("playlistOrderMode").value;
    byId("playlistEntries").dataset.manual=String(mode==="manual");
    byId("playlistOrderHint").textContent=mode==="episode"?"Видео с явным номером серии идут по возрастанию. Без номера — в конце; проверьте список.":mode==="source"?"Сейчас показан порядок источника. Выберите порядок серий до добавления.":mode==="reverse"?"Порядок источника развёрнут. Проверьте последовательность ниже.":"Ваш порядок сохранится после подтверждения. Стрелки и номер позиции работают и на телефоне.";
    if(!byId("playlistApplyButton").textContent.includes("уже"))byId("playlistApplyButton").textContent="Только сохранить";
    byId("playlistDownloadButton").disabled=byId("playlistApplyButton").disabled||storageBusy||!storageState||!storageState.volumes.some(v=>v.id===byId("importStorage").value&&v.mounted&&v.writable);
    if(importStep===3&&previewCanApply()){
        const selected=orderedPreviewEntries().filter(e=>selectedIndices.has(e.index));
        byId("importFinalSummary").textContent=`Будет сохранено ${selected.length} видео. Порядок: ${byId("playlistOrderMode").selectedOptions?.[0]?.textContent||mode}.`;
        const key=JSON.stringify(selected.map(e=>[e.index,e.title]));if(byId("importFinalOrder").dataset.key!==key){byId("importFinalOrder").dataset.key=key;byId("importFinalOrder").replaceChildren(...selected.map(e=>familyElement("li","",e.title||"Без названия")));}
    }
}

function storageSize(bytes){if(typeof bytes!=="number"||!Number.isFinite(bytes))return "неизвестно";return bytes>=1073741824?(bytes/1073741824).toFixed(1)+" ГиБ":(bytes/1048576).toFixed(1)+" МиБ";}
function storageAdditionalBytes(volume){
    if(!volume?.mounted)return null;
    if(!volume.writable)return 0;
    if(![volume.limitBytes,volume.usedBytes,volume.freeBytes].every(Number.isFinite))return null;
    return Math.max(0,Math.min(volume.limitBytes-volume.usedBytes,volume.freeBytes-1073741824));
}
async function refreshStorage(){if(!device||storageRefreshing)return;storageRefreshing=true;try{storageState=await api("/api/storage","GET");renderStorage();}catch(error){byId("storageMessage").textContent="Не удалось получить хранилища: "+apiErrorText(error);}finally{storageRefreshing=false;}}
function storageOptions(select,volumes,selected){const previous=select.value;const choices=volumes.filter(v=>v.mounted&&v.writable);const key=JSON.stringify(choices.map(v=>[v.id,v.name]));if(select.dataset.key!==key){select.dataset.key=key;select.replaceChildren();for(const v of choices){const option=familyElement("option","",v.name+(v.removable?" · карта / USB":" · приставка"));option.value=v.id;select.append(option);}if(!choices.length){const option=familyElement("option","","Нет доступного хранилища");option.value="";select.append(option);}select.value=choices.some(v=>v.id===previous)?previous:selected||"";}else if(!select.value&&selected)select.value=selected;}
function renderStorage(){
    if(!storageState)return;const s=storageState,volumes=s.volumes||[];
    storageOptions(byId("storageSelect"),volumes,s.selectedId);storageOptions(byId("importStorage"),volumes,s.selectedId);
    const key=JSON.stringify([s.selectedId,volumes.map(v=>[v.id,v.name,v.mounted,v.writable,Math.floor(v.freeBytes/1048576),Math.floor(v.usedBytes/1048576),v.limitBytes])]);
    if(key!==storageRenderKey){storageRenderKey=key;const cards=volumes.map(v=>{const item=familyElement("article","storage-volume"+(v.id===s.selectedId?" selected":""));item.append(familyElement("h3","",v.name),familyElement("p","muted",v.mounted?`Видео: ${storageSize(v.usedBytes)} из ${storageSize(v.limitBytes)} · свободно ${storageSize(v.freeBytes)}`:"Носитель отключён. Файлы не удалены."));if(v.mounted)item.append(familyElement("p","muted small",`Для новых видео: ${storageSize(storageAdditionalBytes(v))} с учётом лимита и резерва 1 ГиБ.`));item.append(familyElement("span","badge"+(!v.writable?" warning":""),!v.mounted?"Подключите носитель":!v.writable?"Только чтение":v.id===s.selectedId?"Для новых загрузок":"Доступно"));return item;});if(!volumes.some(v=>v.removable))cards.push(familyElement("p","muted","Карта памяти / USB пока не обнаружена. Подключите носитель и нажмите «Обновить»."));byId("storageVolumes").replaceChildren(...cards);}
    const target=volumes.find(v=>v.id===byId("importStorage").value);byId("importStorageHint").textContent=target?`Можно скачать ещё ${storageSize(storageAdditionalBytes(target))} с учётом лимита ${storageSize(target.limitBytes)} и резерва 1 ГиБ. Размер плейлиста уточняется при загрузке.`:"Для скачивания выберите подключённое хранилище. Сам список можно сохранить сейчас.";
    const active=volumes.find(v=>v.id===s.selectedId);byId("localStorageHint").textContent="Новые файлы: "+(active?.name||"хранилище недоступно")+(active&&(!active.mounted||!active.writable)?" — сейчас недоступно":"")+". Изменить место можно в настройках.";
    const m=s.migration||{status:"IDLE"},running=["COPYING","VERIFYING","CLEANING"].includes(m.status);
    byId("storageMigration").hidden=m.status==="IDLE";
    const names={COPYING:"Копируем",VERIFYING:"Проверяем копии",CLEANING:"Освобождаем память после переноса",COMPLETE:"Перенос проверен. Исходные файлы сохранены",CANCELLED:"Перенос отменён; исходные файлы сохранены",ERROR:"Перенос не завершён",INTERRUPTED:"Перенос прерван; исходные файлы сохранены"};
    byId("storageMigrationText").textContent=`${names[m.status]||m.status}: ${m.completedFiles||0} / ${m.totalFiles||0} файлов · ${storageSize(m.copiedBytes||0)} / ${storageSize(m.totalBytes||0)}${m.error?" · "+storageErrorText(m.error):""}`;
    const progress=byId("storageMigrationProgress");if(m.totalBytes>0)progress.value=Math.min(100,Math.floor(100*(m.copiedBytes||0)/m.totalBytes));else progress.removeAttribute("value");progress.hidden=!running&&m.status!=="COMPLETE";
    byId("storageCancel").hidden=!running||m.status==="CLEANING";byId("storageCleanup").hidden=!(s.retainedOriginalBytes>0||s.incompleteCopyBytes>0);byId("storageCleanup").textContent=`Освободить ${storageSize((s.retainedOriginalBytes||0)+(s.incompleteCopyBytes||0))}: ${s.retainedOriginalBytes>0?"проверенные исходные копии":"временные копии переноса"}`;
    for(const id of ["storageChoose","storageMigrate","storageCleanup"])byId(id).disabled=storageBusy||running;byId("storageCancel").disabled=storageBusy;
    syncImportFlow();
}
function storageErrorText(code){return ({STORAGE_DESTINATION_CONFLICT:"В выбранном хранилище уже есть сохранённая копия. Подготовка не начата; файлы сохранены.",STORAGE_CHANGED:"Настройки хранилища изменились. Проверьте выбор и повторите.",STORAGE_UNAVAILABLE:"Носитель недоступен. Подключите карту.",NO_SPACE:"Недостаточно места. Исходные файлы сохранены.",FILE_DAMAGED:"Копия не прошла проверку. Исходный файл сохранён.",BUSY:"Дождитесь завершения текущей операции.",STOP_PLAYBACK_FIRST:"Сначала остановите текущий ролик и повторите освобождение памяти."})[code]||code;}
function importDownloadPlan(){
    const id=byId("importStorage").value,v=storageState?.volumes?.find(e=>e.id===id);
    if(!v?.mounted||!v?.writable)throw new Error("Выберите доступное хранилище для скачивания.");
    return {storageId:id,select:{action:"SELECT",storageId:id,storageVersion:storageState.storageVersion,idempotencyKey:commandKey()},enableKey:commandKey()};
}
async function finishImportDownload(pending){
    const plan=pending.afterApply,receipt=pending.appliedResult;
    if(!receipt?.addition?.groupId)throw new Error("Не удалось подтвердить плейлист для загрузки.");
    if(!plan.enable){
        const selected=await api("/api/storage","POST",plan.select);
        const fresh=selected.storageVersion===undefined?await api("/api/storage","GET"):selected;
        if(fresh.selectedId!==plan.storageId)throw Object.assign(new Error("Хранилище изменилось"),{status:409,data:{error:"STORAGE_CHANGED"}});
        plan.enable={action:"ENABLE",groupId:receipt.addition.groupId,queueVersion:receipt.queueVersion,storageId:plan.storageId,storageVersion:fresh.storageVersion,idempotencyKey:plan.enableKey};
        writeJson(COMMANDS_KEY,pendingCommands);
    }
    await api("/api/prepared","POST",plan.enable);
}
async function mutateStorage(action){if(storageBusy||!storageState)return;const id=byId("storageSelect").value;if(["SELECT","MIGRATE"].includes(action)&&!id)return;
    if(action==="MIGRATE"&&!confirm("Скопировать готовые видео в выбранное хранилище и проверить копии? Исходные файлы пока останутся. Подготовка загрузок может быть приостановлена."))return;
    if(action==="CONFIRM_CLEANUP"&&state?.intent!=="STOP"){byId("storageMessage").textContent="Для освобождения исходных копий сначала нажмите «Стоп» в пульте. Паузы недостаточно.";return;}
    if(action==="CONFIRM_CLEANUP"&&!confirm(`Освободить копии переноса: ${storageSize(storageState.retainedOriginalBytes||0)} проверенных исходных копий и ${storageSize(storageState.incompleteCopyBytes||0)} временных копий? Программа и действующие готовые файлы останутся.`))return;
    storageBusy=true;renderStorage();try{const payload={action,storageVersion:storageState.storageVersion,idempotencyKey:commandKey()};if(["SELECT","MIGRATE"].includes(action))payload.storageId=id;if(action==="CONFIRM_CLEANUP")Object.assign(payload,{confirm:true,stopPlayback:true,queueVersion:state.queueVersion});await perform("storage-"+commandKey(),"/api/storage",payload);await refreshStorage();byId("storageMessage").textContent=action==="SELECT"?"Хранилище для новых загрузок выбрано.":"Операция принята. Состояние показано ниже.";}catch(error){byId("storageMessage").textContent=storageErrorText(error.data?.error)||apiErrorText(error);await refreshStorage();}finally{storageBusy=false;renderStorage();}}

initializeFamilyLayout();
byId("controlSection").addEventListener("click",event=>{const button=event.target.closest("button");if(!button)return;if(button.dataset.familyPlayback){run(()=>playback(button.dataset.familyPlayback));return;}if(button.dataset.addSource){showAddSource(button.dataset.addSource);return;}const action=button.dataset.familyAction;if(action==="page")showFamilyPage(button.dataset.page);if(action==="add"){showFamilyPage("add");showAddSource("link");}if(action==="close-add"){if(typeof cancelVideoPreview==="function")run(cancelVideoPreview);showFamilyPage("library");}if(action==="move-preview"){const index=Number(button.dataset.previewIndex);const at=previewOrder.indexOf(index);const to=button.dataset.move==="up"?at-1:button.dataset.move==="down"?at+1:Number(button.parentElement.querySelector("input").value)-1;movePreviewEntry(index,to);}});
byId("themeSelect").addEventListener("change",()=>{setFamilyTheme(byId("themeSelect").value);saveFamilyView();});
byId("playlistOrderMode").addEventListener("change",()=>changePreviewOrder(byId("playlistOrderMode").value));
byId("importReviewButton").addEventListener("click",()=>{importStep=3;syncImportFlow();run(refreshStorage);});
byId("importSelectionBack").addEventListener("click",()=>{importStep=2;syncImportFlow();});
byId("importSourceBack").addEventListener("click",()=>{importStep=1;syncImportFlow();});
byId("singleVideoBack").addEventListener("click",()=>{importSingleLink="";importStep=1;syncImportFlow();});
byId("singleVideoApply").addEventListener("click",()=>run(async()=>{if(!importSingleLink||!state)return;await withBusy("single-add",async()=>{byId("singleVideoApply").disabled=true;try{await perform("single-add-"+commandKey(),"/api/queue/add",{links:[importSingleLink],queueVersion:state.queueVersion,idempotencyKey:commandKey()});importSingleLink="";importStep=1;showFamilyPage("library");}finally{byId("singleVideoApply").disabled=false;}});}));
byId("playlistDownloadButton").addEventListener("click",()=>run(()=>applyPreview(true)));
byId("storageRefresh").addEventListener("click",()=>run(refreshStorage));
byId("storageChoose").addEventListener("click",()=>run(()=>mutateStorage("SELECT")));
byId("storageMigrate").addEventListener("click",()=>run(()=>mutateStorage("MIGRATE")));
byId("storageCancel").addEventListener("click",()=>run(()=>mutateStorage("CANCEL_MIGRATION")));
byId("storageCleanup").addEventListener("click",()=>run(()=>mutateStorage("CONFIRM_CLEANUP")));
byId("importStorage").addEventListener("change",renderStorage);
byId("storageSelect").addEventListener("change",renderStorage);
setInterval(()=>{if(device&&!document.hidden&&["settings","add","downloads"].includes(familyPage))run(refreshStorage);},5000);
if(device)run(refreshStorage);if(state)renderFamilyState();
