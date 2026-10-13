import {
  apiDeletePushSubscription,
  apiPushPublicKey,
  apiSavePushSubscription,
  type ApiPushSubscription,
} from './api'

/**
 * Web Push, from the browser side.
 *
 * A notification that arrives while the app is closed cannot come from the
 * page: it comes from the push service, into the service worker. So this module
 * does three things — register the worker, turn the browser's push permission
 * into a subscription, and hand that subscription to the backend so it has
 * somewhere to send to.
 *
 * Everything here is best-effort by nature. Browsers differ wildly (iOS only
 * pushes to an installed app; some don't implement push at all; a non-secure
 * origin has no service worker), so each failure is reported as a state the UI
 * can explain rather than thrown.
 */

export type PushSupport =
  /** Everything this browser needs is present. */
  | 'ready'
  /** iOS Safari: the push APIs exist only for an app on the home screen. */
  | 'needs-install'
  /** No secure context, so there is no service worker and no push at all. */
  | 'insecure'
  /** No service worker, PushManager or Notification API. */
  | 'unsupported'

export type PushEnableResult =
  | { status: 'subscribed' }
  | { status: 'denied' }
  | { status: 'dismissed' }
  | { status: 'needs-install' }
  | { status: 'insecure' }
  | { status: 'unsupported' }
  /** The server has no VAPID key pair configured, so it cannot send anything. */
  | { status: 'unavailable' }
  | { status: 'failed'; message: string }

const WORKER_URL = '/sw.js'

/** iOS reports itself as a Mac with a touch screen; both spellings are needed. */
function isIos(): boolean {
  const ua = navigator.userAgent
  if (/iP(hone|ad|od)/.test(ua)) return true
  return navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1
}

/** Running as an installed app rather than a browser tab. */
function isInstalled(): boolean {
  if (window.matchMedia('(display-mode: standalone)').matches) return true
  // Safari's own flag, which predates the media query.
  return (navigator as Navigator & { standalone?: boolean }).standalone === true
}

export function pushSupport(): PushSupport {
  if (typeof window === 'undefined') return 'unsupported'
  if (!window.isSecureContext) return 'insecure'
  if (!('serviceWorker' in navigator) || !('PushManager' in window)) return 'unsupported'
  if (typeof Notification === 'undefined') return 'unsupported'
  // Safari on iOS exposes PushManager only inside an installed web app, and
  // subscribing from a tab fails with a bare "not supported" — so the caller is
  // told what to do about it instead.
  if (isIos() && !isInstalled()) return 'needs-install'
  return 'ready'
}

/** The registration an alert can be shown through, if one is already active. */
export async function alertWorkerRegistration(): Promise<ServiceWorkerRegistration | null> {
  if (!('serviceWorker' in navigator)) return null
  try {
    const registration = await navigator.serviceWorker.getRegistration()
    return registration?.active ? registration : null
  } catch {
    return null
  }
}

/**
 * Registers the worker and waits for it to take control. Safe to call on every
 * load: registering an already-registered script is a no-op, and the browser
 * checks for an updated one in the background.
 */
export async function registerAlertWorker(): Promise<ServiceWorkerRegistration | null> {
  if (!('serviceWorker' in navigator)) return null
  try {
    await navigator.serviceWorker.register(WORKER_URL, { scope: '/' })
    // `ready` resolves only once a worker is active, which is the point: a
    // registration that is still installing cannot show a notification yet.
    const registration = await navigator.serviceWorker.ready
    return registration ?? null
  } catch {
    return null
  }
}

/**
 * The browser's VAPID key, decoded from the base64url the API serves.
 *
 * The return type names its backing buffer because `PushManager.subscribe` takes
 * a `BufferSource` over a plain `ArrayBuffer`, and a bare `Uint8Array` is typed
 * over a shared one — assigning it there is a type error, not a runtime one.
 */
export function decodeApplicationServerKey(base64url: string): Uint8Array<ArrayBuffer> {
  const padded = base64url.replace(/-/g, '+').replace(/_/g, '/')
  const binary = window.atob(padded.padEnd(Math.ceil(padded.length / 4) * 4, '='))
  const bytes = new Uint8Array(new ArrayBuffer(binary.length))
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i)
  return bytes
}

/** The shape the Push API gives a subscription, as the backend stores it. */
function toApiSubscription(subscription: PushSubscription): ApiPushSubscription | null {
  const json = subscription.toJSON() as {
    endpoint?: string
    keys?: { p256dh?: string; auth?: string }
  }
  const endpoint = json.endpoint
  const p256dh = json.keys?.p256dh
  const auth = json.keys?.auth
  if (!endpoint || !p256dh || !auth) return null
  return { endpoint, keys: { p256dh, auth } }
}

/**
 * Turns "may I?" into "the server can reach this device".
 *
 * Called from the Settings button, so a real user gesture is on the stack —
 * which is the only thing that makes the permission prompt appear on Android
 * and in Safari.
 */
export async function enablePushNotifications(): Promise<PushEnableResult> {
  const support = pushSupport()
  if (support !== 'ready') return { status: support }

  let permission = Notification.permission
  if (permission === 'default') {
    try {
      permission = await Notification.requestPermission()
    } catch {
      permission = Notification.permission
    }
  }
  if (permission === 'denied') return { status: 'denied' }
  // Still 'default': the prompt was dismissed, or the browser ignored the
  // request because it did not come from a gesture.
  if (permission !== 'granted') return { status: 'dismissed' }

  return subscribeThisDevice()
}

/**
 * Registers the worker and files the device's subscription with the backend.
 *
 * Separate from enabling because it is also the repair path: a subscription can
 * be lost by the push service rotating it, and this rebuilds it without asking
 * the user anything again.
 */
export async function subscribeThisDevice(): Promise<PushEnableResult> {
  if (Notification.permission !== 'granted') return { status: 'dismissed' }

  const registration = await registerAlertWorker()
  if (!registration) {
    // A secure origin always has one; a failure here means Service Workers are
    // disabled by policy or the script could not be fetched.
    return { status: 'unsupported' }
  }

  try {
    let publicKey: string
    try {
      publicKey = await apiPushPublicKey()
    } catch {
      return { status: 'unavailable' }
    }
    if (!publicKey) return { status: 'unavailable' }

    const existing = await registration.pushManager.getSubscription()
    const subscription =
      existing ??
      (await registration.pushManager.subscribe({
        // Every push must be shown to the user; browsers reject a subscription
        // that says otherwise.
        userVisibleOnly: true,
        applicationServerKey: decodeApplicationServerKey(publicKey),
      }))

    const payload = toApiSubscription(subscription)
    if (!payload) return { status: 'failed', message: 'The browser returned an unusable subscription.' }

    await apiSavePushSubscription(payload)
    return { status: 'subscribed' }
  } catch (err) {
    const message = err instanceof Error ? err.message : 'Push could not be enabled.'
    return { status: 'failed', message }
  }
}

/**
 * What this device's push state looks like right now, for the Settings page to
 * render. 'unavailable' means the browser could not do it even if asked.
 */
export async function pushSubscriptionState(): Promise<'subscribed' | 'none' | 'unavailable'> {
  if (pushSupport() !== 'ready') return 'unavailable'
  if (Notification.permission !== 'granted') return 'none'
  try {
    const registration = await navigator.serviceWorker.getRegistration()
    const subscription = await registration?.pushManager.getSubscription()
    return subscription ? 'subscribed' : 'none'
  } catch {
    return 'none'
  }
}

/**
 * Silences this device: the subscription is dropped from the server first, so
 * nothing is sent to an endpoint the browser is about to forget.
 */
export async function disablePushNotifications(): Promise<void> {
  if (!('serviceWorker' in navigator)) return
  try {
    const registration = await navigator.serviceWorker.getRegistration()
    const subscription = await registration?.pushManager.getSubscription()
    if (!subscription) return
    try {
      await apiDeletePushSubscription(subscription.endpoint)
    } catch {
      // The server may already have pruned it; unsubscribing locally is still
      // what the user asked for.
    }
    await subscription.unsubscribe()
  } catch {
    // Nothing to silence.
  }
}

/**
 * Re-files this device's subscription when the push service rotates it. The
 * worker cannot do that itself — it has no VAPID key — so it asks a page to.
 * Returns the cleanup for the listener.
 */
export function watchPushSubscriptionChanges(onChange: (result: PushEnableResult) => void): () => void {
  // Held rather than looked up again on cleanup: the container is what the
  // listener was added to, and re-reading `navigator.serviceWorker` later could
  // hand back nothing to remove it from.
  const container = navigator.serviceWorker
  if (!container) return () => {}
  function handle(event: MessageEvent) {
    if ((event.data as { type?: string } | null)?.type !== 'nexus:push-subscription-changed') return
    void subscribeThisDevice().then(onChange)
  }
  container.addEventListener('message', handle)
  return () => container.removeEventListener('message', handle)
}
