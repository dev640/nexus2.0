/*
 * Nexus service worker.
 *
 * Two jobs, and both of them are things the page cannot do for itself:
 *
 *  1. Show a notification. `new Notification(...)` only works on desktop — an
 *     Android browser throws "Illegal constructor, use
 *     ServiceWorkerRegistration.showNotification()" and iOS Safari has no such
 *     constructor at all. Anything that has to appear on a phone comes from
 *     here.
 *  2. Receive a push. A message sent by the server over Web Push is delivered
 *     to this worker even when every tab is closed, which is what makes a
 *     notification arrive while the app is not open.
 *
 * It is intentionally tiny and dependency-free: this file is served from the
 * site root (not hashed, not bundled) and must keep working when the app's
 * bundle changes.
 */

/** Where a notification goes when its payload carries no usable link. */
const DEFAULT_URL = '/'

/** Payloads are re-checked here rather than trusted: push is server-controlled,
 * but the worker is still the last thing between a string and a navigation. */
function appRelative(value) {
  if (typeof value !== 'string') return null
  if (!value.startsWith('/') || value.startsWith('//')) return null
  return value
}

// Take over as soon as possible: a worker waiting behind an old one cannot
// receive the notifications it exists for.
self.addEventListener('install', () => {
  self.skipWaiting()
})

self.addEventListener('activate', (event) => {
  event.waitUntil(self.clients.claim())
})

/**
 * A server push became a notification.
 *
 * The payload is the JSON the backend sent: `{ title, body, url, tag }`.
 * Rubbish in the body is survivable — the worst case is an alert with no text,
 * which is still better than a dropped notification.
 */
self.addEventListener('push', (event) => {
  let payload = {}
  if (event.data) {
    try {
      payload = event.data.json()
    } catch {
      payload = { body: event.data.text() }
    }
  }
  if (payload === null || typeof payload !== 'object') payload = {}

  const title = typeof payload.title === 'string' && payload.title ? payload.title : 'Nexus'
  const body = typeof payload.body === 'string' ? payload.body : ''
  const url = appRelative(payload.url)
  const tag = typeof payload.tag === 'string' && payload.tag ? payload.tag : undefined

  event.waitUntil(
    (async () => {
      // A window the user is actually looking at already showed this activity —
      // the app raises its own alert off the live socket. Drawing a system
      // notification on top of it would announce the same thing twice.
      const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true })
      const watched = windows.some(
        (client) => client.visibilityState === 'visible' && client.focused,
      )
      if (watched) return

      await self.registration.showNotification(title, {
        body,
        icon: '/logo-mark.png',
        badge: '/favicon-v2.png',
        tag,
        // A tagged notification replaces its predecessor instead of stacking,
        // which is right for a re-sent alert and wrong for distinct ones.
        renotify: tag !== undefined,
        data: { url: url ?? DEFAULT_URL },
      })
    })(),
  )
})

/**
 * The notification was tapped: put the app in front of the user on the page it
 * is about, without opening a second copy of the app when one is already open.
 */
self.addEventListener('notificationclick', (event) => {
  event.notification.close()
  const path = appRelative(event.notification.data && event.notification.data.url) ?? DEFAULT_URL
  event.waitUntil(openOrFocus(path))
})

async function openOrFocus(path) {
  const origin = self.location.origin
  const target = new URL(path, origin).href
  const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true })
  const ours = windows.filter((client) => {
    const url = parseUrl(client.url)
    return url !== null && url.origin === origin
  })

  // Already where the notification points: bringing it forward is the whole job.
  const onSamePage = ours.find((client) => {
    const url = parseUrl(client.url)
    return `${url.pathname}${url.search}` === path
  })
  if (onSamePage) return onSamePage.focus()

  // A tab is open somewhere else in the app. Focus it and let the app route
  // itself: a client-side navigation keeps the workspace's state, where
  // Client.navigate would reload the page into a cold start.
  const anyWindow = ours[0]
  if (anyWindow) {
    await anyWindow.focus()
    anyWindow.postMessage({ type: 'nexus:open-alert', url: path })
    return undefined
  }

  return self.clients.openWindow(target)
}

function parseUrl(value) {
  try {
    return new URL(value)
  } catch {
    return null
  }
}

/**
 * The push service rotated the endpoint. The worker cannot resubscribe by
 * itself — it has no application server key — so it asks a page to do it, and
 * the page re-registers the new subscription with the backend.
 */
self.addEventListener('pushsubscriptionchange', (event) => {
  event.waitUntil(
    (async () => {
      const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true })
      windows.forEach((client) => client.postMessage({ type: 'nexus:push-subscription-changed' }))
    })(),
  )
})
