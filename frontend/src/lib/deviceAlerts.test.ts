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
  it('sends a click on the popup to the page the alert carries', () => {
    const navigate = vi.fn()
    setAlertNavigator(navigate)

    showDeviceAlert('Nexus', 'You were assigned to "Ship it"', '/board?task=42')
    instances.at(-1)?.onclick?.()

    expect(navigate).toHaveBeenCalledWith('/board?task=42')
    expect(instances.at(-1)?.closed).toBe(true)
  })

  it('leaves a click inert when the alert has no link', () => {
    const navigate = vi.fn()
    setAlertNavigator(navigate)

    showDeviceAlert('Nexus', 'Something happened')
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
  it('shows a system notification when the browser allows it', () => {
    expect(showDeviceAlert('Nexus', 'You were assigned to "Ship it"')).toBe(true)

    expect(popups).toHaveLength(1)
    expect(popups[0].title).toBe('Nexus')
    expect(popups[0].options?.body).toBe('You were assigned to "Ship it"')
  })

  it('reports failure when permission was not granted, so callers can fall back', () => {
    FakeNotification.permission = 'denied'

    expect(showDeviceAlert('Nexus', 'nope')).toBe(false)
    expect(popups).toHaveLength(0)
  })

  it('reports failure on a browser that has no Notification API at all', () => {
    vi.stubGlobal('Notification', undefined)

    expect(alertPermission()).toBe('unsupported')
    expect(showDeviceAlert('Nexus', 'nope')).toBe(false)
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
