// Synthetic browser contract: safe rendering/authenticated images/resume; no claim of real provider access.
import assert from 'node:assert/strict';import fs from 'node:fs';import vm from 'node:vm';
const images=[],calls=[];
function element(tag){return {tagName:tag,children:[],dataset:{},className:'',isConnected:true,handlers:{},
 append(...nodes){for(const n of nodes)n.parentElement=this;this.children.push(...nodes)},
 addEventListener(n,f){this.handlers[n]=f},removeAttribute(n){delete this[n]},querySelector(selector){return this.children.find(c=>selector.includes('img')?c.tagName==='img':c.className==='thumbnail-placeholder')}};}
let visible;
const ctx=vm.createContext({document:{createElement:element,documentElement:{}},IntersectionObserver:class{constructor(cb){visible=cb}observe(e){assert.equal(e.hidden,undefined,'Lazy observer needs a visible wrapper, not a hidden image');images.push(e.children.find(c=>c.tagName==='img'))}unobserve(){}},MutationObserver:class{observe(){}},setTimeout,clearTimeout,AbortController,fetch:async(path,options)=>{
 calls.push({path,options});return {status:200,ok:true,json:async()=>({image:'data:image/jpeg;base64,/9j/2Q=='})};
}});
vm.runInContext('let device={token:"secret-test-token"};',ctx);
vm.runInContext(fs.readFileSync('app/src/main/assets/web/thumbnails.js','utf8'),ctx);
ctx.parent=element('div');
vm.runInContext('addVideoThumbnail(parent,"video000001","<img onerror=evil()>");addVideoThumbnail(parent,"video000001","Повтор");addVideoThumbnail(parent,"../../private","Путь")',ctx);
assert.equal(images.length,2);assert.equal(images[0].loading,'eager','The fetched hidden image must decode eagerly; wrapper handles lazy scheduling');assert.equal(calls.length,0,'Collapsed/offscreen images must not start network work');assert.equal(images[0].alt,'Превью: <img onerror=evil()>');assert(!('innerHTML' in images[0]));
visible(images.map(img=>({target:img.parentElement,isIntersecting:true})));await new Promise(r=>setTimeout(r,20));
assert.equal(calls.length,1,'Shared video image request is deduplicated');assert.equal(calls[0].options.headers.Authorization,'Bearer secret-test-token');assert.equal(calls[0].path,'/api/thumbnails/video000001');assert(!calls[0].path.includes('secret'));
assert.equal(images[0].src,'data:image/jpeg;base64,/9j/2Q==');assert.equal(images[1].src,images[0].src);
vm.runInContext('clearThumbnails()',ctx);assert.equal(images[0].src,undefined);assert.equal(vm.runInContext('thumbnailImages.size',ctx),0);
ctx.fetch=async()=>({status:200,ok:true,json:async()=>({image:'data:image/svg+xml,<svg onload=evil()>'})});
vm.runInContext('addVideoThumbnail(parent,"video000002","Новый")',ctx);visible([{target:images.at(-1).parentElement,isIntersecting:true}]);await new Promise(r=>setTimeout(r,20));assert.equal(images.at(-1).src,undefined,'Untrusted SVG/HTML never enters image src');
const html=fs.readFileSync('app/src/main/assets/web/index.html','utf8');assert(html.indexOf('/thumbnails.js')<html.indexOf('/app.js'));assert(html.includes('id="resumePlayback"'));
console.log('PASS: lazy authenticated thumbnails, duplicate requests, text-only titles, no token URL, cache clearing and image MIME validation (synthetic)');
