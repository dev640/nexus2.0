import { getCurrentToken, whiteboardSocketUrl } from './api'
import type { ApiChatMessage } from './api'

/** Frames pushed by the backend over /ws/chat. */
export type ChatSocketEvent =
  | { type: 'message.created'; channelId: number; message: ApiChatMessage }
  | { type: 'message.updated'; channelId: number; message: ApiChatMessage }
  | { type: 'message.deleted'; channelId: number; messageId?: number }
  | { type: 'reactions.updated'; channelId: number; reactions: { messageId: number; emoji: string; userIds: number[] }[] }
  | { type: 'channel.created'; channelId: number; channelName: string | null }
  | { type: 'typing'; channelId: number; userId: number; userName: string }
  | { type: 'presence'; online: number[] }

/**
 * Live chat connection. Reconnects automatically with backoff; returns a
 * dispose function. `send` lets the page push typing indicators.
 */
export function connectChatSocket(
  onEvent: (event: ChatSocketEvent) => void,
  onStatus?: (connected: boolean) => void,
): { dispose: () => void; send: (frame: Record<string, unknown>) => void } {
  let socket: WebSocket | null = null
  let closed = false
  let attempt = 0
  let retryTimer: number | undefined

  async function open() {
    const token = await getCurrentToken()
    if (!token || closed) return
    socket = new WebSocket(`${chatSocketUrl()}?token=${encodeURIComponent(token)}`)

    socket.onopen = () => {
      attempt = 0
      onStatus?.(true)
    }

    socket.onmessage = (message) => {
      try {
        const parsed = JSON.parse(message.data as string) as ChatSocketEvent
        if (parsed && typeof parsed.type === 'string') onEvent(parsed)
      } catch {
        // ignore malformed frames
      }
    }

    socket.onclose = () => {
      onStatus?.(false)
      if (closed) return
      attempt += 1
      const delay = Math.min(1000 * 2 ** (attempt - 1), 10000)
      retryTimer = window.setTimeout(open, delay)
    }

    socket.onerror = () => {
      socket?.close()
    }
  }

  open()

  return {
    dispose() {
      closed = true
      if (retryTimer) window.clearTimeout(retryTimer)
      socket?.close()
    },
    send(frame) {
      if (socket && socket.readyState === WebSocket.OPEN) {
        socket.send(JSON.stringify(frame))
      }
    },
  }
}

/** WebSocket endpoint for chat, same base as the whiteboard socket. */
export function chatSocketUrl(): string {
  const base = whiteboardSocketUrl()
  return base.replace(/\/ws\/whiteboard$/, '/ws/chat')
}
