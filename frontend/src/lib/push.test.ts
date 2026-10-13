import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  alertWorkerRegistration,
  decodeApplicationServerKey,
  disablePushNotifications,
  enablePushNotifications,
  pushSubscriptionState,
  pushSupport,
  registerAlertWorker,
  subscribeThisDevice,
  watchPushSubscriptionChanges,
} from './push'
import {
  apiDeletePushSubscription,
  apiPushPublicKey,
  apiSavePushSubscription,
} from './api'

vi.mock('./api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./api')>()
  return {
    ...actual,
    apiPushPublicKey: vi.fn(),
    apiSavePushSubscription: vi.fn(),
    apiDeletePushSubscription: vi.fn(),
  }
})

/** The VAPID public key shape a real deployment serves: 65 bytes, base64url. */
const PUBLIC_KEY = 'BHcy7Ysvq4B8mXp_pRvMh0T16GdVx33MPVfY7Ksmcj-FEiKGjBiJZgtgnoxbhOfKiJLzvo6B4VD1VJjmy5X9EHU'
const ENDPOINT = 'https://fcm.googleapis.com/fcm/send/abc123'

function fakeSubscription(overrides: { endpoint?: string; p256dh?: string; auth?: string } = {}) {
  return {
    endpoint: overrides.endpoint ?? ENDPOINT,
    unsubscribe: vi.fn(async () => true),
    toJSON: () => ({
      endpoint: overrides.endpoint ?? ENDPOINT,
      keys: {
        p256dh: overrides.p256dh ?? 'p256dh-value',
        auth: overrides.auth ?? 'auth-value',
      },
    }),
  }
}

/** A browser-shaped service worker container and registration. */
function stubServiceWorker({ existing = null as ReturnType<typeof fakeSubscription> | null } = {}) {
  const registration = {
    active: {},
    showNotification: vi.fn(async () => {}),
    pushManager: {
      getSubscription: vi.fn(async () => existing),
      // Typed parameters, so the assertions below can read the options the module
      // actually passed instead of an empty tuple.
      subscribe: vi.fn(async (_options: PushSubscriptionOptionsInit) => fakeSubscription()),
    },
  }
  const container = {
    register: vi.fn(async () => registration),
    ready: Promise.resolve(registration),
    getRegistration: vi.fn(async () => registration),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  }
  Object.defineProperty(navigator, 'serviceWorker', { value: container, configurable: true })
  return { registration, container }
}

class FakeNotification {
  static permission: NotificationPermission = 'default'
  static requestPermission = vi.fn(async () => FakeNotification.permission)
}

function setUserAgent(userAgent: string) {
  Object.defineProperty(navigator, 'userAgent', { value: userAgent, configurable: true })
}

function setStandalone(installed: boolean) {
  Object.defineProperty(navigator, 'standalone', { value: installed, configurable: true })
}

function setPlatform(platform: string) {
  Object.defineProperty(navigator, 'platform', { value: platform, configurable: true })
}

/** What a browser does when the person taps Allow: the static state moves too. */
function grantWhenAsked() {
  FakeNotification.requestPermission = vi.fn(async () => {
    FakeNotification.permission = 'granted'
    return 'granted'
  })
}

beforeEach(() => {
  vi.mocked(apiPushPublicKey).mockReset().mockResolvedValue(PUBLIC_KEY)
  vi.mocked(apiSavePushSubscription).mockReset().mockResolvedValue(undefined)
  vi.mocked(apiDeletePushSubscription).mockReset().mockResolvedValue(undefined)
  FakeNotification.permission = 'default'
  FakeNotification.requestPermission = vi.fn(async () => FakeNotification.permission)
  vi.stubGlobal('Notification', FakeNotification)
  vi.stubGlobal('PushManager', class PushManager {})
  Object.defineProperty(window, 'isSecureContext', { value: true, configurable: true })
  Object.defineProperty(window, 'matchMedia', {
    value: () => ({ matches: false }),
    configurable: true,
  })
  setUserAgent('Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/140.0')
  setStandalone(false)
  setPlatform('Win32')
  // Deleted rather than set to undefined: `'serviceWorker' in navigator` is the
  // check the module makes, and a property that exists with no value still
  // answers yes to it.
  delete (navigator as { serviceWorker?: unknown }).serviceWorker
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('what this browser can do', () => {
  it('is ready on a desktop browser with everything present', () => {
    stubServiceWorker()
    expect(pushSupport()).toBe('ready')
  })

  it('is unsupported without a service worker', () => {
    expect(pushSupport()).toBe('unsupported')
  })

  it('is unsupported outside a secure context, where no worker can register', () => {
    stubServiceWorker()
    Object.defineProperty(window, 'isSecureContext', { value: false, configurable: true })

    expect(pushSupport()).toBe('insecure')
  })

  /**
   * iOS Safari exposes PushManager only to an app on the home screen. Subscribing
   * from a tab fails with a bare "not supported", so the state is named and the
   * Settings page can tell the person what to do about it.
   */
  it('needs a home-screen install on an iPhone', () => {
    stubServiceWorker()
    setUserAgent('Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) Safari/605.1.15')

    expect(pushSupport()).toBe('needs-install')
  })

  it('is ready on an iPhone once the app is installed', () => {
    stubServiceWorker()
    setUserAgent('Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) Safari/605.1.15')
    setStandalone(true)

    expect(pushSupport()).toBe('ready')
  })

  it('recognises an iPad, which reports itself as a Mac with a touch screen', () => {
    stubServiceWorker()
    setUserAgent('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Safari/605.1.15')
    setPlatform('MacIntel')
    Object.defineProperty(navigator, 'maxTouchPoints', { value: 5, configurable: true })

    expect(pushSupport()).toBe('needs-install')
  })
})

describe('the VAPID key handed to the browser', () => {
  it('decodes base64url into the raw bytes pushManager expects', () => {
    const bytes = decodeApplicationServerKey(PUBLIC_KEY)

    // A Uint8Array, not a string: subscribe() rejects a string outright.
    expect(bytes).toBeInstanceOf(Uint8Array)
    expect(bytes).toHaveLength(65)
    expect(bytes[0]).toBe(0x04)
  })
})

describe('registering the worker', () => {
  it('registers it and waits for one to be active', async () => {
    const { container, registration } = stubServiceWorker()

    await expect(registerAlertWorker()).resolves.toBe(registration)
    expect(container.register).toHaveBeenCalledWith('/sw.js', { scope: '/' })
  })

  it('reports no registration when the browser refuses the script', async () => {
    const container = {
      register: vi.fn(async () => {
        throw new Error('blocked')
      }),
      ready: Promise.resolve(null),
      getRegistration: vi.fn(async () => null),
    }
    Object.defineProperty(navigator, 'serviceWorker', { value: container, configurable: true })

    await expect(registerAlertWorker()).resolves.toBeNull()
  })

  it('only hands back a registration that can actually show a notification', async () => {
    const container = {
      getRegistration: vi.fn(async () => ({ active: null, pushManager: {} })),
    }
    Object.defineProperty(navigator, 'serviceWorker', { value: container, configurable: true })

    await expect(alertWorkerRegistration()).resolves.toBeNull()
  })
})

describe('turning push on', () => {
  it('asks the browser, subscribes, and files the device with the server', async () => {
    const { registration } = stubServiceWorker()
    FakeNotification.permission = 'default'
    grantWhenAsked()

    await expect(enablePushNotifications()).resolves.toEqual({ status: 'subscribed' })

    expect(registration.pushManager.subscribe).toHaveBeenCalledTimes(1)
    const options = registration.pushManager.subscribe.mock.calls[0]![0]
    expect(options.userVisibleOnly).toBe(true)
    // The key must go in as bytes, decoded from what the API served.
    expect(options.applicationServerKey).toBeInstanceOf(Uint8Array)
    expect(apiSavePushSubscription).toHaveBeenCalledWith({
      endpoint: ENDPOINT,
      keys: { p256dh: 'p256dh-value', auth: 'auth-value' },
    })
  })

  it('reuses an existing subscription instead of creating a second one', async () => {
    const existing = fakeSubscription()
    const { registration } = stubServiceWorker({ existing })
    FakeNotification.permission = 'granted'

    await expect(enablePushNotifications()).resolves.toEqual({ status: 'subscribed' })

    expect(registration.pushManager.subscribe).not.toHaveBeenCalled()
    expect(apiSavePushSubscription).toHaveBeenCalledTimes(1)
  })

  it('reports a refusal rather than pretending', async () => {
    stubServiceWorker()
    FakeNotification.permission = 'default'
    FakeNotification.requestPermission.mockResolvedValue('denied')

    await expect(enablePushNotifications()).resolves.toEqual({ status: 'denied' })
    expect(apiSavePushSubscription).not.toHaveBeenCalled()
  })

  it('reports a dismissed prompt, which leaves permission at default', async () => {
    stubServiceWorker()

    await expect(enablePushNotifications()).resolves.toEqual({ status: 'dismissed' })
  })

  it('says so when the server has no key pair to subscribe against', async () => {
    stubServiceWorker()
    FakeNotification.permission = 'granted'
    vi.mocked(apiPushPublicKey).mockResolvedValue('')

    await expect(enablePushNotifications()).resolves.toEqual({ status: 'unavailable' })
  })

  it('treats a missing key endpoint as "push is off", not as a crash', async () => {
    stubServiceWorker()
    FakeNotification.permission = 'granted'
    vi.mocked(apiPushPublicKey).mockRejectedValue(new Error('404'))

    await expect(enablePushNotifications()).resolves.toEqual({ status: 'unavailable' })
  })

  it('surfaces a browser that returns an unusable subscription', async () => {
    const broken = { ...fakeSubscription(), toJSON: () => ({ endpoint: ENDPOINT }) }
    const registration = {
      active: {},
      pushManager: { getSubscription: vi.fn(async () => broken), subscribe: vi.fn() },
    }
    Object.defineProperty(navigator, 'serviceWorker', {
      value: {
        register: vi.fn(async () => registration),
        ready: Promise.resolve(registration),
        getRegistration: vi.fn(async () => registration),
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
      },
      configurable: true,
    })
    FakeNotification.permission = 'granted'

    const result = await enablePushNotifications()
    expect(result.status).toBe('failed')
  })
})

describe('turning push off', () => {
  it('drops the server row first, then the local subscription', async () => {
    const existing = fakeSubscription()
    stubServiceWorker({ existing })
    FakeNotification.permission = 'granted'

    await disablePushNotifications()

    // Order matters: telling the browser first would leave the server pushing to
    // an endpoint nothing can receive.
    expect(apiDeletePushSubscription).toHaveBeenCalledWith(ENDPOINT)
    expect(existing.unsubscribe).toHaveBeenCalled()
    expect(vi.mocked(apiDeletePushSubscription).mock.invocationCallOrder[0])
      .toBeLessThan(existing.unsubscribe.mock.invocationCallOrder[0])
  })

  it('still silences the browser when the server call fails', async () => {
    const existing = fakeSubscription()
    stubServiceWorker({ existing })
    vi.mocked(apiDeletePushSubscription).mockRejectedValue(new Error('offline'))

    await disablePushNotifications()

    expect(existing.unsubscribe).toHaveBeenCalled()
  })

  it('does nothing when this browser never subscribed', async () => {
    stubServiceWorker()

    await expect(disablePushNotifications()).resolves.toBeUndefined()
    expect(apiDeletePushSubscription).not.toHaveBeenCalled()
  })
})

describe('the state the Settings page shows', () => {
  it('is subscribed when a subscription exists and permission is granted', async () => {
    stubServiceWorker({ existing: fakeSubscription() })
    FakeNotification.permission = 'granted'

    await expect(pushSubscriptionState()).resolves.toBe('subscribed')
  })

  it('is none while permission has not been granted', async () => {
    stubServiceWorker()
    FakeNotification.permission = 'default'

    await expect(pushSubscriptionState()).resolves.toBe('none')
  })

  it('is unavailable on a browser that could not do it anyway', async () => {
    await expect(pushSubscriptionState()).resolves.toBe('unavailable')
  })
})

describe('a subscription the push service rotates', () => {
  it('re-files itself when the worker asks for help', async () => {
    const existing = fakeSubscription()
    const { container } = stubServiceWorker({ existing })
    FakeNotification.permission = 'granted'
    const results: string[] = []

    const stop = watchPushSubscriptionChanges((result) => results.push(result.status))

    // The worker cannot resubscribe alone — it holds no VAPID key — so it messages
    // a page, and this is that page reacting.
    const listener = container.addEventListener.mock.calls[0][1] as (event: MessageEvent) => void
    listener({ data: { type: 'nexus:push-subscription-changed' } } as MessageEvent)
    await vi.waitFor(() => expect(results).toContain('subscribed'))

    expect(apiSavePushSubscription).toHaveBeenCalledTimes(1)
    stop()
    expect(container.removeEventListener).toHaveBeenCalled()
  })

  it('ignores messages that are not about push', async () => {
    const { container } = stubServiceWorker()
    const onChange = vi.fn()
    watchPushSubscriptionChanges(onChange)

    const listener = container.addEventListener.mock.calls[0][1] as (event: MessageEvent) => void
    listener({ data: { type: 'nexus:open-alert', url: '/board' } } as MessageEvent)

    expect(onChange).not.toHaveBeenCalled()
    expect(apiSavePushSubscription).not.toHaveBeenCalled()
  })
})

describe('subscribing without asking', () => {
  it('does nothing while permission has not been granted', async () => {
    stubServiceWorker()

    await expect(subscribeThisDevice()).resolves.toEqual({ status: 'dismissed' })
    expect(apiSavePushSubscription).not.toHaveBeenCalled()
  })
})
