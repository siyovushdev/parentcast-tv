// Synthetic DOM: group identity, readiness, safe names and explicit commands. Not hardware playback.
import assert from 'node:assert/strict';import fs from 'node:fs';import vm from 'node:vm';import {webcrypto} from 'node:crypto';
const elements=new Map();
const element=tag=>({tagName:tag,children:[],dataset:{},handlers:{},textContent:'',value:'',hidden:false,disabled:false,
 setAttribute(k,v){this[k]=v},append(...nodes){this.children.push(...nodes)},replaceChildren(){this.children=[]},addEventListener(k,f){(this.handlers[k]??=[]).push(f)},
 querySelectorAll(selector){const all=this.children.flatMap(n=>[n,...(n.querySelectorAll?.(selector)||[])]);return all.filter(n=>selector.includes('watch-video')?n.dataset.groupAction==='watch-video':true)}});
const document={getElementById(id){if(!elements.has(id))elements.set(id,element('div'));return elements.get(id)},createElement:element,querySelectorAll:()=>[]};
const ctx=vm.createContext({document,localStorage:{getItem:()=>null,setItem(){},removeItem(){}},crypto:webcrypto,setInterval(){},setTimeout(){},clearTimeout(){},confirm:()=>false});
for(const name of ['app','media','groups'])vm.runInContext(fs.readFileSync(`app/src/main/assets/web/${name}.js`,'utf8'),ctx);
vm.runInContext(`state={groups:[{id:'group-one',name:'<script>Название</script>',source:'YOUTUBE_PLAYLIST',videoIds:['video000001','video000002']},{id:'group-two',name:'VK',source:'VK_PLAYLIST',videoIds:['video000001']}],items:[{queueItemId:'a',videoId:'video000001',title:'Готовый',ready:true},{queueItemId:'b',videoId:'video000002',title:'Следующий',ready:false}],currentIndex:0,activeGroupId:'group-one',queueVersion:7,prepared:{enabled:true,files:[{videoId:'video000001'}],entries:[{videoId:'video000002',status:'DOWNLOADING',percent:20}]}};var sent=[];perform=async(key,path,payload)=>{sent.push({path,payload});return {}};renderQueue()`,ctx);
const cards=elements.get('queue').children;assert.equal(cards.length,2);assert(cards[0].children[0].children[0].textContent.includes('<script>'));assert(!('innerHTML' in cards[0]));
const rows=cards[0].children.filter(n=>n.className?.startsWith('queue-item'));assert.equal(rows[0].children[1].textContent,'Смотреть');assert.equal(rows[1].children[1].disabled,true);assert.equal(rows[1].children[1].textContent,'Загружается · 20%');
vm.runInContext('state.prepared.entries[0].percent=55;renderQueue()',ctx);assert.equal(elements.get('queue').children[0],cards[0],'Progress polling preserves card DOM/focus');assert.equal(rows[1].children[1].textContent,'Загружается · 55%');
await vm.runInContext(`programmeAction({dataset:{groupAction:'watch-video',groupId:'group-one',itemId:'b'}})`,ctx);assert.equal(vm.runInContext('sent.length',ctx),0,'Unready video must not issue PLAY');
await vm.runInContext(`programmeAction({dataset:{groupAction:'prepare',groupId:'group-two'}})`,ctx);
let request=JSON.parse(vm.runInContext('JSON.stringify(sent.at(-1))',ctx));assert.equal(request.path,'/api/prepared');assert.equal(request.payload.groupId,'group-two');assert.equal(request.payload.action,'ENABLE');
await vm.runInContext(`programmeAction({dataset:{groupAction:'play',groupId:'group-one'}})`,ctx);
request=JSON.parse(vm.runInContext('JSON.stringify(sent.at(-1))',ctx));assert.equal(request.path,'/api/queue/select');assert.equal(request.payload.groupId,'group-one');assert.equal(request.payload.queueItemId,'a');assert.equal(request.payload.play,true);
await vm.runInContext(`programmeAction({dataset:{groupAction:'down',groupId:'group-one'}})`,ctx);
request=JSON.parse(vm.runInContext('JSON.stringify(sent.at(-1))',ctx));assert.equal(request.path,'/api/queue/group-order');assert.deepEqual(request.payload.groupIds,['group-two','group-one']);assert.equal(request.payload.queueVersion,7);
vm.runInContext('startQueueOrder("group-one");moveDraft("b",0);byId("queueSortMode").value="ready";sortDraft()',ctx);assert.equal(vm.runInContext('queueOrder.ids[0]',ctx),'a','Readiness sorting includes cached files outside the current download plan');vm.runInContext('moveDraft("b",0)',ctx);await vm.runInContext('applyQueueOrder()',ctx);
request=JSON.parse(vm.runInContext('JSON.stringify(sent.at(-1))',ctx));assert.equal(request.path,'/api/queue/group-order');assert.deepEqual(request.payload.videoIds,['video000002','video000001']);assert.equal(request.payload.groupId,'group-one');
vm.runInContext('state.positionMs=42000;state.finished=false;programmeViewKey=null;renderQueue()',ctx);
const resumedRows=elements.get('queue').children[0].children.filter(n=>n.className?.startsWith('queue-item'));
assert.equal(resumedRows[0].children[1].textContent,'Продолжить с 0:42');
await vm.runInContext(`programmeAction({dataset:{groupAction:'watch-video',groupId:'group-one',itemId:'a'}})`,ctx);
request=JSON.parse(vm.runInContext('JSON.stringify(sent.at(-1))',ctx));assert.equal(request.path,'/api/playback');assert.equal(request.payload.action,'PLAY');assert.equal(request.payload.positionMs,undefined);
vm.runInContext(`state.items=[{queueItemId:'one',videoId:'video000001',title:'История Пророков #1: Начало'},
 {queueItemId:'two',videoId:'video000002',title:'История Пророков #2: Продолжение'},
 {queueItemId:'ten',videoId:'video000010',title:'История Пророков #10: Ещё'},
 {queueItemId:'extra',videoId:'video000011',title:'Премьера 2024'},
 {queueItemId:'final',videoId:'video000012',title:'История Пророков - Конец'}];
 state.groups=[{id:'series',name:'История',videoIds:['video000010','video000011','video000002','video000001','video000012']}];
 state.currentIndex=2;state.activeGroupId='series';state.positionMs=12345;
 startQueueOrder('series');byId('queueSortMode').value='episode';sortDraft()`,ctx);
assert.deepEqual(JSON.parse(vm.runInContext('JSON.stringify(queueOrder.ids)',ctx)),['one','two','ten','extra','final']);
assert.deepEqual(JSON.parse(vm.runInContext('JSON.stringify(state.groups[0].videoIds)',ctx)),['video000010','video000011','video000002','video000001','video000012'],'Sorting remains a draft until saved');
for(const [title,expected] of [['Урок 10',10],['Серия №2',2],['История #31: Муса',31],['Выпуск: 7',7],['Как Ной 950 Лет',null],['Премьера 2024',null],['Без названия',null]])assert.equal(vm.runInContext(`episodeNumber(${JSON.stringify(title)})`,ctx),expected);
await vm.runInContext('applyQueueOrder()',ctx);
request=JSON.parse(vm.runInContext('JSON.stringify(sent.at(-1))',ctx));assert.deepEqual(request.payload.videoIds,['video000001','video000002','video000010','video000011','video000012']);
assert.equal(request.payload.groupId,'series');assert.equal(request.payload.queueVersion,7);
assert.equal(vm.runInContext('state.positionMs',ctx),12345);assert.equal(vm.runInContext('state.currentIndex',ctx),2);
console.log('PASS: playlist cards, shared video references, readiness gate, safe titles, stable progress DOM, explicit group preparation/start/order and video draft (synthetic)');
