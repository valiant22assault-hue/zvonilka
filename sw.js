// Звонилка: показывает уведомления о звонках и сообщениях, когда страница закрыта.
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', e => e.waitUntil(self.clients.claim()));

self.addEventListener('push', e => {
  let d = {};
  try { d = e.data ? e.data.json() : {}; } catch (err) {}
  const name = d.name || 'Контакт';
  const call = d.t === 'call';
  e.waitUntil(self.registration.showNotification(call ? `${name} звонит` : `Сообщение от ${name}`, {
    body: call ? 'Нажмите, чтобы ответить' : 'Откройте звонилку, чтобы прочитать',
    tag: call ? 'zvonilka-call' : 'zvonilka-msg',
    renotify: true,
    requireInteraction: call,
    icon: 'icon-192.png',
    badge: 'icon-192.png',
    vibrate: call ? [400, 200, 400, 200, 400] : [200],
  }));
});

self.addEventListener('notificationclick', e => {
  e.notification.close();
  const url = self.registration.scope;
  e.waitUntil(self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(list => {
    const open = list.find(w => w.url.startsWith(url));
    return open ? open.focus() : self.clients.openWindow(url);
  }));
});
