/**
 * Device alerts: the system popup and the workspace sound.
 *
 * Deliberately free of React and of the app store, so an alert can be fired
 * from a websocket callback or a button with no plumbing. Preferences live in
 * localStorage because they belong to this browser, not to the account.
 */

export interface AlertPreferences {
  /** Master switch for both the popup and the sound. */
  enabled: boolean
  /** Whether the alert sound plays alongside each popup. */
  sound: boolean
}

export type AlertPermission = NotificationPermission | 'unsupported'

const PREFERENCES_KEY = 'nexus-alert-preferences'

/** The Mixkit "gaming lock" alert, shipped with the app so alerts need no network. */
export const ALERT_SOUND_URL = '/sounds/mixkit-gaming-lock-2848.wav'

export const defaultAlertPreferences: AlertPreferences = { enabled: true, sound: true }

export function loadAlertPreferences(): AlertPreferences {
  try {
    const stored = localStorage.getItem(PREFERENCES_KEY)
    if (!stored) return { ...defaultAlertPreferences }
    const parsed = JSON.parse(stored) as Partial<AlertPreferences>
    return {
      enabled: parsed.enabled ?? defaultAlertPreferences.enabled,
      sound: parsed.sound ?? defaultAlertPreferences.sound,
    }
  } catch {
    // Private mode, a storage quota, or corrupt JSON: the defaults still hold.
    return { ...defaultAlertPreferences }
  }
}

export function saveAlertPreferences(preferences: AlertPreferences): void {
  try {
    localStorage.setItem(PREFERENCES_KEY, JSON.stringify(preferences))
  } catch {
    // The alert itself already happened; failing to remember the preference is
    // not worth interrupting anyone over.
  }
}

export function alertPermission(): AlertPermission {
  if (typeof Notification === 'undefined') return 'unsupported'
  return Notification.permission
}

/** Asks the browser for permission; resolves to the resulting state either way. */
export async function requestAlertPermission(): Promise<AlertPermission> {
  if (typeof Notification === 'undefined') return 'unsupported'
  try {
    return await Notification.requestPermission()
  } catch {
    return alertPermission()
  }
}

let alertSound: HTMLAudioElement | null = null

/**
 * Plays the alert sound.
 *
 * A rejected play() is expected before the user has interacted with the page
 * and is deliberately swallowed: the popup still appears, and every later alert
 * is audible once the browser lifts the restriction.
 */
export function playAlertSound(): void {
  try {
    if (!alertSound) {
      alertSound = new Audio(ALERT_SOUND_URL)
      alertSound.preload = 'auto'
      alertSound.volume = 0.5
    }
    alertSound.currentTime = 0
    void alertSound.play().catch(() => {})
  } catch {
    // No audio support in this environment; the popup carries the message.
  }
}

/**
 * How an alert opens the page it points at. The app shell registers the router
 * navigator so a click stays inside the SPA; the fallback is a normal page load,
 * which still lands on the right place.
 */
let alertNavigator: ((path: string) => void) | null = null

export function setAlertNavigator(navigate: ((path: string) => void) | null): void {
  alertNavigator = navigate
}

/**
 * Opens the page an alert refers to. Only app-relative paths are honoured: a
 * stored absolute URL would otherwise turn a notification click into a jump to
 * somebody else's site.
 */
export function openAlertLink(link: string): void {
  if (!link.startsWith('/') || link.startsWith('//')) return
  if (alertNavigator) alertNavigator(link)
  else window.location.assign(link)
}

/**
 * Shows a system notification. Returns false when no popup appeared — no
 * permission, an unsupported browser, or a refused constructor — which callers
 * use to fall back to an in-app toast.
 *
 * A `link` makes the popup clickable, so an alert about a task takes you to
 * that task rather than to whatever page you happened to be on.
 */
export function showDeviceAlert(title: string, body: string, link?: string | null): boolean {
  if (alertPermission() !== 'granted') return false
  try {
    const popup = new Notification(title, { body, icon: '/logo-mark.png' })
    popup.onclick = () => {
      window.focus()
      popup.close()
      if (link) openAlertLink(link)
    }
    return true
  } catch {
    return false
  }
}
