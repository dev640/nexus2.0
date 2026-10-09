import { useEffect, useRef } from 'react'
import type { ApiNotification } from '../lib/api'
import { connectChatSocket, type ChatSocketEvent } from '../lib/chatSocket'
import {
  alertPermission,
  loadAlertPreferences,
  playAlertSound,
  requestAlertPermission,
  showDeviceAlert,
} from '../lib/deviceAlerts'
import type { NotificationCategory } from '../store/models'
import { useAppStore } from '../store/useAppStore'

/**
 * Fires one alert: the sound, then a system popup, with the in-app toast as the
 * fallback when the browser will not show a system one. Either way the alert
 * carries the page it is about, so clicking it opens the work. Exported so the
 * Settings page can send a test alert through exactly this path.
 */
export function emitAlert(title: string, body: string, link?: string | null): void {
  const preferences = loadAlertPreferences()
  if (!preferences.enabled) return
  if (preferences.sound) playAlertSound()
  if (!showDeviceAlert(title, body, link)) {
    useAppStore.getState().pushAlert(title, body, link)
  }
}

/**
 * Puts a pushed notification into the feed and alerts about it.
 *
 * Mentions are skipped: the chat message itself already popped a popup when it
 * arrived, and two popups for one message is noise. Muted categories stay quiet
 * the same way they stay out of the Inbox.
 */
function onNotification(notification: ApiNotification): void {
  const state = useAppStore.getState()
  state.receiveNotification(notification)

  const category = notification.category as NotificationCategory
  if (category === 'MENTIONS') return
  if (state.settings.mutedCategories.includes(category)) return
  emitAlert('Nexus', notification.text, notification.link)
}

/**
 * Device alerts for the whole workspace, mounted once in the app shell so every
 * page benefits: notifications pushed by the server, and every chat message
 * that is not your own.
 */
export function useDeviceAlerts(): void {
  const currentUserId = useAppStore((s) => s.currentUser?.id ?? null)
  const askedFor = useRef<number | null>(null)

  // Ask once per signed-in user. Browsers that insist on a gesture leave the
  // answer at 'default', and the Settings button is the deliberate way to ask.
  useEffect(() => {
    if (currentUserId == null || askedFor.current === currentUserId) return
    askedFor.current = currentUserId
    if (alertPermission() === 'default') void requestAlertPermission()
  }, [currentUserId])

  useEffect(() => {
    if (currentUserId == null) return

    function handle(event: ChatSocketEvent) {
      if (event.type === 'notification.created') {
        onNotification(event.notification)
        return
      }
      if (event.type !== 'message.created') return
      const message = event.message
      if (message.authorId === currentUserId) return
      const author = message.authorName ?? 'A teammate'
      // DMs carry no channel name; everything else is a named channel.
      const title = event.channelName ? `${author} in #${event.channelName}` : `Message from ${author}`
      // The channel opens, and the message is highlighted once it is on screen.
      emitAlert(title, message.body, `/slack?channel=${message.channelId}&message=${message.id}`)
    }

    const socket = connectChatSocket(handle)
    return () => socket.dispose()
  }, [currentUserId])
}
