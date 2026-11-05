"use strict";
let timelineEdit=null;
let timelineBusy=false;
let timelineStaleEdit=false;
let timelineSample=null;
function timelineIdentity(s) {return [s?.items?.[s.currentIndex]?.queueItemId,s?.queueVersion,s?.playbackGeneration].join("|");}
function timelinePosition() {
    if(!timelineSample)return 0;
    const extra=timelineSample.playing && !document.hidden ? Math.max(0,Date.now()-timelineSample.at) : 0;
    return Math.min(timelineSample.duration || Infinity,timelineSample.position+extra);
}
function renderTimeline() {
    if(!state){byId("playheadSlider").disabled=true;byId("seekBack").disabled=true;byId("seekForward").disabled=true;return;}
    if(timelineEdit && timelineEdit.identity!==timelineIdentity(state)) {
        if(!timelineBusy)timelineStaleEdit=true;
        timelineEdit=null;byId("seekMessage").textContent="Текущий ролик или программа изменились. Проверьте новую позицию.";
    }
    timelineSample={position:Number(state.positionMs)||0,duration:Number(state.durationMs)||0,playing:state.status==="PLAYING" && state.intent==="PLAY",at:Date.now()};
    const enabled=state.seekable===true && timelineSample.duration>0 && !timelineBusy;
    for(const id of ["playheadSlider","seekBack","seekForward"])byId(id).disabled=!enabled;
    byId("playheadSlider").max=timelineSample.duration>0?timelineSample.duration/1000:1;
    byId("videoDuration").textContent=timelineSample.duration>0?resumeTime(timelineSample.duration):"—:—";
    if(!state.seekable && !timelineBusy)byId("seekMessage").textContent=timelineSample.duration>0?"Перемотка станет доступна после загрузки ролика в плеер.":"Получаем длительность ролика…";
    updateTimelineDisplay();
}
function updateTimelineDisplay() {
    if(!state)return;
    const position=timelineEdit?timelineEdit.position:timelinePosition();
    byId("playheadTime").textContent=resumeTime(position);
    if(!timelineEdit && !timelineBusy)byId("playheadSlider").value=position/1000;
    byId("playheadSlider").setAttribute("aria-valuetext",`${resumeTime(position)} из ${byId("videoDuration").textContent}`);
}
function beginTimelineEdit() {
    if(!state?.seekable || timelineBusy || timelineStaleEdit)return;
    if(!timelineEdit)timelineEdit={identity:timelineIdentity(state),queueItemId:state.items[state.currentIndex].queueItemId,queueVersion:state.queueVersion,playbackGeneration:state.playbackGeneration,position:timelinePosition()};
}
async function seekTimeline(position) {
    if(timelineStaleEdit){timelineStaleEdit=false;timelineEdit=null;renderTimeline();return;}
    beginTimelineEdit();
    if(!timelineEdit || timelineBusy)return;
    const edit=timelineEdit;
    if(edit.identity!==timelineIdentity(state)){timelineEdit=null;renderTimeline();return;}
    edit.position=Math.max(0,Math.min(Number(state.durationMs)||0,Math.round(position)));
    timelineBusy=true;byId("seekMessage").textContent="Перематываем…";renderTimeline();
    try {
        const result=await perform("seek-"+commandKey(),"/api/playback/seek",{queueItemId:edit.queueItemId,queueVersion:edit.queueVersion,playbackGeneration:edit.playbackGeneration,positionMs:edit.position,idempotencyKey:commandKey()});
        if(!state || (result.queueVersion>=state.queueVersion && result.playbackGeneration>=state.playbackGeneration))state=result;
        byId("seekMessage").textContent="Позиция сохранена";
    }catch(error){byId("seekMessage").textContent=apiErrorText(error);}
    finally{timelineBusy=false;timelineEdit=null;if(state)renderTimeline();}
}
byId("playheadSlider").addEventListener("pointerdown",()=>{if(!timelineBusy){timelineStaleEdit=false;timelineEdit=null;beginTimelineEdit();}});
byId("playheadSlider").addEventListener("keydown",event=>{if(!event.repeat && !timelineBusy){timelineStaleEdit=false;beginTimelineEdit();}});
byId("playheadSlider").addEventListener("input",()=>{beginTimelineEdit();if(timelineEdit){timelineEdit.position=Number(byId("playheadSlider").value)*1000;updateTimelineDisplay();}});
byId("playheadSlider").addEventListener("change",()=>run(()=>seekTimeline(Number(byId("playheadSlider").value)*1000)));
byId("playheadSlider").addEventListener("pointercancel",()=>{timelineEdit=null;timelineStaleEdit=false;renderTimeline();});
byId("seekBack").addEventListener("click",()=>run(()=>seekTimeline(timelinePosition()-10000)));
byId("seekForward").addEventListener("click",()=>run(()=>seekTimeline(timelinePosition()+10000)));
setInterval(()=>{if(!document.hidden)updateTimelineDisplay();},250);
if(state)renderTimeline();
