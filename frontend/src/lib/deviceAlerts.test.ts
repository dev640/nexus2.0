import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  ALERT_SOUND_URL,
  alertPermission,
  defaultAlertPreferences,
  loadAlertPreferences,
  openAlertLink,
  playAlertSound,
  requestAlertPermission,
  saveAlertPreferences,
  setAlertNavigator,
  showDeviceAlert,
} from './deviceAlerts'

type FakePopup = { title: string; options?: NotificationOptions }

const popups: FakePopup[] = []
const instances: FakeNotification[] = []
const plays: string[] = []

/** jsdom has neither a Notification API nor audio playback, so both are stubbed. */
class FakeNotification {
  static permission: NotificationPermission = 'granted'
  static requestPermission = vi.fn(async () => FakeNotification.permission)
  onclick: (() => void) | null = null
  closed = false

  constructor(title: string, options?: NotificationOptions) {
    this.options = options
    popups.push({ title, options })
    instances.push(this)
    this.title = title
  }

  readonly title: string
  options?: NotificationOptions

  close() {
    this.closed = true
  }
}

class FakeAudio {
  preload = ''
  volume = 1
  currentTime = 0
  readonly src: string

  constructor(src: string) {
    this.src = src
  }

  play() {
    plays.push(this.src)
    return Promise.resolve()
  }
}

beforeEach(() => {
  localStorage.clear()
  popups.length = 0
  instances.length = 0
  plays.length = 0
  FakeNotification.permission = 'granted'
  FakeNotification.requestPermission.mockClear()
  vi.stubGlobal('Notification', FakeNotification)
  vi.stubGlobal('Audio', FakeAudio)
})

afterEach(() => {
  vi.unstubAllGlobals()
  setAlertNavigator(null)
})

describe('opening what an alert is about', () => {
  it('sends a click on the popup to the page the alert carries', async () => {
    const navigate = vi.fn()
    setAlertNavigator(navigate)

    await showDeviceAlert('Nexus', 'You were assigned to "Ship it"', '/board?task=42')
    instances.at(-1)?.onclick?.()

    expect(navigate).toHaveBeenCalledWith('/board?task=42')
    expect(instances.at(-1)?.closed).toBe(true)
  })

  it('leaves a click inert when the alert has no link', async () => {
    const navigate = vi.fn()
    setAlertNavigator(navigate)

    await showDeviceAlert('Nexus', 'Something happened')
    instances.at(-1)?.onclick?.()

    expect(navigate).not.toHaveBeenCalled()
  })

  it('refuses a link that would take the browser off-site', () => {
    const navigate = vi.fn()
    setAlertNavigator(navigate)

    openAlertLink('https://evil.example/steal')
    openAlertLink('//evil.example/steal')

    expect(navigate).not.toHaveBeenCalled()
  })
})

describe('alert preferences', () => {
  it('turns alerts and the sound on by default', () => {
    expect(loadAlertPreferences()).toEqual(defaultAlertPreferences)
    expect(defaultAlertPreferences).toEqual({ enabled: true, sound: true })
  })

  it('remembers what the user chose', () => {
    saveAlertPreferences({ enabled: false, sound: false })
    expect(loadAlertPreferences()).toEqual({ enabled: false, sound: false })
  })

  it('falls back to the defaults when the stored value is unreadable', () => {
    localStorage.setItem('nexus-alert-preferences', '{ this is not json')
    expect(loadAlertPreferences()).toEqual(defaultAlertPreferences)
  })
})

describe('device popups', () => {
  it('shows a system notification when the browser allows it', async () => {
    await expect(showDeviceAlert('Nexus', 'You were assigned to "Ship it"')).resolves.toBe(true)

    expect(popups).toHaveLength(1)
    expect(popups[0].title).toBe('Nexus')
    expect(popups[0].options?.body).toBe('You were assigned to "Ship it"')
  })

  it('reports failure when permission was not granted, so callers can fall back', async () => {
    FakeNotification.permission = 'denied'

    await expect(showDeviceAlert('Nexus', 'nope')).resolves.toBe(false)
    expect(popups).toHaveLength(0)
  })

  it('reports failure on a browser that has no Notification API at all', async () => {
    vi.stubGlobal('Notification', undefined)

    expect(alertPermission()).toBe('unsupported')
    await expect(showDeviceAlert('Nexus', 'nope')).resolves.toBe(false)
  })
})

/**
 * The phone case, which is the whole reason the worker is involved.
 *
 * An Android browser throws "Illegal constructor, use
 * ServiceWorkerRegistration.showNotification()" on `new Notification(...)`, and
 * iOS Safari has no such constructor. Going through the registration is what
 * makes an alert appear on a phone at all, so it is asserted on its own.
 */
describe('device popups on a phone', () => {
  const shown: { title: string; options?: NotificationOptions }[] = []

  function stubWorker() {
    const registration = {
      active: {},
      showNotification: vi.fn(async (title: string, options?: NotificationOptions) => {
        shown.push({ title, options })
      }),
    }
    Object.defineProperty(navigator, 'serviceWorker', {
      value: { getRegistration: vi.fn(async () => registration) },
      configurable: true,
    })
    return registration
  }

  beforeEach(() => {
    shown.length = 0
  })

  afterEach(() => {
    delete (navigator as { serviceWorker?: unknown }).serviceWorker
  })

  it('shows it through the service worker instead of the constructor', async () => {
    const registration = stubWorker()

    await expect(showDeviceAlert('Nexus', 'You were assigned to "Ship it"', '/board?task=42'))
      .resolves.toBe(true)

    expect(registration.showNotification).toHaveBeenCalledTimes(1)
    expect(shown[0].title).toBe('Nexus')
    expect(shown[0].options?.body).toBe('You were assigned to "Ship it"')
    // The link rides in the notification's data, which is what the worker's click
    // handler reads to focus the app and route it.
    expect(shown[0].options?.data).toEqual({ url: '/board?task=42' })
    // And the desktop-only constructor was not used at all.
    expect(popups).toHaveLength(0)
  })

  it('falls back to the constructor when the worker refuses', async () => {
    const registration = stubWorker()
    registration.showNotification.mockRejectedValueOnce(new Error('nope'))

    await expect(showDeviceAlert('Nexus', 'still delivered')).resolves.toBe(true)

    expect(popups).toHaveLength(1)
  })

  it('asks the browser for permission and answers with the outcome', async () => {
    FakeNotification.permission = 'default'
    FakeNotification.requestPermission.mockResolvedValueOnce('granted')

    await expect(requestAlertPermission()).resolves.toBe('granted')
    expect(FakeNotification.requestPermission).toHaveBeenCalledTimes(1)
  })
})

describe('the alert sound', () => {
  it('plays the workspace sound file the app ships with', () => {
    playAlertSound()
    expect(plays).toEqual([ALERT_SOUND_URL])
  })

  it('survives an environment with no audio support', async () => {
    // A fresh module, because the sound element is created once and kept.
    vi.stubGlobal('Audio', undefined)
    vi.resetModules()
    const fresh = await import('./deviceAlerts')

    expect(() => fresh.playAlertSound()).not.toThrow()
  })
})
