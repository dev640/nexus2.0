import type { StateCreator } from 'zustand'
import type { AppAlert } from '../models'
import type { AppState } from '../state'

/** How many alerts stay on screen at once; older ones fall off the top. */
const MAX_VISIBLE = 4

export interface AlertsSlice {
  /**
   * In-app alerts currently on screen. They are the fallback when the browser
   * will not show a system popup — and the only part of an alert a screenshot
   * or a test can see.
   */
  activeAlerts: AppAlert[]

  pushAlert: (title: string, body: string, link?: string | null) => void
  dismissAlert: (id: number) => void
}

let nextAlertId = 1

export const createAlertsSlice: StateCreator<AppState, [], [], AlertsSlice> = (set) => ({
  activeAlerts: [],

  pushAlert: (title, body, link) => {
    const alert: AppAlert = { id: nextAlertId++, title, body, link: link ?? null }
    set((state) => ({ activeAlerts: [...state.activeAlerts, alert].slice(-MAX_VISIBLE) }))
  },

  dismissAlert: (id) => {
    set((state) => ({ activeAlerts: state.activeAlerts.filter((alert) => alert.id !== id) }))
  },
})
