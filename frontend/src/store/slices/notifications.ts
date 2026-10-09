import type { StateCreator } from 'zustand'
import {
  apiArchiveNotification,
  apiErrorMessage,
  apiListNotifications,
  apiMarkAllNotificationsRead,
  apiMarkNotificationRead,
  type ApiNotification,
} from '../../lib/api'
import type { Notification, NotificationCategory } from '../models'
import { timeAgo } from '../models'
import type { AppState } from '../state'

/** The API row as the Inbox renders it. */
function toModel(n: ApiNotification): Notification {
  return {
    id: n.id,
    category: (n.category as NotificationCategory) ?? 'SYSTEM',
    text: n.text,
    link: n.link ?? null,
    time: timeAgo(n.createdAt),
    read: n.read,
    archived: false,
  }
}

export interface NotificationsSlice {
  notifications: Notification[]

  // Notifications (API-backed since Phase 4)
  loadNotifications: () => Promise<void>
  /** Puts one pushed notification at the top, without refetching the list. */
  receiveNotification: (n: ApiNotification) => void
  markNotificationRead: (id: number) => void
  archiveNotification: (id: number) => void
  markAllNotificationsRead: () => Promise<void>
}

export const createNotificationsSlice: StateCreator<AppState, [], [], NotificationsSlice> = (set, get) => ({
  notifications: [],

  loadNotifications: async () => {
    try {
      const items = await apiListNotifications()
      set({ notifications: items.map(toModel) })
    } catch (err) {
      set({ syncError: apiErrorMessage(err, 'Failed to load notifications') })
    }
  },

  receiveNotification: (n) => {
    // A push can race the list load that is already in flight; the id check
    // keeps the same notification from appearing twice.
    set((state) =>
      state.notifications.some((existing) => existing.id === n.id)
        ? state
        : { notifications: [toModel(n), ...state.notifications] },
    )
  },

  markNotificationRead: (id) => {
    // Optimistic; roll back on failure
    const previous = get().notifications
    set({ notifications: previous.map((n) => (n.id === id ? { ...n, read: true } : n)) })
    apiMarkNotificationRead(id).catch(() => set({ notifications: previous }))
  },

  archiveNotification: (id) => {
    const previous = get().notifications
    set({ notifications: previous.filter((n) => n.id !== id) })
    apiArchiveNotification(id).catch(() => set({ notifications: previous }))
  },

  markAllNotificationsRead: async () => {
    const previous = get().notifications
    set({ notifications: previous.map((n) => ({ ...n, read: true })) })
    try {
      await apiMarkAllNotificationsRead()
    } catch {
      set({ notifications: previous })
    }
  },
})
