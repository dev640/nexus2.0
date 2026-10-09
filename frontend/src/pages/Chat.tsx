import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  Hash,
  Lock,
  MessageSquare,
  Plus,
  Search,
  Send,
  SmilePlus,
  Users,
  X,
} from 'lucide-react'
import { useAppStore } from '../store/useAppStore'
import {
  apiChatChannels,
  apiChatCreateChannel,
  apiChatDeleteMessage,
  apiChatDiscoverChannels,
  apiChatEditMessage,
  apiChatJoinChannel,
  apiChatLeaveChannel,
  apiChatDeleteChannel,
  apiChatMarkRead,
  apiChatMessages,
  apiChatOpenDm,
  apiChatPostMessage,
  apiChatReact,
  apiChatSearch,
  apiChatUnread,
  apiErrorMessage,
  apiListUsers,
  type ApiChatChannel,
  type ApiChatMessage,
  type ApiUser,
} from '../lib/api'
import { useDeepLink } from '../hooks/useDeepLink'
import { connectChatSocket, type ChatSocketEvent } from '../lib/chatSocket'

const EMOJIS = ['👍', '🎉', '❤️', '😂', '👀', '✅']

/** How long a message stays marked after an alert opens the channel it is in. */
const HIGHLIGHT_MS = 2500

function channelTitle(c: ApiChatChannel): string {
  return c.type === 'DM' ? c.partnerName || 'Direct message' : `#${c.name}`
}

function timeLabel(iso: string): string {
  const d = new Date(iso)
  return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

export function ChatPage() {
  const currentUser = useAppStore((s) => s.currentUser)
  // VIEWER is read-only: the backend rejects chat writes with 403, so a
  // viewer can read every channel they belong to but cannot post.
  const canWrite = useAppStore((s) => s.currentUser?.role !== 'VIEWER')
  const [confirmDeleteId, setConfirmDeleteId] = useState<number | null>(null)
  const [users, setUsers] = useState<ApiUser[]>([])

  const [channels, setChannels] = useState<ApiChatChannel[]>([])
  const [discoverable, setDiscoverable] = useState<ApiChatChannel[]>([])
  const [activeId, setActiveId] = useState<number | null>(null)
  const [messages, setMessages] = useState<ApiChatMessage[]>([])
  const [draft, setDraft] = useState('')
  const [unread, setUnread] = useState<Record<number, number>>({})
  const [typingIn, setTypingIn] = useState<{ channelId: number; userId: number; name: string; at: number } | null>(null)
  const [online, setOnline] = useState<number[]>([])
  const [connected, setConnected] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [search, setSearch] = useState('')
  const [searchResults, setSearchResults] = useState<ApiChatMessage[] | null>(null)
  const [creating, setCreating] = useState(false)
  const [newName, setNewName] = useState('')
  const [newType, setNewType] = useState<'PUBLIC' | 'PRIVATE'>('PUBLIC')
  const [showDmPicker, setShowDmPicker] = useState(false)
  const [editingId, setEditingId] = useState<number | null>(null)
  const [editingBody, setEditingBody] = useState('')
  const [highlightMessageId, setHighlightMessageId] = useState<number | null>(null)

  const bottomRef = useRef<HTMLDivElement | null>(null)
  const socketRef = useRef<{ dispose: () => void; send: (frame: Record<string, unknown>) => void } | null>(null)
  const activeIdRef = useRef<number | null>(null)

  useEffect(() => {
    activeIdRef.current = activeId
  }, [activeId])

  // ---------- data loading ----------

  const refreshUnread = useCallback(async () => {
    try {
      const data = await apiChatUnread()
      const map: Record<number, number> = {}
      for (const c of data.channels) map[c.channelId] = c.count
      setUnread(map)
    } catch {
      // badges are best-effort
    }
  }, [])

  const loadChannels = useCallback(async () => {
    try {
      const [mine, others] = await Promise.all([apiChatChannels(), apiChatDiscoverChannels()])
      setChannels(mine)
      setDiscoverable(others)
      setActiveId((current) => current ?? mine[0]?.id ?? null)
      void refreshUnread()
    } catch (err) {
      setError(apiErrorMessage(err, 'Could not load conversations'))
    }
  }, [refreshUnread])

  const loadMessages = useCallback(async (channelId: number) => {
    try {
      const page = await apiChatMessages(channelId)
      setMessages(page)
      void apiChatMarkRead(channelId).then(refreshUnread)
    } catch (err) {
      setError(apiErrorMessage(err, 'Could not load messages'))
    }
  }, [refreshUnread])

  useEffect(() => {
    apiListUsers().then(setUsers).catch(() => setUsers([]))
    void loadChannels()
  }, [loadChannels])

  useEffect(() => {
    if (activeId != null) void loadMessages(activeId)
  }, [activeId, loadMessages])

  // scroll to bottom when the visible thread changes
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ block: 'end' })
  }, [messages.length, activeId])

  // An alert about a message arrives as ?channel=<id>&message=<id>: open that
  // channel, then bring the message itself into view and mark it. This runs
  // after the scroll-to-bottom above, so it wins.
  useDeepLink(
    ['channel', 'message'],
    (query) => {
      const channelId = Number(query.get('channel'))
      if (!Number.isFinite(channelId) || channelId <= 0) return 'gone'
      if (!channels.some((c) => c.id === channelId)) return 'gone'
      setActiveId(channelId)
      const messageId = Number(query.get('message'))
      if (Number.isFinite(messageId) && messageId > 0) setHighlightMessageId(messageId)
      return 'open'
    },
    // The channel may have been created after this viewer's list was fetched.
    { refresh: loadChannels },
  )

  // Only a message that is actually on screen is chased: one that was deleted,
  // or that is older than the page that loaded, is silently ignored.
  const visibleHighlightId =
    highlightMessageId != null && messages.some((m) => m.id === highlightMessageId)
      ? highlightMessageId
      : null

  useEffect(() => {
    if (visibleHighlightId == null) return
    document.getElementById(`chat-message-${visibleHighlightId}`)?.scrollIntoView({ block: 'center' })
    const timer = window.setTimeout(() => setHighlightMessageId(null), HIGHLIGHT_MS)
    return () => window.clearTimeout(timer)
  }, [visibleHighlightId])

  // ---------- live socket ----------

  const applyEvent = useCallback((event: ChatSocketEvent) => {
    switch (event.type) {
      case 'message.created': {
        const incoming = event.message
        if (incoming.channelId === activeIdRef.current) {
          setMessages((prev) => (prev.some((m) => m.id === incoming.id) ? prev : [...prev, incoming]))
          if (incoming.authorId !== currentUser?.id) {
            void apiChatMarkRead(event.channelId).then(refreshUnread)
          }
        } else {
          void refreshUnread()
        }
        break
      }
      case 'message.updated':
        setMessages((prev) => prev.map((m) => (m.id === event.message.id ? event.message : m)))
        break
      case 'message.deleted':
        if (event.messageId != null) {
          setMessages((prev) => prev.filter((m) => m.id !== event.messageId))
        }
        break
      case 'reactions.updated':
        setMessages((prev) =>
          prev.map((m) => {
            const mine = event.reactions.find((r) => r.messageId === m.id)
            if (!mine) return m
            const reactions = { ...m.reactions }
            if (mine.userIds.length === 0) delete reactions[mine.emoji]
            else reactions[mine.emoji] = mine.userIds
            return { ...m, reactions }
          }),
        )
        break
      case 'channel.created':
        void loadChannels()
        break
      case 'typing':
        setTypingIn({ channelId: event.channelId, userId: event.userId, name: event.userName, at: Date.now() })
        break
      case 'presence':
        setOnline(event.online)
        break
    }
  }, [currentUser?.id, loadChannels, refreshUnread])

  useEffect(() => {
    const socket = connectChatSocket(applyEvent, setConnected)
    socketRef.current = socket
    return () => socket.dispose()
  }, [applyEvent])

  // expire typing indicator after 3.5s
  useEffect(() => {
    if (!typingIn) return
    const t = window.setTimeout(() => setTypingIn(null), 3500)
    return () => window.clearTimeout(t)
  }, [typingIn])

  // ---------- actions ----------

  async function send() {
    const body = draft.trim()
    if (!body || activeId == null) return
    setDraft('')
    try {
      const created = await apiChatPostMessage(activeId, body)
      setMessages((prev) => (prev.some((m) => m.id === created.id) ? prev : [...prev, created]))
      void refreshUnread()
    } catch (err) {
      setError(apiErrorMessage(err, 'Message not sent'))
      setDraft(body)
    }
  }

  async function toggleReaction(message: ApiChatMessage, emoji: string) {
    const add = !(message.reactions[emoji] || []).includes(currentUser?.id ?? -1)
    try {
      const updated = await apiChatReact(message.id, emoji, add)
      setMessages((prev) => prev.map((m) => (m.id === updated.id ? updated : m)))
    } catch (err) {
      setError(apiErrorMessage(err, 'Reaction failed'))
    }
  }

  async function saveEdit() {
    if (editingId == null) return
    const body = editingBody.trim()
    if (!body) return
    try {
      const updated = await apiChatEditMessage(editingId, body)
      setMessages((prev) => prev.map((m) => (m.id === updated.id ? updated : m)))
      setEditingId(null)
    } catch (err) {
      setError(apiErrorMessage(err, 'Edit failed'))
    }
  }

  async function removeMessage(id: number) {
    try {
      await apiChatDeleteMessage(id)
      setMessages((prev) => prev.filter((m) => m.id !== id))
    } catch (err) {
      setError(apiErrorMessage(err, 'Delete failed'))
    }
  }

  async function openDm(user: ApiUser) {
    setShowDmPicker(false)
    try {
      const dm = await apiChatOpenDm(user.id)
      await loadChannels()
      setActiveId(dm.id)
    } catch (err) {
      setError(apiErrorMessage(err, 'Could not open conversation'))
    }
  }

  async function join(channelId: number) {
    try {
      await apiChatJoinChannel(channelId)
      await loadChannels()
      setActiveId(channelId)
    } catch (err) {
      setError(apiErrorMessage(err, 'Could not join channel'))
    }
  }

  async function leave(channelId: number) {
    try {
      await apiChatLeaveChannel(channelId)
      if (activeId === channelId) setActiveId(null)
      await loadChannels()
    } catch (err) {
      setError(apiErrorMessage(err, 'Could not leave channel'))
    }
  }

  /**
   * Deletes a channel and its whole history. The server restricts this to the
   * creator or an admin and refuses direct messages outright — two people share
   * a DM, so deleting it would destroy the other person's history too.
   */
  async function removeChannel(channelId: number) {
    try {
      await apiChatDeleteChannel(channelId)
      if (activeId === channelId) setActiveId(null)
      setConfirmDeleteId(null)
      await loadChannels()
    } catch (err) {
      setError(apiErrorMessage(err, 'Could not delete channel'))
    }
  }

  async function create() {
    const name = newName.trim()
    if (!name) return
    try {
      const created = await apiChatCreateChannel(name, newType)
      setCreating(false)
      setNewName('')
      await loadChannels()
      setActiveId(created.id)
    } catch (err) {
      setError(apiErrorMessage(err, 'Could not create channel'))
    }
  }

  async function runSearch() {
    const q = search.trim()
    if (q.length < 2) {
      setSearchResults(null)
      return
    }
    try {
      setSearchResults(await apiChatSearch(q))
    } catch {
      setSearchResults([])
    }
  }

  // ---------- derived ----------

  const active = channels.find((c) => c.id === activeId) ?? null
  const dmCandidates = useMemo(
    () => users.filter((u) => u.id !== currentUser?.id),
    [users, currentUser?.id],
  )
  const activeUnread = activeId != null ? unread[activeId] ?? 0 : 0
  void activeUnread // surfaced via sidebar badges

  function composerValue(v: string) {
    setDraft(v)
    if (activeId != null && v.length % 4 === 1) {
      socketRef.current?.send({ type: 'typing', channelId: activeId, name: currentUser?.name ?? 'Someone' })
    }
  }

  return (
    <div className="flex h-[calc(100vh-4rem)] min-h-[480px] flex-col px-4 py-4 sm:px-6 lg:px-10">
      <div className="mb-3 flex flex-wrap items-end justify-between gap-3">
        <div>
          <div className="text-xs font-medium uppercase tracking-widest text-mute">Team chat</div>
          <h1 className="text-2xl font-semibold tracking-tight text-ink sm:text-3xl">Slack</h1>
        </div>
        <div className="flex items-center gap-2">
          <span className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs ${connected ? 'bg-ink text-white' : 'bg-line text-mute'}`}>
            <span className={`h-1.5 w-1.5 rounded-full ${connected ? 'bg-emerald-400' : 'bg-mute'}`} />
            {connected ? 'Live' : 'Reconnecting…'}
          </span>
          {canWrite ? (
            <button
              onClick={() => setCreating(true)}
              className="inline-flex items-center gap-1.5 rounded-md bg-ink px-3 py-1.5 text-sm font-medium text-white hover:opacity-90"
            >
              <Plus size={14} /> Channel
            </button>
          ) : null}
        </div>
      </div>

      {error && (
        <div className="mb-3 flex items-center justify-between rounded-md border border-line bg-white px-3 py-2 text-sm text-ink">
          <span>{error}</span>
          <button onClick={() => setError(null)} aria-label="Dismiss"><X size={14} /></button>
        </div>
      )}

      <div className="grid min-h-0 flex-1 grid-cols-1 gap-4 lg:grid-cols-[260px_1fr]">
        {/* sidebar */}
        <aside className="flex min-h-0 flex-col rounded-xl border border-line bg-white p-3">
          <div className="relative mb-2">
            <Search size={14} className="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-mute" />
            <input
              value={search}
              onChange={(e) => { setSearch(e.target.value); void runSearch() }}
              placeholder="Search messages"
              className="w-full rounded-md border border-line py-1.5 pl-8 pr-2 text-sm outline-none focus:border-ink"
            />
          </div>

          <div className="mb-1 flex items-center justify-between px-1">
            <span className="text-xs font-semibold uppercase tracking-wide text-mute">Channels</span>
            <button onClick={() => setCreating(true)} aria-label="New channel" className="text-mute hover:text-ink"><Plus size={14} /></button>
          </div>
          <div className="min-h-0 flex-1 space-y-0.5 overflow-y-auto pr-0.5">
            {channels.filter((c) => c.type !== 'DM').map((c) => (
              <button
                key={c.id}
                onClick={() => { setSearchResults(null); setActiveId(c.id) }}
                className={`flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm ${
                  activeId === c.id ? 'bg-ink text-white' : 'text-ink/80 hover:bg-line/60'
                }`}
              >
                {c.type === 'PRIVATE' ? <Lock size={13} /> : <Hash size={13} />}
                <span className="truncate">{c.name}</span>
                {(unread[c.id] ?? 0) > 0 && activeId !== c.id && (
                  <span className="ml-auto rounded-full bg-ink px-1.5 text-[10px] font-semibold text-white">{unread[c.id]}</span>
                )}
              </button>
            ))}

            <div className="mt-3 flex items-center justify-between px-1">
              <span className="text-xs font-semibold uppercase tracking-wide text-mute">Direct messages</span>
              <button onClick={() => setShowDmPicker(true)} aria-label="New direct message" className="text-mute hover:text-ink"><Plus size={14} /></button>
            </div>
            {channels.filter((c) => c.type === 'DM').map((c) => (
              <button
                key={c.id}
                onClick={() => { setSearchResults(null); setActiveId(c.id) }}
                className={`flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm ${
                  activeId === c.id ? 'bg-ink text-white' : 'text-ink/80 hover:bg-line/60'
                }`}
              >
                <MessageSquare size={13} />
                <span className="truncate">{channelTitle(c)}</span>
                {online.includes(c.partnerId ?? -1) && <span className="h-1.5 w-1.5 rounded-full bg-emerald-500" />}
                {(unread[c.id] ?? 0) > 0 && activeId !== c.id && (
                  <span className="ml-auto rounded-full bg-ink px-1.5 text-[10px] font-semibold text-white">{unread[c.id]}</span>
                )}
              </button>
            ))}

            {discoverable.length > 0 && (
              <>
                <div className="mt-3 px-1 text-xs font-semibold uppercase tracking-wide text-mute">Browse</div>
                {discoverable.map((c) => (
                  <button
                    key={c.id}
                    onClick={() => void join(c.id)}
                    className="flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm text-mute hover:bg-line/60 hover:text-ink"
                  >
                    <Hash size={13} />
                    <span className="truncate">{c.name}</span>
                    <span className="ml-auto text-[10px] uppercase">join</span>
                  </button>
                ))}
              </>
            )}
          </div>
        </aside>

        {/* thread */}
        <section className="flex min-h-0 flex-col rounded-xl border border-line bg-white">
          {searchResults ? (
            <div className="flex min-h-0 flex-1 flex-col">
              <div className="flex items-center justify-between border-b border-line px-4 py-3">
                <span className="text-sm font-medium">Results for “{search}”</span>
                <button onClick={() => setSearchResults(null)} className="text-mute hover:text-ink"><X size={16} /></button>
              </div>
              <div className="min-h-0 flex-1 overflow-y-auto p-4">
                {searchResults.length === 0 && <p className="text-sm text-mute">No messages matched.</p>}
                {searchResults.map((m) => (
                  <div key={m.id} className="border-b border-line/60 py-2 text-sm">
                    <span className="font-medium">{m.authorName ?? 'Unknown'}</span>
                    <span className="ml-2 text-xs text-mute">{timeLabel(m.createdAt)}</span>
                    <p className="mt-0.5 text-ink/90">{m.body}</p>
                  </div>
                ))}
              </div>
            </div>
          ) : !active ? (
            <div className="flex flex-1 flex-col items-center justify-center gap-2 p-8 text-center">
              <Users size={28} className="text-mute" />
              <p className="text-sm text-mute">Pick a conversation on the left, or start one.</p>
            </div>
          ) : (
            <>
              <header className="flex items-center justify-between border-b border-line px-4 py-3">
                <div className="min-w-0">
                  <div className="flex items-center gap-1.5 font-semibold text-ink">
                    {active.type === 'PRIVATE' ? <Lock size={14} /> : active.type === 'DM' ? <MessageSquare size={14} /> : <Hash size={14} />}
                    <span className="truncate">{channelTitle(active)}</span>
                  </div>
                  {active.topic && <p className="truncate text-xs text-mute">{active.topic}</p>}
                </div>
                {active.type !== 'DM' && (
                  <div className="flex items-center gap-3">
                    {/* Creator or admin only — matching the server, so the button
                        is not offered to someone who can only fail. */}
                    {(currentUser &&
                      (active.createdBy === currentUser.email || currentUser.role === 'ADMIN')) && (
                      confirmDeleteId === active.id ? (
                        <span className="flex items-center gap-2">
                          <span className="text-xs text-danger">Delete #{active.name} and all messages?</span>
                          <button
                            onClick={() => setConfirmDeleteId(null)}
                            className="text-xs text-mute hover:text-ink"
                          >
                            Cancel
                          </button>
                          <button
                            onClick={() => void removeChannel(active.id)}
                            className="text-xs font-medium text-danger hover:opacity-80"
                          >
                            Delete
                          </button>
                        </span>
                      ) : (
                        <button
                          onClick={() => setConfirmDeleteId(active.id)}
                          className="text-xs text-mute hover:text-danger"
                        >
                          Delete
                        </button>
                      )
                    )}
                    <button onClick={() => void leave(active.id)} className="text-xs text-mute hover:text-ink">Leave</button>
                  </div>
                )}
              </header>

              <div className="min-h-0 flex-1 space-y-1 overflow-y-auto px-4 py-3">
                {messages.length === 0 && <p className="py-8 text-center text-sm text-mute">No messages yet — say hello 👋</p>}
                {messages.map((m, i) => {
                  const mine = m.authorId === currentUser?.id
                  const prev = messages[i - 1]
                  const grouped = prev && prev.authorId === m.authorId
                    && new Date(m.createdAt).getTime() - new Date(prev.createdAt).getTime() < 5 * 60 * 1000
                  return (
                    <div
                      key={m.id}
                      id={`chat-message-${m.id}`}
                      className={`group flex gap-2.5 rounded-md ${grouped ? 'mt-0.5' : 'mt-3'} ${
                        visibleHighlightId === m.id ? 'bg-accent/10 ring-1 ring-accent/40' : ''
                      }`}
                    >
                      <div className="w-8 shrink-0">
                        {!grouped && (
                          <div className="flex h-8 w-8 items-center justify-center rounded-full bg-line text-xs font-semibold text-ink">
                            {(m.authorName ?? '?').slice(0, 1).toUpperCase()}
                          </div>
                        )}
                      </div>
                      <div className="min-w-0 flex-1">
                        {!grouped && (
                          <div className="flex items-baseline gap-2">
                            <span className="text-sm font-semibold text-ink">{mine ? 'You' : m.authorName ?? 'Unknown'}</span>
                            <span className="text-[11px] text-mute">{timeLabel(m.createdAt)}</span>
                          </div>
                        )}
                        {editingId === m.id ? (
                          <div className="mt-1 flex gap-2">
                            <input
                              value={editingBody}
                              onChange={(e) => setEditingBody(e.target.value)}
                              onKeyDown={(e) => { if (e.key === 'Enter') void saveEdit(); if (e.key === 'Escape') setEditingId(null) }}
                              className="flex-1 rounded-md border border-line px-2 py-1 text-sm outline-none focus:border-ink"
                            />
                            <button onClick={() => void saveEdit()} className="text-xs text-ink underline">save</button>
                            <button onClick={() => setEditingId(null)} className="text-xs text-mute">cancel</button>
                          </div>
                        ) : (
                          <p className="whitespace-pre-wrap break-words text-sm text-ink/90">
                            {m.body}
                            {m.edited && <span className="ml-1 text-[10px] text-mute">(edited)</span>}
                          </p>
                        )}
                        {Object.keys(m.reactions).length > 0 && (
                          <div className="mt-1 flex flex-wrap gap-1">
                            {Object.entries(m.reactions).map(([emoji, ids]) => (
                              <button
                                key={emoji}
                                onClick={() => void toggleReaction(m, emoji)}
                                className={`inline-flex items-center gap-1 rounded-full border px-1.5 py-0.5 text-xs ${
                                  ids.includes(currentUser?.id ?? -1) ? 'border-ink bg-ink/5 text-ink' : 'border-line text-mute hover:border-ink'
                                }`}
                              >
                                {emoji} {ids.length}
                              </button>
                            ))}
                          </div>
                        )}
                      </div>
                      <div className="flex shrink-0 items-start gap-1 opacity-0 transition-opacity group-hover:opacity-100">
                        {EMOJIS.slice(0, 3).map((e) => (
                          <button key={e} onClick={() => void toggleReaction(m, e)} aria-label={`React ${e}`} className="rounded p-1 text-mute hover:bg-line/60 hover:text-ink">
                            <SmilePlus size={14} />
                          </button>
                        ))}
                        {mine && (
                          <>
                            <button onClick={() => { setEditingId(m.id); setEditingBody(m.body) }} aria-label="Edit message" className="rounded p-1 text-mute hover:bg-line/60 hover:text-ink">
                              <span className="text-xs">✏️</span>
                            </button>
                            <button onClick={() => void removeMessage(m.id)} aria-label="Delete message" className="rounded p-1 text-mute hover:text-red-600">
                              <span className="text-xs">🗑️</span>
                            </button>
                          </>
                        )}
                      </div>
                    </div>
                  )
                })}
                <div ref={bottomRef} />
              </div>

              <div className="border-t border-line px-4 py-3">
                {typingIn && typingIn.channelId === active.id && (
                  <p className="mb-1 text-xs text-mute">{typingIn.name} is typing…</p>
                )}
                {canWrite ? (
                <div className="flex items-end gap-2">
                  <textarea
                    value={draft}
                    onChange={(e) => composerValue(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' && !e.shiftKey) {
                        e.preventDefault()
                        void send()
                      }
                    }}
                    rows={1}
                    placeholder={`Message ${channelTitle(active)}`}
                    className="max-h-32 min-h-[38px] flex-1 resize-none rounded-lg border border-line px-3 py-2 text-sm outline-none focus:border-ink"
                  />
                  <button
                    onClick={() => void send()}
                    disabled={!draft.trim()}
                    className="inline-flex h-[38px] items-center gap-1.5 rounded-lg bg-ink px-3 text-sm font-medium text-white disabled:opacity-40"
                  >
                    <Send size={14} /> Send
                  </button>
                </div>
                ) : (
                  <p className="rounded-lg border border-line bg-paper px-3 py-2 text-sm text-mute">
                    You have read-only access to this conversation.
                  </p>
                )}
                <p className="mt-1 text-[11px] text-mute">
                  <b>@username</b> mentions notify teammates · Enter sends · Shift+Enter for a new line
                </p>
              </div>
            </>
          )}
        </section>
      </div>

      {/* new channel dialog */}
      {creating && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4" onClick={() => setCreating(false)}>
          <div className="w-full max-w-sm rounded-xl bg-white p-5" onClick={(e) => e.stopPropagation()}>
            <h2 className="text-lg font-semibold text-ink">New channel</h2>
            <input
              autoFocus
              value={newName}
              onChange={(e) => setNewName(e.target.value)}
              placeholder="e.g. design-critique"
              className="mt-3 w-full rounded-md border border-line px-3 py-2 text-sm outline-none focus:border-ink"
            />
            <div className="mt-3 flex gap-2 text-sm">
              {(['PUBLIC', 'PRIVATE'] as const).map((t) => (
                <button
                  key={t}
                  onClick={() => setNewType(t)}
                  className={`rounded-md border px-3 py-1.5 ${newType === t ? 'border-ink bg-ink text-white' : 'border-line text-ink/70'}`}
                >
                  {t === 'PUBLIC' ? 'Public' : 'Private'}
                </button>
              ))}
            </div>
            <div className="mt-4 flex justify-end gap-2">
              <button onClick={() => setCreating(false)} className="rounded-md px-3 py-1.5 text-sm text-mute hover:text-ink">Cancel</button>
              <button onClick={() => void create()} className="rounded-md bg-ink px-3 py-1.5 text-sm font-medium text-white">Create</button>
            </div>
          </div>
        </div>
      )}

      {/* DM picker */}
      {showDmPicker && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4" onClick={() => setShowDmPicker(false)}>
          <div className="max-h-96 w-full max-w-sm overflow-y-auto rounded-xl bg-white p-3" onClick={(e) => e.stopPropagation()}>
            <h2 className="px-2 pb-2 text-sm font-semibold text-ink">Message a teammate</h2>
            {dmCandidates.length === 0 && <p className="px-2 pb-3 text-sm text-mute">No teammates yet.</p>}
            {dmCandidates.map((u) => (
              <button
                key={u.id}
                onClick={() => void openDm(u)}
                className="flex w-full items-center gap-2.5 rounded-md px-2 py-2 text-left text-sm hover:bg-line/60"
              >
                <div className="flex h-7 w-7 items-center justify-center rounded-full bg-line text-xs font-semibold">{u.name.slice(0, 1).toUpperCase()}</div>
                <span className="truncate">{u.name}</span>
                <span className="ml-auto truncate text-xs text-mute">{u.email}</span>
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}
