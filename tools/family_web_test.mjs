// Real Chrome DOM with a local deterministic API fixture. Not H728/YouTube acceptance.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import {spawn} from 'node:child_process';
const out=path.resolve('.dev/family-web-test');fs.mkdirSync(out,{recursive:true});
const requests=[],errors=[];
const items=[1,2,3,4].map(n=>({queueItemId:'q'+n,videoId:'video00000'+n,title:n===1?'<img src=x onerror=alert(1)> История #1':'История #'+n,ready:n<3,preparationStatus:n<3?'READY':'WAITING',durationSeconds:1800,local:false}));
const state={queueVersion:10,items,currentIndex:0,positionMs:42000,durationMs:1800000,seekable:true,playbackGeneration:1,intent:'PAUSE',status:'PAUSED',updatedAtMs:0,serverTimeMs:0,displayReady:true,activeGroupId:'g1',groups:[{id:'g1',name:'История пророков',source:'VK_PLAYLIST',videoIds:items.map(e=>e.videoId)},{id:'g2',name:'Арабский с нуля',source:'YOUTUBE_PLAYLIST',videoIds:items.slice(0,2).map(e=>e.videoId)}],prepared:{enabled:true,paused:false,ready:2,count:4,limitBytes:10737418240,freeBytes:3000000000,reserveBytes:1073741824,usedBytes:1000000,additionalBytes:2000000000,files:items.slice(0,2).map(e=>({videoId:e.videoId,title:e.title,bytes:500000,available:true,inQueue:true})),entries:items.map((e,i)=>({index:i+1,videoId:e.videoId,title:e.title,status:e.preparationStatus,bytes:i<2?500000:0,percent:i<2?100:0,durationSeconds:1800}))}};
const storage={storageVersion:1,selectedId:'internal',volumes:[{id:'internal',name:'Память приставки',mounted:true,writable:true,removable:false,freeBytes:3000000000,totalBytes:16000000000,limitBytes:10737418240,usedBytes:1000000},{id:'sd-fixture',name:'microSD',mounted:true,writable:true,removable:true,freeBytes:60000000000,totalBytes:64000000000,limitBytes:53687091200,usedBytes:0}],migration:{status:'IDLE'},retainedOriginalBytes:0};
const preview={previewId:'preview-fixture',status:'COMPLETE',provider:'VK',title:'Проверка импорта',reportedCount:4,entries:[{index:1,videoId:'video000010',title:'Серия 10',durationSeconds:600,availability:'UNKNOWN',selectable:true},{index:2,videoId:'video000002',title:'Серия 2',durationSeconds:600,availability:'AVAILABLE',selectable:true},{index:3,videoId:'video000001',title:'Серия 1',durationSeconds:600,availability:'AVAILABLE',selectable:true},{index:4,title:'Удалённое видео',availability:'UNAVAILABLE',selectable:false}]};
let lostEnable=false;const receipts=new Map();
const server=http.createServer(async(req,res)=>{
 const url=new URL(req.url,'http://localhost');const pathname=url.pathname;
 if(!pathname.startsWith('/api/')){const file=path.resolve('app/src/main/assets/web',pathname==='/'?'index.html':pathname.slice(1));if(!file.startsWith(path.resolve('app/src/main/assets/web')+path.sep)){res.writeHead(404).end();return;}try{res.setHeader('Content-Type',file.endsWith('.js')?'text/javascript':file.endsWith('.css')?'text/css':'text/html');res.end(fs.readFileSync(file));}catch{res.writeHead(404).end();}return;}
 let raw='';for await(const part of req)raw+=part;const body=raw?JSON.parse(raw):{};
 if(req.method==='POST')requests.push({path:pathname,body});
 if(req.headers.authorization!=='Bearer fixture-only'){res.writeHead(401).end('{"error":"UNAUTHORIZED"}');return;}
 let result={};
 if(pathname==='/api/state')result=state;
 else if(pathname==='/api/storage'){if(req.method==='POST'){if(!receipts.has(body.idempotencyKey)){assert.equal(body.storageVersion,storage.storageVersion);if(body.action==='SELECT'){storage.selectedId=body.storageId;storage.storageVersion++;}receipts.set(body.idempotencyKey,structuredClone(storage));}result=receipts.get(body.idempotencyKey);}else result=storage;}
 else if(pathname==='/api/devices')result={devices:[]};
 else if(pathname==='/api/local')result={files:[],jobs:[]};
 else if(pathname==='/api/collections')result={collections:[]};
 else if(pathname.startsWith('/api/thumbnails/')){res.writeHead(404).end('{"error":"NOT_FOUND"}');return;}
 else if(pathname==='/api/playlists/preview')result={previewId:preview.previewId};
 else if(pathname.startsWith('/api/playlists/preview/'))result=preview;
 else if(pathname==='/api/playlists/cancel')result={...preview,status:'CANCELLED'};
 else if(pathname==='/api/playlists/apply'){assert.deepEqual(body.orderedIndices,[3,2,1]);if(!receipts.has(body.idempotencyKey)){state.queueVersion++;state.groups.push({id:'g-new',name:body.groupName,source:'VK_PLAYLIST',videoIds:body.orderedIndices.map(i=>preview.entries.find(e=>e.index===i).videoId)});receipts.set(body.idempotencyKey,{...structuredClone(state),addition:{groupId:'g-new',added:1,skipped:2}});}result=receipts.get(body.idempotencyKey);}
 else if(pathname==='/api/prepared'){assert.equal(body.groupId,'g-new');assert.equal(body.storageId,'sd-fixture');assert.equal(body.storageVersion,storage.storageVersion);assert.equal(body.action,'ENABLE');result=state.prepared;}
 else{res.writeHead(404).end('{"error":"NOT_FOUND"}');return;}
 res.setHeader('Content-Type','application/json');res.end(JSON.stringify(result));
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const port=server.address().port,profile=fs.mkdtempSync(path.join(out,'chrome-'));
const chrome=spawn(process.env.CHROME||'/usr/bin/google-chrome',['--headless=new','--no-first-run','--no-default-browser-check','--proxy-server=direct://','--remote-debugging-port=0','--user-data-dir='+profile,'about:blank'],{stdio:['ignore','ignore',fs.openSync(path.join(out,'chrome.log'),'a')]});
const sleep=ms=>new Promise(r=>setTimeout(r,ms));let ws;
try{
 let cdpPort;for(let i=0;i<50;i++){try{cdpPort=Number(fs.readFileSync(path.join(profile,'DevToolsActivePort'),'utf8').split('\n')[0]);break;}catch{}await sleep(100);}assert(cdpPort);
 const tabs=await(await fetch('http://127.0.0.1:'+cdpPort+'/json')).json();ws=new WebSocket(tabs.find(t=>t.type==='page').webSocketDebuggerUrl);await new Promise((r,j)=>{ws.onopen=r;ws.onerror=j;});
 let seq=0;const calls=new Map();ws.onmessage=e=>{const m=JSON.parse(e.data);if(m.id){const c=calls.get(m.id);calls.delete(m.id);m.error?c.reject(m.error):c.resolve(m.result);}else if(m.method==='Runtime.exceptionThrown')errors.push(m.params.exceptionDetails.exception?.description||m.params.exceptionDetails.text);};
 const send=(method,params={})=>new Promise((resolve,reject)=>{const id=++seq;calls.set(id,{resolve,reject});ws.send(JSON.stringify({id,method,params}));});
 const ev=async expression=>{const r=await send('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});if(r.exceptionDetails)throw Error(r.exceptionDetails.exception?.description||r.exceptionDetails.text);return r.result.value;};
 const wait=async expression=>{for(let i=0;i<80;i++){if(await ev(expression))return;await sleep(100);}throw Error('Timed out: '+expression+'; JS: '+errors.join('; ')+'; page: '+await ev('document.body.innerText.slice(0,200)'));};
 const click=async selector=>{assert(await ev(`!!document.querySelector(${JSON.stringify(selector)})`),'Missing '+selector);await ev(`document.querySelector(${JSON.stringify(selector)}).click()`);};
 await send('Runtime.enable');await send('Page.enable');await send('Emulation.setDeviceMetricsOverride',{width:1280,height:1000,deviceScaleFactor:1,mobile:false});await send('Emulation.setEmulatedMedia',{features:[{name:'prefers-color-scheme',value:'light'}]});
 await send('Page.addScriptToEvaluateOnNewDocument',{source:'localStorage.setItem("parentcast.device.v1",JSON.stringify({deviceId:"fixture",token:"fixture-only"}));const originalFetch=window.fetch;let losePreparedResponse=true;window.fetch=async(...args)=>{const response=await originalFetch(...args);if(args[0]==="/api/prepared"&&args[1]?.method==="POST"&&losePreparedResponse){losePreparedResponse=false;throw new TypeError("Synthetic lost prepared response");}return response;};'});
 await send('Page.navigate',{url:'http://127.0.0.1:'+port+'/'});await wait('document.querySelectorAll(".library-card").length===2');
 assert.equal(await ev('document.querySelectorAll("[data-page-panel]:not([hidden])").length'),1);
 assert.equal(await ev('document.getElementById("collectionSnapshotNote").closest("details").contains(document.getElementById("collectionSaveButton"))'),true,'Collection-only explanation belongs inside collection controls');
 assert.equal(await ev('document.getElementById("playlistConfirm").querySelector(".notice").textContent'),'Плейлист добавится в программу. Просмотр не запустится.');
 assert.deepEqual(await ev('[storageAdditionalBytes({mounted:true,writable:true,limitBytes:10737418240,usedBytes:10704993730,freeBytes:4734377984}),storageAdditionalBytes({mounted:true,writable:true,limitBytes:10737418240,usedBytes:0,freeBytes:1073741824}),storageAdditionalBytes({mounted:true,writable:true,limitBytes:10,usedBytes:20,freeBytes:5000000000}),storageAdditionalBytes({mounted:false,writable:false,limitBytes:null,usedBytes:null,freeBytes:null})]'),[32424510,0,0,null],'New-download allowance must respect both quota and reserve without inventing missing-volume capacity');
 await ev('(()=>{const v=storageState.volumes[0],original={...v};Object.assign(v,{usedBytes:10704993730,freeBytes:4734377984});renderStorage();if(!document.getElementById("importStorageHint").textContent.includes("30.9 МиБ"))throw Error("Import remaining quota is misleading");if(!document.getElementById("storageVolumes").textContent.includes("30.9 МиБ"))throw Error("Settings remaining quota is misleading");Object.assign(v,original);renderStorage();})()');
 // A visible command notice must not auto-place the content in the navigation column.
 await ev('showFamilyPage("remote");document.getElementById("commandMessage").textContent="Команда принята";document.getElementById("experienceMessage").textContent="Видео в программе"');
 const noticeLayout=await ev('(()=>{const r=s=>document.querySelector(s).getBoundingClientRect();const nav=r(".app-nav"),pages=r(".app-pages"),title=r("#currentTitle"),notice=r(".command-notice");return {navRight:nav.right,pagesLeft:pages.left,pagesWidth:pages.width,titleWidth:title.width,noticeWidth:notice.width,noticeVisible:getComputedStyle(document.querySelector(".command-notice")).display!=="none"}})()');
 assert.equal(noticeLayout.noticeVisible,true);assert(noticeLayout.pagesLeft>=noticeLayout.navRight+10,JSON.stringify(noticeLayout));assert(noticeLayout.pagesWidth>600,JSON.stringify(noticeLayout));assert(noticeLayout.titleWidth>300,JSON.stringify(noticeLayout));
 const noticeShot=await send("Page.captureScreenshot",{format:"png"});fs.writeFileSync(path.join(out,"desktop-command-notice.png"),Buffer.from(noticeShot.data,"base64"));
 await ev('document.getElementById("experienceMessage").textContent=""');assert.equal(await ev('getComputedStyle(document.querySelector(".command-notice")).display!=="none"'),true,'One nonempty message must remain visible');
 await ev('document.getElementById("commandMessage").textContent="";document.getElementById("retryCommandButton").hidden=false');assert.equal(await ev('getComputedStyle(document.querySelector(".command-notice")).display!=="none"'),true,'A visible retry must keep the notice');
 await ev('document.getElementById("retryCommandButton").hidden=true');assert.equal(await ev('getComputedStyle(document.querySelector(".command-notice")).display'),"none",'Empty notice must not leave a row');
 for(const width of [1856,1280,1000,850,701,700,390,320]){
  await send('Emulation.setDeviceMetricsOverride',{width,height:1100,deviceScaleFactor:1,mobile:width<=700});
  for(const visible of [false,true]){
   await ev('document.getElementById("commandMessage").textContent='+JSON.stringify(visible?'Команда принята':'')+';document.getElementById("experienceMessage").textContent='+JSON.stringify(visible?'Изменения сохранены':''));
   const layout=await ev('(()=>{const a=document.querySelector(".app-pages").getBoundingClientRect(),n=document.querySelector(".app-nav").getBoundingClientRect();return {left:a.left,width:a.width,navRight:n.right,scroll:document.documentElement.scrollWidth,client:document.documentElement.clientWidth}})()');
   assert.equal(layout.scroll,layout.client,'Page overflow '+width+' notice '+visible);
   if(width>700){assert(layout.left>=layout.navRight+10,JSON.stringify({width,visible,...layout}));assert(layout.width>350,JSON.stringify(layout));}else assert(layout.width>=280,JSON.stringify(layout));
  }
 }
 await send('Emulation.setDeviceMetricsOverride',{width:1280,height:1000,deviceScaleFactor:1,mobile:false});
 await ev('document.getElementById("commandMessage").textContent="";document.getElementById("experienceMessage").textContent="";showFamilyPage("library")');
 let shot=await send('Page.captureScreenshot',{format:'png'});fs.writeFileSync(path.join(out,'desktop.png'),Buffer.from(shot.data,'base64'));
 assert.deepEqual(await ev('(()=>{const item=state.items[0];item.ready=false;item.preparationStatus="STORAGE_UNAVAILABLE";state.prepared.files[0].available=false;renderState();const result={resumeDisabled:document.getElementById("nowToggle").disabled,fileDelete:!!document.querySelector("#preparedFiles button[data-prepared-delete=\\"video000001\\"]"),message:document.getElementById("preparedSummary").textContent.includes("На отключённом носителе: 1")};item.ready=true;item.preparationStatus="READY";state.prepared.files[0].available=true;renderState();return result;})()'),{resumeDisabled:true,fileDelete:false,message:true});
 await click('[data-open-group="g1"]');assert(await ev('document.querySelector("#queue").textContent.includes("<img src=x onerror=alert(1)>")'));assert.equal(await ev('document.querySelectorAll("img[onerror]").length'),0);
 await click('[data-family-action="add"]');await ev('document.getElementById("playlistUrl").value="https://vkvideo.ru/playlist/-227392256_1/"');await click('#playlistPreviewButton');await wait('preview?.status==="COMPLETE"');
 assert.equal(await ev('importStep'),2);assert.equal(requests.filter(r=>r.path==='/api/playlists/apply').length,0);
 await click('#playlistSelectAllButton');await ev('document.getElementById("playlistOrderMode").value="episode";document.getElementById("playlistOrderMode").dispatchEvent(new Event("change",{bubbles:true}))');
 assert.deepEqual(await ev('selectedPayload().orderedIndices'),[3,2,1]);assert.equal(await ev('[...document.querySelectorAll("input[data-index]")].find(e=>e.dataset.index==="4").disabled'),true);
 for(const width of [390,320]){await send('Emulation.setDeviceMetricsOverride',{width,height:1100,deviceScaleFactor:1,mobile:true});await sleep(100);const metrics=await ev('({client:document.documentElement.clientWidth,scroll:document.documentElement.scrollWidth})');assert.equal(metrics.scroll,metrics.client,'Mobile overflow at '+width);}
 await ev('document.getElementById("playlistOrderMode").value="manual";document.getElementById("playlistOrderMode").dispatchEvent(new Event("change",{bubbles:true}));document.getElementById("playlistEntries").scrollIntoView({block:"start"})');
 shot=await send('Page.captureScreenshot',{format:'png'});fs.writeFileSync(path.join(out,'mobile-import.png'),Buffer.from(shot.data,'base64'));
 await send('Emulation.setEmulatedMedia',{features:[{name:'prefers-color-scheme',value:'dark'}]});await sleep(100);shot=await send('Page.captureScreenshot',{format:'png'});fs.writeFileSync(path.join(out,'mobile-dark.png'),Buffer.from(shot.data,'base64'));
 await send('Emulation.setEmulatedMedia',{features:[{name:'prefers-color-scheme',value:'light'}]});
 await click('#importReviewButton');await ev('document.getElementById("importStorage").value="sd-fixture";document.getElementById("importStorage").dispatchEvent(new Event("change",{bubbles:true}))');
 await click('#playlistDownloadButton');await wait('!!Object.values(pendingCommands).find(p=>p.appliedResult)');await wait('!busyActions.has("apply")');
 assert((await ev('document.getElementById("commandMessage").textContent')).includes('Плейлист сохранён'));
 await click('#retryCommandButton');await wait('Object.keys(pendingCommands).length===0');
 assert.equal(requests.filter(r=>r.path==='/api/playlists/apply').length,1,'Retry must not re-import after a successful apply');
 const enables=requests.filter(r=>r.path==='/api/prepared');assert.equal(enables.length,2);assert.deepEqual(enables[0].body,enables[1].body,'Retry must reuse exact ENABLE body');assert.equal(requests.filter(r=>r.path==='/api/playback').length,0,'Import must not PLAY or STOP');
 assert.equal(await ev('familyPage'),'downloads');
 assert.equal(await ev('document.querySelectorAll(".download-group").length'),3,'Other playlists must retain compact download summaries');
 const stable=await ev('(()=>{const button=document.querySelector(".download-row button");button.focus();const row=button.closest(".download-row");renderPrepared();return document.activeElement===button&&row.isConnected;})()');assert.equal(stable,true,'Polling must preserve focused download controls');
 for(const page of ['settings','remote','library']){await click('.app-nav [data-page="'+page+'"]');const metrics=await ev('({client:document.documentElement.clientWidth,scroll:document.documentElement.scrollWidth})');assert.equal(metrics.scroll,metrics.client,'Page overflow '+page);}
 await click('#libraryBack');await ev('document.getElementById("librarySearch").value="Арабский";document.getElementById("librarySearch").dispatchEvent(new Event("input",{bubbles:true}))');assert.equal(await ev('document.querySelectorAll(".library-card").length'),1);
 assert.deepEqual(errors,[]);console.log('PASS: real Chrome fixture desktop/mobile320/390; navigation, safe titles, import order before apply, unavailable selection, save/download and lost-response retry, no playback changes. Synthetic API only.');
 await send('Browser.close');
}finally{ws?.close();if(chrome.exitCode===null)chrome.kill('SIGTERM');await new Promise(r=>server.close(r));}
