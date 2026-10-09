// GTA Spotify Radio service worker: keeps the app shell available and installable.
// Network first, so a new version of the page is picked up as soon as you are online.
const CACHE = "manu-fm-v23";
const SHELL = ["./", "./index.html", "./manifest.webmanifest", "./icon-192.png", "./icon-512.png","./hover.wav","./switch.mp3"].concat(Array.from({length:29},(_,i)=>"./logos/"+String(i+1).padStart(2,"0")+".png"));

self.addEventListener("install", e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(SHELL)).catch(() => {}));
  self.skipWaiting();
});

self.addEventListener("activate", e => {
  e.waitUntil(
    caches.keys()
      .then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", e => {
  const u = new URL(e.request.url);
  if (e.request.method !== "GET" || u.origin !== location.origin) return;   // never touch Spotify requests
  if (u.search) return;                                                      // e.g. the ?code= login return
  e.respondWith(
    fetch(e.request)
      .then(r => { const copy = r.clone(); caches.open(CACHE).then(c => c.put(e.request, copy)); return r; })
      .catch(() => caches.match(e.request))
  );
});
