import { useState } from 'react'
import { useAppStore, type NotificationCategory } from '../store/useAppStore'
import type { MailMessage } from '../store/useAppStore'
import { ComposeMessageModal } from '../components/mail/ComposeMessageModal'
import { MessageModal } from '../components/mail/MessageModal'

type Category = NotificationCategory
type View = 'NOTIFICATIONS' | 'MAIL'
type Box = 'received' | 'sent'

const tabs: { key: 'ALL' | Category; label: string }[] = [
  { key: 'ALL', label: 'All' },
  { key: 'MENTIONS', label: 'Mentions' },
  { key: 'TASKS', label: 'Tasks' },
  { key: 'PROJECTS', label: 'Projects' },
  { key: 'AI', label: 'AI' },
  { key: 'SYSTEM', label: 'System' },
]

const categoryColor: Record<Category, string> = {
  MENTIONS: 'text-info',
  TASKS: 'text-ink',
  PROJECTS: 'text-ink',
  AI: 'text-ink',
  SYSTEM: 'text-mute',
}

const rowButton = 'rounded-md border border-line px-3 py-1.5 font-medium hover:bg-paper'

export function Inbox() {
  const notifications = useAppStore((s) => s.notifications)
  const markRead = useAppStore((s) => s.markNotificationRead)
  const archive = useAppStore((s) => s.archiveNotification)
  const markAllRead = useAppStore((s) => s.markAllNotificationsRead)
  const mutedCategories = useAppStore((s) => s.settings.mutedCategories)
  const inboxMessages = useAppStore((s) => s.inboxMessages)
  const sentMessages = useAppStore((s) => s.sentMessages)
  const markMessageRead = useAppStore((s) => s.markMessageRead)
  const deleteMessage = useAppStore((s) => s.deleteMessage)
  const loadMessages = useAppStore((s) => s.loadMessages)

  const [view, setView] = useState<View>('NOTIFICATIONS')
  const [tab, setTab] = useState<'ALL' | Category>('ALL')
  const [box, setBox] = useState<Box>('received')
  const [openMessage, setOpenMessage] = useState<MailMessage | null>(null)
  const [compose, setCompose] = useState<{ recipientId: string; subject: string } | null>(null)

  const unmuted = notifications.filter((n) => !mutedCategories.includes(n.category))
  const visible = unmuted.filter(
    (n) => !n.archived && (tab === 'ALL' || n.category === tab),
  )
  const unreadCount = unmuted.filter((n) => !n.read && !n.archived).length

  const mail = box === 'received' ? inboxMessages : sentMessages
  const unreadMail = inboxMessages.filter((m) => !m.read).length

  function openMail(message: MailMessage) {
    setOpenMessage(message)
    if (box === 'received' && !message.read) {
      markMessageRead(message.id)
    }
  }

  return (
    <div className="px-4 py-6 sm:px-8 sm:py-8 lg:px-16 lg:py-12">
      <div className="flex items-start justify-between gap-4">
        <div>
          <div className="mb-1 text-xs font-medium uppercase tracking-widest text-mute">
            Notifications
          </div>
          <h1 className="text-3xl font-semibold tracking-tight text-ink sm:text-4xl lg:text-5xl">Inbox</h1>
        </div>
        <button
          onClick={() => setCompose({ recipientId: '', subject: '' })}
          className="shrink-0 rounded-md bg-ink px-4 py-2 text-sm font-medium text-white hover:bg-black"
        >
          + New message
        </button>
      </div>

      <div className="mt-6 flex gap-1 border-b border-line">
        <button
          onClick={() => setView('NOTIFICATIONS')}
          className={`px-4 py-2 text-sm font-medium ${
            view === 'NOTIFICATIONS' ? 'border-b-2 border-ink text-ink' : 'text-mute hover:text-ink'
          }`}
        >
          Notifications
          {unreadCount > 0 && <span className="ml-2 text-xs text-mute">{unreadCount}</span>}
        </button>
        <button
          onClick={() => {
            setView('MAIL')
            // Landing on Mail is the moment a stale list matters most, so
            // refresh rather than relying on the load done at sign-in.
            void loadMessages()
          }}
          className={`px-4 py-2 text-sm font-medium ${
            view === 'MAIL' ? 'border-b-2 border-ink text-ink' : 'text-mute hover:text-ink'
          }`}
        >
          Mail
          {unreadMail > 0 && <span className="ml-2 text-xs text-mute">{unreadMail}</span>}
        </button>
      </div>

      {view === 'NOTIFICATIONS' ? (
        <>
          <div className="mt-3 flex items-center gap-4">
            {unreadCount > 0 && <p className="text-sm text-mute">{unreadCount} unread</p>}
            {unreadCount > 0 && (
              <button
                onClick={() => void markAllRead()}
                className="text-xs font-medium text-ink underline-offset-2 hover:underline"
              >
                Mark all read
              </button>
            )}
          </div>

          <div className="mt-4 flex gap-1 overflow-x-auto border-b border-line">
            {tabs.map((t) => (
              <button
                key={t.key}
                onClick={() => setTab(t.key)}
                className={`shrink-0 px-4 py-2 text-sm font-medium ${
                  tab === t.key
                    ? 'border-b-2 border-ink text-ink'
                    : 'text-mute hover:text-ink'
                }`}
              >
                {t.label}
              </button>
            ))}
          </div>

          <div className="mt-4 border border-line bg-white">
            {visible.length === 0 && (
              <div className="p-8 text-sm text-mute">Nothing here.</div>
            )}
            {visible.map((n) => (
              <div
                key={n.id}
                className="flex flex-col gap-3 border-b border-line px-4 py-4 last:border-0 sm:flex-row sm:items-center sm:justify-between sm:gap-4 sm:px-5"
              >
                <div className="flex items-center gap-3">
                  <span
                    className={`h-2 w-2 shrink-0 rounded-full ${n.read ? 'bg-transparent' : 'bg-accent'}`}
                  />
                  <div>
                    <span className={`mr-2 text-xs font-semibold uppercase tracking-wide ${categoryColor[n.category]}`}>
                      {n.category}
                    </span>
                    <span className={`text-sm ${n.read ? 'text-ink/70' : 'font-medium text-ink'}`}>
                      {n.text}
                    </span>
                    <div className="mt-0.5 text-xs text-mute">{n.time}</div>
                  </div>
                </div>
                <div className="flex flex-wrap shrink-0 gap-2 text-xs">
                  {!n.read && (
                    <button onClick={() => markRead(n.id)} className={rowButton}>
                      Mark read
                    </button>
                  )}
                  <button onClick={() => archive(n.id)} className={rowButton}>
                    Archive
                  </button>
                  <button className="rounded-md bg-ink px-3 py-1.5 font-medium text-white hover:bg-black">
                    Open
                  </button>
                </div>
              </div>
            ))}
          </div>
        </>
      ) : (
        <>
          <div className="mt-4 flex gap-1">
            {(['received', 'sent'] as Box[]).map((b) => (
              <button
                key={b}
                onClick={() => setBox(b)}
                className={`rounded-md px-3 py-1.5 text-sm font-medium ${
                  box === b ? 'bg-ink text-white' : 'border border-line text-mute hover:text-ink'
                }`}
              >
                {b === 'received' ? 'Received' : 'Sent'}
                {b === 'received' && unreadMail > 0 && (
                  <span className={box === b ? 'ml-2 text-xs text-white/80' : 'ml-2 text-xs'}>
                    {unreadMail}
                  </span>
                )}
              </button>
            ))}
          </div>

          <div className="mt-4 border border-line bg-white">
            {mail.length === 0 && (
              <div className="p-8 text-sm text-mute">
                {box === 'received'
                  ? 'No messages yet. Write to a teammate with “+ New message”.'
                  : 'Nothing sent yet.'}
              </div>
            )}
            {mail.map((m) => (
              <div
                key={m.id}
                className="flex flex-col gap-3 border-b border-line px-4 py-4 last:border-0 sm:flex-row sm:items-center sm:justify-between sm:gap-4 sm:px-5"
              >
                <div className="flex min-w-0 items-center gap-3">
                  <span
                    className={`h-2 w-2 shrink-0 rounded-full ${
                      box === 'received' && !m.read ? 'bg-accent' : 'bg-transparent'
                    }`}
                  />
                  <div className="min-w-0">
                    <div className="text-xs font-semibold uppercase tracking-wide text-mute">
                      {box === 'received' ? `From ${m.senderName}` : `To ${m.recipientName}`}
                    </div>
                    <div
                      className={`truncate text-sm ${
                        box === 'received' && !m.read ? 'font-semibold text-ink' : 'text-ink/80'
                      }`}
                    >
                      {m.subject}
                    </div>
                    <div className="truncate text-xs text-mute">
                      {m.body.length > 120 ? `${m.body.slice(0, 120)}…` : m.body}
                    </div>
                    <div className="mt-0.5 text-xs text-mute">{m.time}</div>
                  </div>
                </div>
                <div className="flex flex-wrap shrink-0 gap-2 text-xs">
                  <button onClick={() => openMail(m)} className="rounded-md bg-ink px-3 py-1.5 font-medium text-white hover:bg-black">
                    Open
                  </button>
                  {box === 'received' && !m.read && (
                    <button onClick={() => markMessageRead(m.id)} className={rowButton}>
                      Mark read
                    </button>
                  )}
                  <button
                    onClick={() => {
                      if (openMessage?.id === m.id) setOpenMessage(null)
                      void deleteMessage(m.id)
                    }}
                    className={rowButton}
                  >
                    Delete
                  </button>
                </div>
              </div>
            ))}
          </div>
        </>
      )}

      {/* Mounted only while open, so each compose (new mail or a reply) starts
          with its own pre-filled fields instead of the previous draft's. */}
      {compose && (
        <ComposeMessageModal
          open
          initialRecipientId={compose.recipientId}
          initialSubject={compose.subject}
          onClose={() => setCompose(null)}
          onSent={() => {
            setBox('sent')
            void loadMessages()
          }}
        />
      )}

      <MessageModal
        message={openMessage}
        box={box}
        onClose={() => setOpenMessage(null)}
        onReply={(m) => {
          setOpenMessage(null)
          setCompose({
            recipientId: box === 'received' ? m.senderId : m.recipientId,
            subject: m.subject.startsWith('Re: ') ? m.subject : `Re: ${m.subject}`,
          })
        }}
      />
    </div>
  )
}
