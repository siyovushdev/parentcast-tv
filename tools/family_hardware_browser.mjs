// Real Chrome + confirmed H728. Reuses an existing browser token without printing it.
// Optional FAMILY_IMPORT_TEST=1 imports only two videos already present in another
// programme group, then removes only the new temporary group. No files are deleted.
// Required: PARENTCAST_URL, PARENTCAST_DEVICE_FILE. CHROME may override the executable.
import fs from 'node:fs';
import path from 'node:path';
import {spawn} from 'node:child_process';
import {randomUUID} from 'node:crypto';
import assert from 'node:assert/strict';
const base=process.env.PARENTCAST_URL?.replace(/\/+$/,'');
const tokenFile=process.env.PARENTCAST_DEVICE_FILE;
if(!base||!tokenFile)throw Error('Explicit panel URL and protected token file required');
const out=path.resolve('.dev/family-hardware-browser');fs.mkdirSync(out,{recursive:true,mode:0o700});
const profile=fs.mkdtempSync(path.join(out,'chrome-'));
const chrome=spawn(process.env.CHROME||'/usr/bin/google-chrome',['--headless=new','--no-first-run','--no-default-browser-check','--proxy-server=direct://','--remote-debugging-port=0','--user-data-dir='+profile,'about:blank'],{stdio:['ignore','ignore',fs.openSync(path.join(out,'chrome.log'),'a',0o600)]});
const sleep=ms=>new Promise(r=>setTimeout(r,ms));let ws,send,ev,temporaryGroup=null,before;
const errors=[];let observedApply,expectedTemporaryIds;
const temporaryName='Проверка семейного пульта (временная) '+randomUUID();
// Only remove our exact, inactive group while every video is still referenced by
// another group. removeGroup also removes unshared queue entries, so recheck live
// references and let queueVersion reject concurrent changes.
const removeTemporaryGroup=async()=>{
 if(!before||!expectedTemporaryIds)return false;
 const live=await ev('api("/api/state","GET")');
 const oldIds=new Set(before.groups.map(g=>g.id));
 const candidates=live.groups.filter(g=>!oldIds.has(g.id)&&g.name===temporaryName);
 if(!candidates.length){temporaryGroup=null;return false;}
 assert.equal(candidates.length,1,'Ambiguous temporary group; leave it untouched');
 const group=candidates[0];
 if(temporaryGroup)assert.equal(group.id,temporaryGroup);
 assert.deepEqual(group.videoIds,expectedTemporaryIds,'Temporary group was edited; leave it untouched');
 assert.notEqual(live.activeGroupId,group.id,'Temporary group is now active; leave it untouched');
 const shared=new Set(live.groups.filter(g=>g.id!==group.id).flatMap(g=>g.videoIds));
 assert(group.videoIds.every(id=>shared.has(id)),'Group no longer shares all videos; leave it untouched');
 temporaryGroup=group.id;
 await ev('api("/api/queue/group-remove","POST",{groupId:'+JSON.stringify(group.id)+',queueVersion:'+JSON.stringify(live.queueVersion)+',idempotencyKey:commandKey()}).then(()=>refreshState())');
 temporaryGroup=null;return true;
};
try {
 let port;for(let n=0;n<60;n++){try{port=Number(fs.readFileSync(path.join(profile,'DevToolsActivePort'),'utf8').split('\n')[0]);break;}catch{}await sleep(100);}
 if(!port)throw Error('Chrome did not start');
 const pages=await(await fetch('http://127.0.0.1:'+port+'/json')).json();
 ws=new WebSocket(pages.find(p=>p.type==='page').webSocketDebuggerUrl);await new Promise((r,j)=>{ws.onopen=r;ws.onerror=j;});
 let seq=0;const pending=new Map();
 ws.onmessage=e=>{const m=JSON.parse(e.data);if(m.id){const p=pending.get(m.id);pending.delete(m.id);m.error?p.reject(Error(m.error.message)):p.resolve(m.result);}else if(m.method==='Runtime.exceptionThrown')errors.push(m.params.exceptionDetails.text);else if(m.method==='Network.requestWillBeSent'&&m.params.request.url===base+'/api/playlists/apply'&&m.params.request.postData)observedApply=JSON.parse(m.params.request.postData);};
 send=(method,params={})=>new Promise((resolve,reject)=>{const id=++seq;pending.set(id,{resolve,reject});ws.send(JSON.stringify({id,method,params}));});
 ev=async expression=>{const r=await send('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});if(r.exceptionDetails)throw Error('Browser evaluation failed: '+r.exceptionDetails.text);return r.result.value;};
 const wait=async(condition,ms=30000)=>{const deadline=Date.now()+ms;while(Date.now()<deadline){if(await ev(condition))return;await sleep(250);}throw Error('UI condition timed out: '+condition);};
 const click=async selector=>{const p=await ev(`(()=>{const e=[...document.querySelectorAll(${JSON.stringify(selector)})].find(e=>!e.disabled&&e.getClientRects().length&&getComputedStyle(e).visibility!=='hidden');if(!e)throw Error('Unavailable control');e.scrollIntoView({block:'center'});const r=e.getBoundingClientRect();return{x:r.x+r.width/2,y:r.y+r.height/2}})()`);await send('Input.dispatchMouseEvent',{type:'mousePressed',button:'left',clickCount:1,...p});await send('Input.dispatchMouseEvent',{type:'mouseReleased',button:'left',clickCount:1,...p});};
 const shot=async name=>{const r=await send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});fs.writeFileSync(path.join(out,name),Buffer.from(r.data,'base64'));};
 await send('Runtime.enable');await send('Page.enable');await send('Network.enable');
 await send('Emulation.setDeviceMetricsOverride',{width:1280,height:1000,deviceScaleFactor:1,mobile:false});
 await send('Page.navigate',{url:base+'/'});await sleep(800);
 const device=JSON.parse(fs.readFileSync(tokenFile,'utf8'));
 await ev('localStorage.setItem("parentcast.device.v1",'+JSON.stringify(JSON.stringify(device))+');location.reload()');
 await wait('typeof state!=="undefined"&&state&&document.querySelector(".app-nav")&&document.querySelectorAll(".library-card").length>0');
 before=await ev('state');assert.equal(before.intent,'PAUSE','Hardware acceptance expects existing PAUSE');
 await wait('[...document.querySelectorAll(".library-cover img")].some(e=>!e.hidden&&e.naturalWidth>0)',45000);
 console.log(JSON.stringify({stage:'liveLibrary',items:before.items.length,groups:before.groups.length,loadedCovers:await ev('[...document.querySelectorAll(".library-cover img")].filter(e=>!e.hidden&&e.naturalWidth>0).length')}));
 await shot('desktop-library.png');
 for(const width of [390,320]){
  await send('Emulation.setDeviceMetricsOverride',{width,height:1000,deviceScaleFactor:1,mobile:true});
  for(const page of ['remote','downloads','settings','library']){await click('.app-nav [data-page="'+page+'"]');const m=await ev('({client:document.documentElement.clientWidth,scroll:document.documentElement.scrollWidth})');assert.equal(m.scroll,m.client,'Overflow '+page+' at '+width);}
 }
 await send('Emulation.setDeviceMetricsOverride',{width:390,height:1000,deviceScaleFactor:1,mobile:true});await shot('mobile-library.png');
 await click('.app-nav [data-page="settings"]');await wait('storageState&&storageState.volumes.length>0');
 console.log(JSON.stringify({stage:'liveStorage',volumes:await ev('storageState.volumes.map(v=>({id:v.id,removable:v.removable,mounted:v.mounted,writable:v.writable}))')}));
 await shot('mobile-storage.png');
 if(process.env.FAMILY_IMPORT_TEST==='1'){
  await click('.app-nav [data-page="library"]');await click('[data-family-action="add"]');
  await ev('document.getElementById("playlistUrl").value="https://vkvideo.ru/playlist/-227392256_1/"');await click('#playlistPreviewButton');
  await wait('preview&&preview.status!=="LOADING"',180000);
  const preview=await ev('preview');assert.equal(preview.status,'COMPLETE');
  const existing=new Set(before.groups.flatMap(g=>g.videoIds));
  const seen=new Set();
  const chosen=preview.entries.filter(e=>{if(!e.selectable||!existing.has(e.videoId)||seen.has(e.videoId))return false;seen.add(e.videoId);return true;}).slice(0,2);assert.equal(chosen.length,2);
  for(const e of chosen)await click('input[data-index="'+e.index+'"]');
  await ev('document.getElementById("playlistOrderMode").value="reverse";document.getElementById("playlistOrderMode").dispatchEvent(new Event("change",{bubbles:true}))');
  const wanted=[...chosen].sort((a,b)=>b.index-a.index).map(e=>e.videoId);
  expectedTemporaryIds=wanted;
  const untouched=await ev('state');assert.equal(untouched.queueVersion,before.queueVersion);assert.equal(untouched.intent,before.intent);
  await click('#importReviewButton');await ev('document.getElementById("playlistGroupName").value='+JSON.stringify(temporaryName)+';document.getElementById("playlistGroupName").dispatchEvent(new Event("input",{bubbles:true}))');
  await shot('mobile-import-confirm.png');await click('#playlistApplyButton');
  await wait('!busyActions.has("apply")&&state.groups.length>'+before.groups.length);
  const after=await ev('state');const old=new Set(before.groups.map(g=>g.id));
  const added=after.groups.filter(g=>!old.has(g.id));assert.equal(added.length,1);temporaryGroup=added[0].id;
  assert.deepEqual(added[0].videoIds,wanted);assert.equal(after.items.length,before.items.length);assert.equal(after.intent,before.intent);assert.equal(after.positionMs,before.positionMs);
  assert(observedApply);await ev('api("/api/playlists/apply","POST",'+JSON.stringify(observedApply)+').then(()=>refreshState())');
  assert.equal(await ev('state.queueVersion'),after.queueVersion);
  assert(await removeTemporaryGroup(),'Temporary group must exist before cleanup');
  const restored=await ev('state');assert.deepEqual(restored.groups,before.groups);assert.deepEqual(restored.items.map(e=>[e.queueItemId,e.videoId]),before.items.map(e=>[e.queueItemId,e.videoId]));assert.equal(restored.intent,before.intent);assert.equal(restored.positionMs,before.positionMs);
  console.log(JSON.stringify({stage:'realVkImport',received:preview.entries.length,status:preview.status,selected:2,orderedApply:'PASS',replay:'PASS',temporaryGroupRemoved:true,programmeAndPausePreserved:true}));
 }
 await ev('showFamilyPage("library")');
 assert.deepEqual(errors,[]);console.log('PASS: real H728 UI, thumbnails, mobile/desktop navigation, storage discovery; JavaScript exceptions 0');
}finally{
 if(expectedTemporaryIds&&ev){try{if(await removeTemporaryGroup())console.log('Temporary acceptance group removed after failure');}catch{console.log('Temporary acceptance group cleanup needs follow-up; no unrelated group was removed');}}
 if(send)await send('Browser.close').catch(()=>{});ws?.close();if(chrome.exitCode===null)chrome.kill('SIGTERM');
}
