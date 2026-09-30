// EarthShade — service worker (réécrit pour la 2.0, 30/09/2026)
//
// Rôle : garder une copie de l'appli elle-même (la page, le manifeste, les icônes)
// pour qu'elle s'ouvre vite, même avec un mauvais réseau.
// Les données (Supabase), les polices et les bibliothèques extérieures ne sont
// JAMAIS mises en cache ici : l'agenda et les listes sont toujours lus en direct.
//
// Fonctionnement : on affiche la copie gardée tout de suite, et on télécharge la
// nouvelle version en arrière-plan. D'où la règle : après une mise en ligne,
// fermer et rouvrir l'appli deux fois (la 1re fois télécharge, la 2e affiche).
//
// À CHAQUE NOUVELLE VERSION : changer le numéro ci-dessous (ex. earthshade-v2.0.1).
const CACHE_NAME = 'earthshade-v2.1.1';

self.addEventListener('install', (event) => {
  // La nouvelle version prend la main sans attendre la fermeture de tous les onglets
  self.skipWaiting();
  event.waitUntil(
    caches.open(CACHE_NAME)
      .then((cache) => cache.addAll(['./', './index.html', './manifest.json']))
      .catch(() => {}) // un fichier manquant ne doit pas bloquer l'installation
  );
});

self.addEventListener('activate', (event) => {
  // On supprime les copies des versions précédentes
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE_NAME).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  // Uniquement les fichiers de l'appli (même adresse que la page) :
  // Supabase, Google, polices et bibliothèques passent directement par le réseau.
  if (url.origin !== self.location.origin) return;
  // Le service worker lui-même n'est jamais servi depuis le cache
  if (url.pathname.endsWith('/sw.js')) return;

  const isPage = req.mode === 'navigate';
  // La page (même avec ?code=… au retour de Google) est rangée sous une seule adresse
  const key = isPage ? new Request(url.origin + url.pathname) : req;

  event.respondWith(
    caches.open(CACHE_NAME).then(async (cache) => {
      const cached = await cache.match(key, { ignoreSearch: isPage });
      const network = fetch(req)
        .then((res) => {
          if (res && res.ok && res.type === 'basic') cache.put(key, res.clone()).catch(() => {});
          return res;
        })
        .catch(() => cached || Response.error());
      if (cached) {
        event.waitUntil(network.then(() => {}, () => {})); // mise à jour en arrière-plan
        return cached;
      }
      return network;
    })
  );
});
