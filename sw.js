// Service worker minimal pour EarthShade : rend l'app "installable"
// (PWA) et garde une copie de la page pour un chargement quasi-instantané.
// Les données viennent toujours de Supabase en direct — ce service worker
// ne met en cache QUE la page elle-même (index.html), jamais les appels
// réseau vers Supabase (auth, base de données, temps réel), pour ne
// jamais servir de données périmées.
// Version 1.1 : cache renommé + page toujours revérifiée auprès du serveur
// (cache: 'no-cache'), pour que chaque mise en ligne soit prise en compte
// sans avoir à effacer les données du site.
// >>> Changer CACHE_NAME à chaque nouvelle version d'EarthShade <<<
const CACHE_NAME = 'earthshade-v1.3';
const APP_SHELL = ['./', './index.html'];

self.addEventListener('install', (event) => {
  self.skipWaiting();
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => cache.addAll(APP_SHELL)).catch(() => {})
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE_NAME).map((k) => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  // Ne jamais intercepter les requêtes vers un autre domaine (Supabase,
  // polices, etc.) — uniquement la page de l'app elle-même.
  if (req.method !== 'GET' || new URL(req.url).origin !== self.location.origin) return;

  event.respondWith(
    // 'no-cache' : le navigateur redemande toujours au serveur si la page
    // a changé, au lieu de resservir une vieille copie.
    fetch(req, { cache: 'no-cache' })
      .then((res) => {
        const copy = res.clone();
        caches.open(CACHE_NAME).then((cache) => cache.put(req, copy)).catch(() => {});
        return res;
      })
      .catch(() => caches.match(req).then((cached) => cached || caches.match('./index.html')))
  );
});
