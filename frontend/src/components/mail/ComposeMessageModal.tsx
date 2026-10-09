import { useState } from 'react'
import { Modal } from '../ui/Modal'
import { useAppStore } from '../../store/useAppStore'

const fieldLabel = 'mb-1 block text-xs font-medium uppercase tracking-wide text-mute'
const field =
  'w-full rounded-md border border-line px-3 py-2 text-sm outline-none focus:border-ink'

/**
 * The "everyone" choice in the recipient picker. A sentinel rather than a
 * member id, because the audience is not a person: it is everyone in the
 * workspace except the sender, resolved by the server.
 */
const EVERYONE = 'everyone'

/**
 * Writing to a colleague. Also used for replies, where the recipient and
 * subject arrive pre-filled so replying is one click and one message.
 */
export function ComposeMessageModal({
  open,
  onClose,
  initialRecipientId = '',
  initialSubject = '',
  onSent,
}: {
  open: boolean
  onClose: () => void
  initialRecipientId?: string
  initialSubject?: string
  onSent?: () => void
}) {
  const members = useAppStore((s) => s.members)
  const currentUser = useAppStore((s) => s.currentUser)
  const sendMessage = useAppStore((s) => s.sendMessage)

  const [recipientId, setRecipientId] = useState(initialRecipientId)
  const [subject, setSubject] = useState(initialSubject)
  const [body, setBody] = useState('')
  const [error, setError] = useState('')
  const [sending, setSending] = useState(false)

  // Everyone but yourself: a message to your own inbox is almost always a
  // mis-click, and there is no reply to wait for.
  const others = members.filter((m) => m.id !== (currentUser ? `u-${currentUser.id}` : ''))

  function reset() {
    setRecipientId(initialRecipientId)
    setSubject(initialSubject)
    setBody('')
    setError('')
    setSending(false)
  }

  function handleClose() {
    reset()
    onClose()
  }

  const toEveryone = recipientId === EVERYONE

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!recipientId) {
      setError('Pick someone to send this to, or choose Everyone')
      return
    }
    if (toEveryone && others.length === 0) {
      setError('There is nobody else in the workspace to write to')
      return
    }
    if (!subject.trim()) {
      setError('Subject is required')
      return
    }
    if (!body.trim()) {
      setError('Write something first')
      return
    }
    setSending(true)
    setError('')
    const result = await sendMessage({ recipientId, subject, body, toEveryone })
    setSending(false)
    if (!result.ok) {
      setError(result.error ?? 'Could not send the message')
      return
    }
    reset()
    onSent?.()
    onClose()
  }

  return (
    <Modal open={open} onClose={handleClose} title="New message">
      <form onSubmit={handleSubmit} className="flex flex-col gap-4">
        <div>
          <label className={fieldLabel}>To</label>
          <select
            autoFocus
            value={recipientId}
            onChange={(e) => setRecipientId(e.target.value)}
            className={field}
          >
            <option value="">Pick a teammate…</option>
            {others.length > 0 && (
              <option value={EVERYONE}>Everyone ({others.length} teammates)</option>
            )}
            {others.map((m) => (
              <option key={m.id} value={m.id}>
                {m.name}
              </option>
            ))}
          </select>
          {toEveryone && (
            <p className="mt-1 text-xs text-mute">
              Each teammate gets their own copy. It shows up once in your Sent box.
            </p>
          )}
        </div>

        <div>
          <label className={fieldLabel}>Subject</label>
          <input
            value={subject}
            onChange={(e) => setSubject(e.target.value)}
            className={field}
            placeholder="Sprint review notes"
          />
        </div>

        <div>
          <label className={fieldLabel}>Message</label>
          <textarea
            value={body}
            onChange={(e) => setBody(e.target.value)}
            rows={6}
            className={field}
            placeholder="What do they need to know?"
          />
        </div>

        {error && <p className="text-xs text-danger">{error}</p>}

        <div className="mt-2 flex justify-end gap-2">
          <button
            type="button"
            onClick={handleClose}
            className="rounded-md border border-line px-4 py-2 text-sm font-medium hover:bg-paper"
          >
            Cancel
          </button>
          <button
            type="submit"
            disabled={sending}
            className="rounded-md bg-ink px-4 py-2 text-sm font-medium text-white hover:bg-black disabled:opacity-60"
          >
            {sending ? 'Sending…' : toEveryone ? 'Send to everyone' : 'Send'}
          </button>
        </div>
      </form>
    </Modal>
  )
}
