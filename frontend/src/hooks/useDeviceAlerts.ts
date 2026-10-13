import { useEffect, useRef } from 'react'
import type { ApiNotification } from '../lib/api'
import { connectChatSocket, type ChatSocketEvent } from '../lib/chatSocket'
import {
  alertPermission,
  loadAlertPreferences,
  openAlertLink,
  playAlertSound,
  requestAlertPermission,
  showDeviceAlert,
} from '../lib/deviceAlerts'
import { registerAlertWorker, subscribeThisDevice, watchPushSubscriptionChanges } from '../lib/push'
import type { NotificationCategory } from '../store/models'
import { useAppStore } from '../store/useAppStore'

/**
 * Fires one alert: the sound, then a system popup, with the in-app toast as the
 * fallback when the browser will not show a system one. Either way the alert
 * carries the page it is about, so clicking it opens the work. Exported so the
 * Settings page can send a test alert through exactly this path.
 *
 * Async because showing the popup is: it goes through the service worker when
 * there is one. Nothing waits on it — an alert that is a moment late is still an
 * alert — but the toast fallback has to know the popup failed first, so callers
 * use `void` rather than pretending it is done.
 */
export async function emitAlert(title: string, body: string, link?: string | null): Promise<void> {
  const preferences = loadAlertPreferences()
  if (!preferences.enabled) return
  if (preferences.sound) playAlertSound()
  if (!(await showDeviceAlert(title, body, link))) {
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
  void emitAlert('Nexus', notification.text, notification.link)
}

/**
 * Device alerts for the whole workspace, mounted once in the app shell so every
 * page benefits: notifications pushed by the server, and every chat message
 * that is not your own.
 *
 * It also owns the service worker, because the worker is what turns an alert
 * into something a phone can show and what receives a push while the app is
 * closed. Registering it here means every signed-in session has it, without a
 * page having to remember.
 */
export function useDeviceAlerts(): void {
  const currentUserId = useAppStore((s) => s.currentUser?.id ?? null)
  const askedFor = useRef<number | null>(null)

  // The worker goes first, before anybody asks for anything: it is a few
  // hundred bytes, it is what a notification is shown through, and it is what
  // the push subscription hangs off.
  useEffect(() => {
    if (currentUserId == null) return
    void registerAlertWorker()
  }, [currentUserId])

  // Ask once per signed-in user. Browsers that insist on a gesture leave the
  // answer at 'default', and the Settings button is the deliberate way to ask.
  useEffect(() => {
    if (currentUserId == null || askedFor.current === currentUserId) return
    askedFor.current = currentUserId
    if (alertPermission() === 'default') void requestAlertPermission()
  }, [currentUserId])

  // Permission already granted — from an earlier visit, or from the prompt
  // above — so this device should be subscribed. Subscribing is idempotent, and
  // it is the repair path when the push service rotated the endpoint.
  useEffect(() => {
    if (currentUserId == null || alertPermission() !== 'granted') return
    void subscribeThisDevice()
  }, [currentUserId])

  // The worker cannot resubscribe by itself (it has no VAPID key) and cannot
  // route the app anywhere (it has no router), so both come back as messages.
  useEffect(() => {
    if (currentUserId == null) return
    const stopPush = watchPushSubscriptionChanges(() => {})
    function onMessage(event: MessageEvent) {
      const data = event.data as { type?: string; url?: string } | null
      if (data?.type === 'nexus:open-alert' && data.url) openAlertLink(data.url)
    }
    navigator.serviceWorker?.addEventListener('message', onMessage)
    return () => {
      stopPush()
      navigator.serviceWorker?.removeEventListener('message', onMessage)
    }
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
      void emitAlert(title, message.body, `/slack?channel=${message.channelId}&message=${message.id}`)
    }

    const socket = connectChatSocket(handle)
    return () => socket.dispose()
  }, [currentUserId])
}
