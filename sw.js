var C="ebike-v1",A=["./","index.html","manifest.json","icon-192.png","https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.css","https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.js"];
self.addEventListener("install",function(e){self.skipWaiting();e.waitUntil(caches.open(C).then(function(c){return Promise.all(A.map(function(u){return c.add(u).catch(function(){});}));}));});
self.addEventListener("activate",function(e){e.waitUntil(self.clients.claim());});
self.addEventListener("fetch",function(e){
  var r=e.request;if(r.method!=="GET")return;
  var same=r.url.indexOf(self.location.origin)===0;
  e.respondWith(same?fetch(r).then(function(x){var y=x.clone();caches.open(C).then(function(c){c.put(r,y);});return x;}).catch(function(){return caches.match(r);}):caches.match(r).then(function(m){return m||fetch(r);}));
});
