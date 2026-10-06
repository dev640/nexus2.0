import { useEffect, useState } from 'react'
import { connectChatSocket, type ChatSocketEvent } from '../lib/chatSocket'
import { apiChatUnread } from '../lib/api'
import { useAppStore } from '../store/useAppStore'

/**
 * Total unread chat count (messages + mentions across all channels), kept live
 * by polling /api/chat/unread and refreshing on chat socket activity.
 */
export function useUnreadCount(): number {
  const currentUser = useAppStore((s) => s.currentUser)
  const [total, setTotal] = useState(0)

  useEffect(() => {
    if (!currentUser) {
      setTotal(0)
      return
    }

    let cancelled = false
    let retryTimer: number | undefined
    let disposed = false

    async function refresh() {
      try {
        const { total: next } = await apiChatUnread()
        if (!cancelled) setTotal(next)
      } catch {
        // Transient network errors are fine: the poll retries on the next tick.
      }
    }

    function onEvent(event: ChatSocketEvent) {
      if (event.type === 'message.created' || event.type === 'message.deleted' || event.type === 'reactions.updated') {
        refresh()
      }
    }

    function onStatus(connected: boolean) {
      if (connected && !disposed) refresh()
    }

    refresh()
    const socket = connectChatSocket(onEvent, onStatus)
    const poll = window.setInterval(refresh, 15000)

    return () => {
      cancelled = true
      disposed = true
      window.clearInterval(poll)
      if (retryTimer) window.clearTimeout(retryTimer)
      socket.dispose()
    }
  }, [currentUser?.id])

  return total
}
