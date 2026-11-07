// Actual frontend script in a minimal DOM. Synthetic test; no YouTube/H728.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import {webcrypto} from 'node:crypto';
const elements = new Map();
const element = () => ({value:'',textContent:'',hidden:false,disabled:false,dataset:{},children:[],
    append(...items){this.children.push(...items)}, replaceChildren(){this.children=[]}, addEventListener(){}});
const ctx=vm.createContext({document:{getElementById(id){if(!elements.has(id))elements.set(id,element());return elements.get(id)},createElement:element,querySelectorAll:()=>[]},
    localStorage:{getItem:()=>null,setItem(){},removeItem(){}},crypto:webcrypto,setInterval(){},setTimeout(){},clearTimeout(){}});
vm.runInContext(fs.readFileSync('app/src/main/assets/web/app.js','utf8'),ctx);
vm.runInContext(`state={status:'LOADING',prepared:{enabled:true,paused:true,ready:1,count:2,entries:[
    {index:1,title:'<img src=x onerror=alert(1)>',status:'READY',bytes:1048576},
    {index:2,videoId:'video000002',status:'ERROR',bytes:0,error:'YOUTUBE_AUTH_REQUIRED'}]}};renderPrepared()`,ctx);
assert(elements.get('preparedSummary').textContent.includes('Готово 1 из 2'));
assert(elements.get('preparedSummary').textContent.includes('остановлена'));
const rows=elements.get('preparedEntries').children;
assert(rows[0].textContent.includes('<img src=x onerror=alert(1)>'));
assert(!('innerHTML' in rows[0]),'Untrusted titles must remain text');
assert(rows[1].textContent.includes('YouTube требует авторизацию'));
assert(elements.get('playerStatus').textContent.includes('полностью подготовленного файла'));
const html=fs.readFileSync('app/src/main/assets/web/index.html','utf8');
for(const action of ['ENABLE','DISABLE','PAUSE','RETRY','CLEANUP']) assert(html.includes(`data-prepared="${action}"`));
console.log('PASS: prepared readiness, paused/auth failure, safe title rendering, local-only wait and controls (synthetic)');
