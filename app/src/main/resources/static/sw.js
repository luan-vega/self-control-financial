// Service worker: só guarda os arquivos estáticos para abrir mais rápido.
// Páginas e dados financeiros sempre vêm da rede (nada sensível em cache).
const CACHE = "self-control-static-v3";
const STATIC = ["/css/app.css", "/js/app.js", "/icons/icon.svg", "/webjars/htmx.org/dist/htmx.min.js"];

self.addEventListener("install", (event) => {
    event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(STATIC)));
    self.skipWaiting();
});

self.addEventListener("activate", (event) => {
    event.waitUntil(
        caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
    );
    self.clients.claim();
});

self.addEventListener("fetch", (event) => {
    const url = new URL(event.request.url);
    if (event.request.method === "GET" && STATIC.includes(url.pathname)) {
        event.respondWith(caches.match(event.request).then((hit) => hit || fetch(event.request)));
    }
});
