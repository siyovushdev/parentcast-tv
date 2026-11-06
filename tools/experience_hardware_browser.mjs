// Opt-in real Chrome acceptance against an already confirmed H728 running this version.
// Required: PARENTCAST_URL, PARENTCAST_DEVICE_FILE. Optional EXPERIENCE_CHILD_TEST=1.
// Never starts playback, downloads, removes media, or changes current programme context.
// Stops on concurrent edits; cleanup never rebases itself over another person's changes.
import fs from 'node:fs';
import path from 'node:path';
import {spawn} from 'node:child_process';
import {randomUUID} from 'node:crypto';
import assert from 'node:assert/strict';

const base=process.env.PARENTCAST_URL?.replace(/\/+$/,'');
const tokenFile=process.env.PARENTCAST_DEVICE_FILE;
if(!base||!tokenFile)throw Error('Explicit panel URL and protected token file required');
const parsed=new URL(base);
if(!['http:','https:'].includes(parsed.protocol)||parsed.username||parsed.password)throw Error('Use a panel URL without credentials');
const device=JSON.parse(fs.readFileSync(tokenFile,'utf8'));
if(typeof device.token!=='string'||!device.token)throw Error('Protected browser token is unavailable');
const out=path.resolve(process.env.EXPERIENCE_BROWSER_OUT||'.dev/experience-hardware-browser');
fs.mkdirSync(out,{recursive:true,mode:0o700});
const runId=randomUUID(),temporaryName='Проверка пульта · '+runId;
const profile=fs.mkdtempSync(path.join(out,'chrome-'));
const journalPath=path.join(out,'run-'+runId+'.json');
const chrome=spawn(process.env.CHROME||'/usr/bin/google-chrome',[
    '--headless=new','--no-first-run','--no-default-browser-check','--proxy-server=direct://',
    '--remote-debugging-port=0','--user-data-dir='+profile,'about:blank',
],{stdio:['ignore','ignore',fs.openSync(path.join(out,'chrome.log'),'a',0o600)]});
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
let ws,send,ev,before,expectedVersion,temporaryGroup=null,createdBookmark=null;
let expectedTemporaryName=temporaryName,expectedTemporaryIds=[],videoFavoriteChanged=false,childChanged=false;
let concurrencyDetected=false,inFlight=false,finished=false;
const errors=[],observedMutations=[];
const immutablePlayback=s=>({intent:s.intent,status:s.status,currentIndex:s.currentIndex,
    queueItemId:s.items[s.currentIndex]?.queueItemId||null,activeGroupId:s.activeGroupId||null,
    positionMs:s.positionMs,playbackGeneration:s.playbackGeneration});
const itemIdentity=s=>s.items.map(v=>[v.queueItemId,v.videoId]);
const filesIdentity=s=>(s.prepared?.files||[]).map(v=>[v.videoId,v.bytes,v.storageId,v.available]).sort((a,b)=>a[0].localeCompare(b[0]));
const journal=()=>fs.writeFileSync(journalPath,JSON.stringify({runId,expectedVersion,temporaryGroup,
    temporaryName:expectedTemporaryName,temporaryVideoIds:expectedTemporaryIds,createdBookmark,
    videoFavoriteChanged,childChanged,concurrencyDetected,inFlight,finished,
    baseline:before?{playback:immutablePlayback(before),groups:before.programme.groups,
        childScreen:before.programme.childScreen,chosenFavorites:expectedTemporaryIds.map(id=>({videoId:id,
            favorite:before.programme.videos.find(v=>v.videoId===id)?.favorite||false}))}:null},null,2),{mode:0o600});
function assertPlayback(s){
    assert.deepEqual(immutablePlayback(s),immutablePlayback(before),'Playback context changed; leave current playback untouched');
    assert.deepEqual(itemIdentity(s),itemIdentity(before),'Queue items changed; do not remove or reorder user items');
    assert.deepEqual(filesIdentity(s),filesIdentity(before),'Ready files/storage changed; no media cleanup is permitted');
}
async function liveGuard(){
    const live=await ev('api("/api/state","GET")');
    try{
        assert.equal(live.queueVersion,expectedVersion,'Another command changed queueVersion');
        assertPlayback(live);
        assert.deepEqual(live.programme.groups.filter(g=>g.id!==temporaryGroup),before.programme.groups,'An original playlist changed');
    }catch(error){concurrencyDetected=true;journal();throw error;}
    // Put the exact freshly checked snapshot into the UI before clicking a mutation.
    await ev('state='+JSON.stringify(live)+';renderState()');
    return live;
}
async function mutationDiagnostic(since){
    return ev(`(()=>{
        const audit=window.__experiencePostAudit||{next:0,active:0,events:[]};
        const dialog=document.getElementById("experienceDialog");
        return {active:audit.active,events:audit.events.filter(event=>event.sequence>${Number(since)}),
            message:(dialog?.querySelector(":scope > .message")?.textContent||
                document.getElementById("experienceMessage")?.textContent||"").slice(0,240),
            uiIdle:!experienceBusy&&!groupRequestBusy&&!programmeEditor?.busy&&
                ![...dialog?.querySelectorAll(".dialog-footer button")||[]].some(button=>button.disabled)};
    })()`);
}
async function confirmedRejection(since,live){
    const diagnostic=await mutationDiagnostic(since);
    const failure=diagnostic.events.find(event=>event.status>=400&&event.status<500);
    // A network error or timeout is ambiguous. A completed 4xx with unchanged
    // version/context is a definitive rejection, so our earlier edits may be cleaned up.
    if(failure&&diagnostic.active===0&&diagnostic.uiIdle&&live.queueVersion===expectedVersion){
        assertPlayback(live);
        assert.deepEqual(live.programme.groups.filter(g=>g.id!==temporaryGroup),before.programme.groups);
        inFlight=false;journal();
        return `Rejected ${failure.path}: HTTP ${failure.status}${failure.error?' '+failure.error:''}`+
            (diagnostic.message?`; UI: ${diagnostic.message}`:'');
    }
    const uncertain=diagnostic.events.find(event=>event.status===0||event.status>=500);
    if(uncertain&&diagnostic.active===0&&diagnostic.uiIdle){
        // Report server/transport failure immediately, but do not declare the command
        // uncommitted or enable automatic cleanup merely because an error was shown.
        return `Unconfirmed ${uncertain.path}: HTTP ${uncertain.status}${uncertain.error?' '+uncertain.error:''}`+
            (diagnostic.message?`; UI: ${diagnostic.message}`:'')+'; inFlight remains uncertain';
    }
    return null;
}
async function awaitMutation(action,expected=()=>true){
    await liveGuard();
    const auditStart=await ev('window.__experiencePostAudit?.next||0');
    inFlight=true;journal();
    try{
        await action();
        const deadline=Date.now()+45000;
        while(Date.now()<deadline){
            const live=await ev('api("/api/state","GET")');
            if(live.queueVersion===expectedVersion+1&&expected(live)){
                assertPlayback(live);expectedVersion=live.queueVersion;inFlight=false;
                // The API commit can be visible before the UI callback closes its dialog.
                // Do not open the next dialog while that earlier callback is still running.
                for(let n=0;n<60;n++){
                    if(await ev('!experienceBusy&&!groupRequestBusy&&!programmeEditor?.busy'))break;
                    await sleep(100);
                    if(n===59)throw Error('Previous UI action is still busy; stop before the next edit');
                }
                await ev('state='+JSON.stringify(live)+';renderState()');journal();return live;
            }
            if(live.queueVersion>expectedVersion){
                concurrencyDetected=true;journal();throw Error('Unexpected state/version after command; leave changes for review');
            }
            const rejection=await confirmedRejection(auditStart,live);
            if(rejection)throw Error(rejection);
            await sleep(200);
        }
        const diagnostic=await mutationDiagnostic(auditStart);
        const statuses=diagnostic.events.map(event=>`${event.path}:${event.status??'pending'}${event.error?' '+event.error:''}`).join(', ');
        throw Error('Mutation outcome was not confirmed; no automatic rollback of an uncertain command'+
            `; POST: ${statuses||'no request observed'}; UI: ${diagnostic.message||'(no message)'}`);
    }catch(error){
        // A programmeCommand invoked through Runtime.evaluate can reject before the
        // polling loop. Recheck it without treating transport errors as safe failures.
        if(inFlight&&!concurrencyDetected){
            try{const rejected=await confirmedRejection(auditStart,await ev('api("/api/state","GET")'));
                if(rejected)error=Error(rejected);
            }catch{ /* Preserve an uncertain inFlight journal rather than guess. */ }
        }
        journal();throw error;
    }
}
async function cleanup(){
    if(!before||finished||concurrencyDetected||inFlight)return false;
    let live=await liveGuard();
    if(childChanged){
        await awaitMutation(()=>ev('programmeCommand("CHILD_SCREEN",'+JSON.stringify(before.programme.childScreen)+')'),
            s=>JSON.stringify(s.programme.childScreen)===JSON.stringify(before.programme.childScreen));childChanged=false;journal();
    }
    if(createdBookmark){
        const bookmark=createdBookmark;
        await awaitMutation(()=>ev('programmeCommand("BOOKMARK_DELETE",'+JSON.stringify(bookmark)+')'),
            s=>!s.programme.videos.find(v=>v.videoId===bookmark.videoId)?.bookmarks.some(b=>b.id===bookmark.bookmarkId));
        createdBookmark=null;journal();
    }
    if(videoFavoriteChanged){
        const id=expectedTemporaryIds[0],value=before.programme.videos.find(v=>v.videoId===id)?.favorite||false;
        await awaitMutation(()=>ev('programmeCommand("FAVORITE_VIDEO",'+JSON.stringify({videoId:id,value})+')'),
            s=>s.programme.videos.find(v=>v.videoId===id)?.favorite===value);videoFavoriteChanged=false;journal();
    }
    live=await liveGuard();
    if(temporaryGroup){
        const temp=live.programme.groups.find(g=>g.id===temporaryGroup);
        assert(temp,'Own temporary group disappeared');
        assert.equal(temp.name,expectedTemporaryName,'Own temporary group was renamed externally');
        assert.deepEqual(temp.videoIds,expectedTemporaryIds,'Own temporary group membership was edited externally');
        assert.notEqual(live.activeGroupId,temp.id);
        const shared=new Set(live.programme.groups.filter(g=>g.id!==temp.id).flatMap(g=>g.videoIds));
        assert(temp.videoIds.every(id=>shared.has(id)),'Do not remove unshared items');
        await awaitMutation(()=>ev('perform("experience-cleanup-"+commandKey(),"/api/queue/group-remove",'+
            JSON.stringify({groupId:temp.id,queueVersion:expectedVersion,idempotencyKey:randomUUID()})+')'),
            s=>!s.programme.groups.some(g=>g.id===temp.id));temporaryGroup=null;journal();
    }
    const after=await liveGuard();
    assert.deepEqual(after.programme.groups,before.programme.groups);
    assert.deepEqual(after.programme.childScreen,before.programme.childScreen);
    for(const id of expectedTemporaryIds){
        const old=before.programme.videos.find(v=>v.videoId===id),now=after.programme.videos.find(v=>v.videoId===id);
        assert.equal(now.favorite,old.favorite);assert.deepEqual(now.bookmarks,old.bookmarks);
        assert.equal(now.positionMs,old.positionMs);assert.equal(now.status,old.status);
    }
    finished=true;journal();return true;
}

try{
    let port;
    for(let n=0;n<60;n++){try{port=Number(fs.readFileSync(path.join(profile,'DevToolsActivePort'),'utf8').split('\n')[0]);break;}catch{}await sleep(100);}
    if(!port)throw Error('Chrome did not start');
    const pages=await(await fetch('http://127.0.0.1:'+port+'/json')).json();
    ws=new WebSocket(pages.find(page=>page.type==='page').webSocketDebuggerUrl);
    await new Promise((resolve,reject)=>{ws.onopen=resolve;ws.onerror=reject;});
    let seq=0;const pending=new Map();
    ws.onmessage=event=>{
        const message=JSON.parse(event.data);
        if(message.id){const call=pending.get(message.id);pending.delete(message.id);message.error?call.reject(Error(message.error.message)):call.resolve(message.result);}
        else if(message.method==='Runtime.exceptionThrown')errors.push(message.params.exceptionDetails.text);
        else if(message.method==='Network.requestWillBeSent'){
            const request=message.params.request;
            if(request.method==='POST'&&request.url.startsWith(base+'/api/')){
                const url=new URL(request.url);let body={};try{body=JSON.parse(request.postData||'{}');}catch{}
                // Intentionally omit headers, tokens, signed URLs and arbitrary payloads from output.
                observedMutations.push({path:url.pathname,action:body.action||null});
            }
        }
    };
    send=(method,params={})=>new Promise((resolve,reject)=>{const id=++seq;pending.set(id,{resolve,reject});ws.send(JSON.stringify({id,method,params}));});
    ev=async expression=>{const result=await send('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});if(result.exceptionDetails)throw Error('Browser evaluation failed: '+result.exceptionDetails.text);return result.result.value;};
    const wait=async(condition,timeout=45000)=>{const until=Date.now()+timeout;while(Date.now()<until){if(await ev(condition))return;await sleep(200);}throw Error('Browser condition was not confirmed');};
    const click=async selector=>{
        const point=await ev(`(()=>{const e=[...document.querySelectorAll(${JSON.stringify(selector)})].find(e=>!e.disabled&&e.getClientRects().length);if(!e)throw Error('Unavailable control');e.scrollIntoView({block:'center'});const r=e.getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2}})()`);
        await send('Input.dispatchMouseEvent',{type:'mousePressed',button:'left',clickCount:1,...point});
        await send('Input.dispatchMouseEvent',{type:'mouseReleased',button:'left',clickCount:1,...point});
    };
    const screenshot=async name=>{const result=await send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});fs.writeFileSync(path.join(out,name+'.png'),Buffer.from(result.data,'base64'),{mode:0o600});};
    await send('Runtime.enable');await send('Page.enable');await send('Network.enable');
    await send('Page.addScriptToEvaluateOnNewDocument',{source:`(()=>{
        const originalFetch=window.fetch.bind(window);
        const audit=window.__experiencePostAudit={next:0,active:0,events:[]};
        window.fetch=async(...args)=>{
            const path=new URL(typeof args[0]==="string"?args[0]:args[0].url,location.href).pathname;
            if(args[1]?.method!=="POST"||!path.startsWith("/api/"))return originalFetch(...args);
            const event={sequence:++audit.next,path,status:null,error:null};
            audit.events.push(event);if(audit.events.length>500)audit.events.shift();audit.active++;
            try{
                const response=await originalFetch(...args);event.status=response.status;
                try{const body=await response.clone().json();
                    if(typeof body.error==="string"&&/^[A-Z0-9_]{1,80}$/.test(body.error))event.error=body.error;
                }catch{}
                return response;
            }catch(error){event.status=0;event.error="NETWORK_OR_ABORT";throw error;}
            finally{audit.active--;}
        };
    })()`});
    await send('Emulation.setDeviceMetricsOverride',{width:1280,height:1000,deviceScaleFactor:1,mobile:false});
    await send('Page.navigate',{url:base+'/'});await sleep(700);
    await ev('localStorage.setItem("parentcast.device.v1",'+JSON.stringify(JSON.stringify(device))+');location.reload()');
    await wait('typeof state!=="undefined"&&!!state?.programme&&typeof openProgrammeEditor==="function"');
    before=await ev('api("/api/state","GET")');
    assert.equal(before.intent,'PAUSE','Run only after root confirms PAUSE baseline');
    assert(before.programme.groups.length>0,'Existing groups required');
    assert(!before.prepared?.clearing,'A file cleanup is currently running');
    const shared=new Set(before.programme.groups.flatMap(group=>group.videoIds));
    const current=before.items[before.currentIndex]?.videoId;
    const chosen=before.items.filter(item=>item.videoId!==current&&shared.has(item.videoId)&&item.ready===true&&
        before.prepared.files.some(file=>file.videoId===item.videoId&&file.available!==false)).slice(0,2);
    assert.equal(chosen.length,2,'Need two already prepared, non-current videos shared with existing groups');
    expectedTemporaryIds=chosen.map(v=>v.videoId);expectedVersion=before.queueVersion;journal();
    await ev('state='+JSON.stringify(before)+';renderState();showFamilyPage("library")');
    await screenshot('baseline-desktop');
    // CREATE_GROUP through the actual new-playlist dialog.
    await ev('editGroup(null);document.querySelector("#experienceDialog input").value='+JSON.stringify(temporaryName)+';'+
        '[...document.querySelectorAll(".new-group-choices input")].forEach((box,index)=>{const wanted='+JSON.stringify(expectedTemporaryIds)+'.includes(state.items[index].videoId);if(box.checked!==wanted)box.click();})');
    const created=await awaitMutation(()=>click('#experienceDialog .dialog-footer button'),s=>s.programme.groups.some(g=>g.name===temporaryName));
    const added=created.programme.groups.filter(g=>!before.programme.groups.some(old=>old.id===g.id));
    assert.equal(added.length,1);temporaryGroup=added[0].id;assert.deepEqual(added[0].videoIds,expectedTemporaryIds);journal();
    await ev('libraryGroupId='+JSON.stringify(temporaryGroup)+';programmeViewKey=null;renderQueue()');
    // Rename and group favorite in one exact versioned UI transaction.
    expectedTemporaryName=temporaryName+' · проверено';journal();
    await ev('editGroup('+JSON.stringify(temporaryGroup)+');document.querySelector("#experienceDialog input").value='+JSON.stringify(expectedTemporaryName)+';document.querySelector("#experienceDialog input[type=checkbox]").click()');
    await awaitMutation(()=>click('#experienceDialog .dialog-footer button'),s=>s.programme.groups.find(g=>g.id===temporaryGroup)?.favorite===true);
    // Frame cover is derived from a READY file; no video/media file is deleted.
    await ev('coverDialog('+JSON.stringify(temporaryGroup)+');document.querySelector("#experienceDialog input[type=number]").value="0"');
    await awaitMutation(()=>click('#experienceDialog .dialog-footer button:nth-child(2)'),s=>/^[a-f0-9]{64}$/.test(s.programme.groups.find(g=>g.id===temporaryGroup)?.coverImageId||''));
    await wait('!document.getElementById("experienceDialog").open');
    await send('Emulation.setDeviceMetricsOverride',{width:390,height:1000,deviceScaleFactor:1,mobile:true});
    await screenshot('temporary-playlist-mobile');
    // Reorder only the temporary playlist, then exact-ID logical undo.
    await ev('openProgrammeEditor('+JSON.stringify(temporaryGroup)+');moveEditor('+JSON.stringify(expectedTemporaryIds[0])+',1)');
    const reversed=expectedTemporaryIds.slice().reverse();
    await awaitMutation(()=>click('#experienceOrderSave'),s=>JSON.stringify(s.programme.groups.find(g=>g.id===temporaryGroup)?.videoIds)===JSON.stringify(reversed));
    const undoState=await ev('state');assert(undoState.programme.undo.available);
    expectedTemporaryIds=reversed;journal();
    const original=chosen.map(v=>v.videoId);
    await awaitMutation(()=>click('#programmeUndo'),s=>JSON.stringify(s.programme.groups.find(g=>g.id===temporaryGroup)?.videoIds)===JSON.stringify(original));
    expectedTemporaryIds=original;journal();
    // Individual favorite and bookmark are restored independently of group undo.
    const favoriteVideo=original[0],oldFavorite=before.programme.videos.find(v=>v.videoId===favoriteVideo).favorite;
    await awaitMutation(()=>ev('programmeCommand("FAVORITE_VIDEO",'+JSON.stringify({videoId:favoriteVideo,value:!oldFavorite})+')'),s=>s.programme.videos.find(v=>v.videoId===favoriteVideo)?.favorite===!oldFavorite);
    videoFavoriteChanged=true;journal();
    await awaitMutation(()=>ev('programmeCommand("FAVORITE_VIDEO",'+JSON.stringify({videoId:favoriteVideo,value:oldFavorite})+')'),s=>s.programme.videos.find(v=>v.videoId===favoriteVideo)?.favorite===oldFavorite);
    videoFavoriteChanged=false;journal();
    const bookmarkName='Проверка '+runId;
    await ev('bookmarkDialog('+JSON.stringify(favoriteVideo)+');document.querySelector("#experienceDialog input").value='+JSON.stringify(bookmarkName)+';document.querySelector("#experienceDialog input[type=number]").value="0"');
    const bookmarked=await awaitMutation(()=>click('#experienceDialog .dialog-footer button'),s=>s.programme.videos.find(v=>v.videoId===favoriteVideo)?.bookmarks.some(b=>b.name===bookmarkName));
    const bookmark=bookmarked.programme.videos.find(v=>v.videoId===favoriteVideo).bookmarks.find(b=>b.name===bookmarkName);
    createdBookmark={videoId:favoriteVideo,bookmarkId:bookmark.id};journal();
    await awaitMutation(()=>ev('(()=>{const row=[...document.querySelectorAll(".bookmark-row")].find(row=>row.textContent.includes('+JSON.stringify(bookmarkName)+'));row.querySelectorAll("button")[1].click();})()'),s=>!s.programme.videos.find(v=>v.videoId===favoriteVideo)?.bookmarks.some(b=>b.id===bookmark.id));
    createdBookmark=null;journal();await ev('document.getElementById("experienceDialog").close()');
    if(process.env.EXPERIENCE_CHILD_TEST==='1'){
        await ev('showFamilyPage("settings");document.getElementById("childScreenEnabled").checked=!state.programme.childScreen.enabled;document.getElementById("childScreenEnabled").dispatchEvent(new Event("change",{bubbles:true}));');
        await awaitMutation(()=>ev('saveChildSettings()'),s=>s.programme.childScreen.enabled!==before.programme.childScreen.enabled);
        childChanged=true;journal();
        await awaitMutation(()=>ev('programmeCommand("CHILD_SCREEN",'+JSON.stringify(before.programme.childScreen)+')'),s=>JSON.stringify(s.programme.childScreen)===JSON.stringify(before.programme.childScreen));childChanged=false;journal();
    }
    for(const width of [320,390]){
        await send('Emulation.setDeviceMetricsOverride',{width,height:1000,deviceScaleFactor:1,mobile:true});
        await ev('showFamilyPage("library")');assert.equal(await ev('document.documentElement.scrollWidth'),width);
    }
    assert(await cleanup(),'Cleanup was not completed');
    const prohibited=observedMutations.filter(r=>r.path.startsWith('/api/playback')||r.path==='/api/queue/select'||
        r.path==='/api/prepared'||r.path==='/api/video/apply'||r.path==='/api/storage'||r.path.startsWith('/api/local'));
    assert.deepEqual(prohibited,[],'This scenario must never play/download/delete media or select storage');
    assert.deepEqual(errors,[]);
    console.log(JSON.stringify({result:'PASS',scenario:'real Chrome/H728 programme experience',temporaryGroupRemoved:true,
        readyMediaPreserved:true,originalGroupsPreserved:true,originalPauseAndPositionPreserved:true,
        frameCover:'PASS',renameFavorite:'PASS',reorderUndo:'PASS',bookmarkCreateDelete:'PASS',
        childSettings:process.env.EXPERIENCE_CHILD_TEST==='1'?'PASS restored':'NOT_RUN',
        mobileWidths:[320,390],javascriptExceptions:0,journal:journalPath}));
}catch(error){
    console.error('Hardware browser scenario stopped: '+error.message);
    process.exitCode=1;
}finally{
    if(before&&!finished&&ev){
        try{if(await cleanup())console.log('Own temporary changes safely removed after failure');
            else console.log('Automatic cleanup skipped: concurrent change or uncertain mutation; inspect protected journal '+journalPath);}
        catch{console.log('Cleanup needs review; no unrelated item or media file was removed. Journal: '+journalPath);}
    }
    if(send)await send('Browser.close').catch(()=>{});
    ws?.close();if(chrome.exitCode===null)chrome.kill('SIGTERM');
}
