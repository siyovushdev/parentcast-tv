"use strict";
// Images use authenticated fetch; tokens never enter URLs or external image requests.
const thumbnailImages = new Map();
const thumbnailRequests = new Map();
const thumbnailWatching = new Set();
let thumbnailQueue = [];
let thumbnailActive = 0;
let thumbnailEpoch = 0;
const thumbnailObserver = typeof IntersectionObserver === "function" ? new IntersectionObserver(entries => {
    for (const entry of entries) if(entry.isIntersecting) {
        thumbnailObserver.unobserve(entry.target);const img=entry.target.querySelector("img[data-video-thumbnail]");if(img)requestThumbnail(img);
    }
}, {rootMargin: "120px"}) : null;

function addVideoThumbnail(container, id, title) {
    if(typeof id !== "string" || !/^(?:[A-Za-z0-9_-]{11}|vk:-?[0-9]{1,20}_[0-9]{1,20}|local_[0-9a-f]{32})$/.test(id))return;
    container.className += " has-video-thumbnail";
    const box = document.createElement("span");box.className="video-thumbnail";
    const placeholder = document.createElement("span");placeholder.className="thumbnail-placeholder";placeholder.textContent="Превью";
    const img = document.createElement("img");img.width=160;img.height=90;img.alt="Превью: "+(title || "Видео");
    // Visibility is already handled by the wrapper observer. A hidden lazy img cannot finish loading.
    img.decoding="async";img.loading="eager";img.referrerPolicy="no-referrer";img.hidden=true;img.dataset.videoThumbnail=id;
    img.addEventListener("load",()=>{img.hidden=false;placeholder.hidden=true;});
    img.addEventListener("error",()=>{img.hidden=true;placeholder.hidden=false;placeholder.textContent="Нет превью";});
    box.append(placeholder,img);container.append(box);
    thumbnailWatching.add(img);
    if(thumbnailObserver)thumbnailObserver.observe(box);else setTimeout(()=>requestThumbnail(img),0);
}
function requestThumbnail(img) {
    if(!img.isConnected || typeof device === "undefined" || !device)return;
    const id=img.dataset.videoThumbnail;
    const cached=thumbnailImages.get(id);
    if(cached) {img.src=cached;return;}
    let promise=thumbnailRequests.get(id);
    if(!promise) {
        const epoch=thumbnailEpoch;
        promise=new Promise(resolve=>{thumbnailQueue.push({id,epoch,resolve});pumpThumbnails();});
        thumbnailRequests.set(id,promise);
        promise.finally(()=>{if(thumbnailRequests.get(id)===promise)thumbnailRequests.delete(id);});
    }
    const epoch=thumbnailEpoch;
    promise.then(image=>{
        if(!img.isConnected || epoch!==thumbnailEpoch)return;
        if(image)img.src=image;
        else {const text=img.parentElement?.querySelector(".thumbnail-placeholder");if(text)text.textContent="Нет превью";}
    });
}
function pumpThumbnails() {
    while(thumbnailActive<2 && thumbnailQueue.length) {
        const job=thumbnailQueue.shift();thumbnailActive++;
        loadThumbnail(job.id,job.epoch).then(job.resolve,()=>job.resolve(null)).finally(()=>{thumbnailActive--;pumpThumbnails();});
    }
}
async function loadThumbnail(id,epoch) {
    for(let attempt=0;attempt<16;attempt++) {
        if(epoch!==thumbnailEpoch || !device)return null;
        const token=device.token;
        const controller=new AbortController();const timer=setTimeout(()=>controller.abort(),12000);
        let response;
        try { response=await fetch("/api/thumbnails/"+encodeURIComponent(id),{headers:{Authorization:"Bearer "+token,"X-ParentCast-Client":"m1"},credentials:"omit",cache:"no-store",signal:controller.signal}); }
        finally {clearTimeout(timer);}
        if(epoch!==thumbnailEpoch || !device || device.token!==token)return null;
        if(response.status===202) {await new Promise(resolve=>setTimeout(resolve,1800));continue;}
        if(!response.ok)return null;
        const data=await response.json();
        if(typeof data.image!=="string" || data.image.length>131100 || !/^data:image\/jpeg;base64,[A-Za-z0-9+/=]+$/.test(data.image))return null;
        thumbnailImages.delete(id);thumbnailImages.set(id,data.image);
        while(thumbnailImages.size>120)thumbnailImages.delete(thumbnailImages.keys().next().value);
        return data.image;
    }
    return null;
}
function clearThumbnails() {
    thumbnailEpoch++;thumbnailImages.clear();thumbnailRequests.clear();
    for(const job of thumbnailQueue)job.resolve(null);
    thumbnailQueue=[];
    for(const img of thumbnailWatching) {img.removeAttribute("src");img.hidden=true;thumbnailObserver?.unobserve(img.parentElement);}
    thumbnailWatching.clear();
}
if(typeof MutationObserver === "function")new MutationObserver(()=>{
    for(const img of thumbnailWatching)if(!img.isConnected){thumbnailObserver?.unobserve(img.parentElement);thumbnailWatching.delete(img);}
}).observe(document.documentElement,{childList:true,subtree:true});
