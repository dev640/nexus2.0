import { getCurrentToken, whiteboardSocketUrl } from './api'

export interface WhiteboardEventPayload {
  type: 'created' | 'updated' | 'deleted'
  note: {
    id: number
    board: string
    text: string
    color: string
    x: number
    y: number
    author: string | null
  }
}

/**
 * Live whiteboard connection. Reconnects automatically with backoff while the
 * page is open; closes cleanly on dispose.
 */
export function connectWhiteboardSocket(
  onEvent: (event: WhiteboardEventPayload) => void,
  onStatus?: (connected: boolean) => void,
): () => void {
  let socket: WebSocket | null = null
  let closed = false
  let attempt = 0
  let retryTimer: number | undefined

  async function open() {
    const token = await getCurrentToken()
    if (!token || closed) return
    socket = new WebSocket(`${whiteboardSocketUrl()}?token=${encodeURIComponent(token)}`)

    socket.onopen = () => {
      attempt = 0
      onStatus?.(true)
    }

    socket.onmessage = (message) => {
      try {
        const parsed = JSON.parse(message.data as string) as WhiteboardEventPayload
        if (parsed?.type && parsed.note) onEvent(parsed)
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

  return () => {
    closed = true
    if (retryTimer) window.clearTimeout(retryTimer)
    socket?.close()
  }
}
