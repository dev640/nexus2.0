import { Modal } from '../ui/Modal'
import { useAppStore } from '../../store/useAppStore'
import type { MailMessage } from '../../store/useAppStore'

/**
 * One message, opened from either box. Reading it as the recipient marks it
 * read — the same rule the API enforces, so nothing here tries to mark someone
 * else's copy.
 */
export function MessageModal({
  message,
  box,
  onClose,
  onReply,
}: {
  message: MailMessage | null
  box: 'received' | 'sent'
  onClose: () => void
  onReply: (message: MailMessage) => void
}) {
  const markRead = useAppStore((s) => s.markMessageRead)

  if (!message) return null

  const received = box === 'received'
  // A letter to everyone has no single person to answer, so the sender's own
  // copy offers no Reply — it would quietly pick one teammate out of the
  // audience. A recipient replying to the author is unaffected.
  const canReply = received || !message.broadcast

  function handleReply() {
    if (!message) return
    markRead(message.id)
    onReply(message)
  }

  return (
    <Modal open onClose={onClose} title={message.subject}>
      <div className="flex flex-col gap-4">
        <div className="border-b border-line pb-3 text-sm">
          <div className="flex flex-wrap justify-between gap-1">
            <span className="font-medium text-ink">
              {received
                ? `From ${message.senderName}${message.broadcast ? ' (to everyone)' : ''}`
                : message.broadcast
                  ? 'To Everyone'
                  : `To ${message.recipientName}`}
            </span>
            <span className="text-xs text-mute">{message.time}</span>
          </div>
        </div>

        <p className="whitespace-pre-wrap text-sm leading-relaxed text-ink">{message.body}</p>

        <div className="mt-2 flex flex-wrap justify-end gap-2">
          {received && (
            <button
              type="button"
              onClick={() => {
                markRead(message.id)
                onClose()
              }}
              className="mr-auto rounded-md border border-line px-3 py-2 text-sm font-medium hover:bg-paper"
            >
              Mark read
            </button>
          )}
          {canReply ? (
            <button
              type="button"
              onClick={handleReply}
              className="rounded-md border border-line px-3 py-2 text-sm font-medium hover:bg-paper"
            >
              Reply
            </button>
          ) : (
            <span className="mr-auto self-center text-xs text-mute">
              Sent to everyone in the workspace.
            </span>
          )}
          <button
            type="button"
            onClick={onClose}
            className="rounded-md bg-ink px-4 py-2 text-sm font-medium text-white hover:bg-black"
          >
            Close
          </button>
        </div>
      </div>
    </Modal>
  )
}
