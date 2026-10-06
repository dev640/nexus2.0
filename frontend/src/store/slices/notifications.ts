import type { StateCreator } from 'zustand'
import {
  apiArchiveNotification,
  apiErrorMessage,
  apiListNotifications,
  apiMarkAllNotificationsRead,
  apiMarkNotificationRead,
} from '../../lib/api'
import type { Notification, NotificationCategory } from '../models'
import { timeAgo } from '../models'
import type { AppState } from '../state'

export interface NotificationsSlice {
  notifications: Notification[]

  // Notifications (API-backed since Phase 4)
  loadNotifications: () => Promise<void>
  markNotificationRead: (id: number) => void
  archiveNotification: (id: number) => void
  markAllNotificationsRead: () => Promise<void>
}

export const createNotificationsSlice: StateCreator<AppState, [], [], NotificationsSlice> = (set, get) => ({
  notifications: [],

  loadNotifications: async () => {
    try {
      const items = await apiListNotifications()
      set({
        notifications: items.map((n) => ({
          id: n.id,
          category: (n.category as NotificationCategory) ?? 'SYSTEM',
          text: n.text,
          time: timeAgo(n.createdAt),
          read: n.read,
          archived: false,
        })),
      })
    } catch (err) {
      set({ syncError: apiErrorMessage(err, 'Failed to load notifications') })
    }
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
