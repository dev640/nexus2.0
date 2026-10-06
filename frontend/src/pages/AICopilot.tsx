import { useCallback, useEffect, useRef, useState } from 'react'
import { Check, Copy, Plus, RefreshCw, Sparkles, Trash2, X } from 'lucide-react'
import {
  apiAiKnowledgeStatus,
  apiDeleteAiConversation,
  apiGetAiConversation,
  apiGetAnalyticsOverview,
  apiErrorMessage,
  apiListAiConversations,
  streamAiAsk,
  streamAiRegenerate,
  type AiConversation,
  type AiKnowledgeStatus,
  type AiMessage,
  type AiSource,
  type AiStreamHandlers,
  type ApiAnalyticsOverview,
} from '../lib/api'
import { Markdown } from '../components/ai/Markdown'

const sourceTypeLabel: Record<string, string> = {
  WIKI_PAGE: 'Wiki page',
  PROJECT: 'Project',
  TASK: 'Task',
  SPRINT: 'Sprint',
  WHITEBOARD_NOTE: 'Whiteboard note',
}

const suggestedPrompts = [
  'Summarize my project documents.',
  'What information do we have about Project Alpha?',
  'What changed recently?',
  'Which tasks are urgent right now?',
]

function sourceLabel(source: AiSource): string {
  return sourceTypeLabel[source.type] ?? source.type
}

/**
 * Nexus AI: a RAG assistant over the workspace knowledge index. Questions
 * stream over SSE from the backend, which retrieves only permission-relevant
 * Nexus context and calls OpenAI entirely server-side.
 */
export function AICopilot() {
  const [status, setStatus] = useState<AiKnowledgeStatus | null>(null)
  const [conversations, setConversations] = useState<AiConversation[]>([])
  const [activeId, setActiveId] = useState<string | null>(null)
  const [messages, setMessages] = useState<AiMessage[]>([])
  const [input, setInput] = useState('')
  const [busy, setBusy] = useState(false)
  const [streamText, setStreamText] = useState<string | null>(null)
  const [streamSources, setStreamSources] = useState<AiSource[]>([])
  const [error, setError] = useState('')
  const [copiedId, setCopiedId] = useState<string | null>(null)
  const [analytics, setAnalytics] = useState<ApiAnalyticsOverview | null>(null)

  const scrollRef = useRef<HTMLDivElement>(null)
  const nextId = useRef(1)

  // ---------- loading ----------

  const refreshConversations = useCallback(() => {
    apiListAiConversations()
      .then(setConversations)
      .catch(() => {
        /* history is non-critical */
      })
  }, [])

  useEffect(() => {
    apiAiKnowledgeStatus()
      .then(setStatus)
      .catch((err) => setError(apiErrorMessage(err, 'Could not load AI status')))
    refreshConversations()
    apiGetAnalyticsOverview()
      .then(setAnalytics)
      .catch(() => {
        /* panels degrade to empty states */
      })
  }, [refreshConversations])

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' })
  }, [messages, streamText])

  // ---------- actions ----------

  async function openConversation(id: string) {
    if (busy) return
    setError('')
    try {
      const detail = await apiGetAiConversation(id)
      setActiveId(id)
      setMessages(detail.messages)
    } catch (err) {
      setError(apiErrorMessage(err, 'Could not open that conversation'))
    }
  }

  function newChat() {
    if (busy) return
    setActiveId(null)
    setMessages([])
    setError('')
    setStreamText(null)
    setStreamSources([])
  }

  async function deleteConversation(id: string) {
    try {
      await apiDeleteAiConversation(id)
      if (activeId === id) newChat()
      refreshConversations()
    } catch (err) {
      setError(apiErrorMessage(err, 'Could not delete the conversation'))
    }
  }

  function copyMessage(message: AiMessage) {
    void navigator.clipboard.writeText(message.content).then(() => {
      setCopiedId(message.id)
      window.setTimeout(() => setCopiedId(null), 1500)
    })
  }

  /** Shared streaming loop for a fresh question and for regeneration. */
  async function runStream(
    start: (handlers: AiStreamHandlers) => Promise<void>,
    priorMessages: AiMessage[],
  ) {
    setBusy(true)
    setError('')
    setStreamText(null)
    setStreamSources([])

    let sources: AiSource[] = []
    let acc = ''
    let committed = false
    let failed = false

    const finish = (mode: string, model: string) => {
      const assistant: AiMessage = {
        id: `local-${nextId.current++}`,
        role: 'ASSISTANT',
        content: acc,
        sources,
        mode,
        model: model || null,
        createdAt: new Date().toISOString(),
      }
      committed = true
      setMessages([...priorMessages, assistant])
      setStreamText(null)
    }

    const handlers: AiStreamHandlers = {
      onMeta: (meta) => {
        sources = meta.sources
        setStreamSources(meta.sources)
        if (!activeId) {
          setActiveId(meta.conversationId)
          refreshConversations()
        }
      },
      onDelta: (text) => {
        acc += text
        setStreamText(acc)
      },
      onDone: (done) => finish(done.mode, done.model),
      onError: (message) => {
        failed = true
        setError(message)
      },
    }

    try {
      await start(handlers)
    } catch (err) {
      failed = true
      setError(apiErrorMessage(err, 'The AI request failed. Please try again.'))
    } finally {
      setBusy(false)
      setStreamText(null)
      if (!committed && !failed && acc) {
        // Stream ended without a terminal event; keep what arrived.
        finish('openai', '')
      }
      refreshConversations()
    }
  }

  async function send(question: string) {
    const q = question.trim()
    if (!q || busy) return

    const userMessage: AiMessage = {
      id: `local-${nextId.current++}`,
      role: 'USER',
      content: q,
      sources: [],
      mode: null,
      model: null,
      createdAt: new Date().toISOString(),
    }
    const prior = [...messages, userMessage]
    setMessages(prior)
    setInput('')

    await runStream(
      (handlers) => streamAiAsk({ question: q, conversationId: activeId }, handlers),
      prior,
    )
  }

  async function regenerate() {
    if (busy || !activeId) return
    const last = messages[messages.length - 1]
    const prior = last?.role === 'ASSISTANT' ? messages.slice(0, -1) : messages
    if (prior.length === 0) return

    // The backend drops the stale assistant reply and re-answers the last question.
    setMessages(prior)
    await runStream((handlers) => streamAiRegenerate(activeId, handlers), prior)
  }

  // ---------- derived ----------

  const lastMessage = messages[messages.length - 1]
  const canRegenerate =
    !busy && Boolean(activeId) && lastMessage?.role === 'ASSISTANT' && messages.length > 0
  const risks = analytics?.risks ?? []
  const teamLoad = analytics?.teamLoad ?? []
  const latestSprint = analytics?.velocity.perSprint[analytics.velocity.perSprint.length - 1]
  const isLastIndex = messages.length - 1

  return (
    <div className="px-4 py-6 sm:px-8 sm:py-8 lg:px-16 lg:py-12">
      <div className="mb-2 flex flex-wrap items-center gap-3 text-xs font-medium uppercase tracking-widest text-mute">
        <span>Intelligence</span>
        {status && (
          <span
            className={`rounded-full border px-2 py-0.5 normal-case tracking-normal ${
              status.configured
                ? 'border-accent bg-accent/20 text-ink'
                : 'border-line bg-paper text-mute'
            }`}
          >
            {status.configured ? `OpenAI · ${status.model}` : 'Context mode'}
          </span>
        )}
        {status && (
          <span className="normal-case tracking-normal text-mute">
            {status.indexedChunks} indexed chunks · {status.vectorsLoaded} vectors
          </span>
        )}
      </div>
      <h1 className="text-3xl font-semibold tracking-tight text-ink sm:text-4xl lg:text-5xl">
        Nexus AI
      </h1>
      <p className="mt-2 max-w-2xl text-sm text-mute">
        Ask anything about your workspace. Answers are grounded in your permission-filtered Nexus
        knowledge — wiki pages, projects, tasks, sprints and notes — and generated server-side.
      </p>

      {error && (
        <div className="mt-4 flex items-start justify-between gap-3 border border-danger/40 bg-danger/5 p-3 text-sm text-danger">
          <span>{error}</span>
          <button onClick={() => setError('')} aria-label="Dismiss error">
            <X size={14} />
          </button>
        </div>
      )}

      <div className="mt-8 flex flex-col gap-4 lg:flex-row">
        {/* Conversations */}
        <div className="hidden w-56 shrink-0 flex-col border border-line bg-white lg:flex">
          <div className="flex items-center justify-between border-b border-line px-3 py-2.5">
            <span className="text-xs font-semibold uppercase tracking-widest text-mute">
              Chats
            </span>
            <button
              onClick={newChat}
              disabled={busy}
              className="flex items-center gap-1 rounded-md border border-line px-2 py-1 text-xs text-ink hover:bg-paper disabled:opacity-50"
            >
              <Plus size={12} /> New
            </button>
          </div>
          <div className="flex max-h-[420px] flex-col overflow-y-auto p-2">
            {conversations.length === 0 && (
              <p className="px-1 py-2 text-xs text-mute">No conversations yet.</p>
            )}
            {conversations.map((conversation) => (
              <div key={conversation.id} className="group flex items-center gap-1">
                <button
                  onClick={() => void openConversation(conversation.id)}
                  className={`min-w-0 flex-1 truncate rounded-md px-2 py-1.5 text-left text-xs ${
                    conversation.id === activeId
                      ? 'bg-ink text-white'
                      : 'text-ink hover:bg-paper'
                  }`}
                  title={conversation.title}
                >
                  {conversation.title}
                </button>
                <button
                  onClick={() => void deleteConversation(conversation.id)}
                  className="shrink-0 rounded-md p-1 text-mute opacity-0 transition hover:text-danger group-hover:opacity-100"
                  aria-label={`Delete ${conversation.title}`}
                >
                  <Trash2 size={12} />
                </button>
              </div>
            ))}
          </div>
        </div>

        {/* Chat */}
        <div className="flex min-w-0 flex-1 flex-col border border-line bg-white">
          <div ref={scrollRef} className="flex h-[520px] flex-col gap-3 overflow-y-auto p-6">
            {messages.length === 0 && !streamText && (
              <div className="mr-auto max-w-[85%] rounded-lg border border-accent/40 bg-accent/10 px-4 py-3 text-sm leading-relaxed text-ink">
                <div className="mb-1 flex items-center gap-1.5 font-medium">
                  <Sparkles size={14} /> Ask your Nexus workspace anything
                </div>
                I retrieve only the most relevant, authorized Nexus content for each question —
                then answer with sources.
              </div>
            )}

            {messages.map((message, index) =>
              message.role === 'USER' ? (
                <div
                  key={message.id}
                  className="max-w-[85%] self-end rounded-lg bg-ink px-4 py-2.5 text-sm leading-relaxed text-white"
                >
                  {message.content}
                </div>
              ) : (
                <div
                  key={message.id}
                  className="mr-auto w-full max-w-[92%] border border-accent/40 bg-accent/10"
                >
                  <div className="px-4 py-3">
                    <Markdown>{message.content}</Markdown>
                  </div>
                  <div className="flex items-center justify-between gap-3 border-t border-line/70 px-3 py-2 text-[11px] text-mute">
                    <span>
                      {message.mode === 'openai'
                        ? `Nexus AI · ${message.model ?? 'OpenAI'}`
                        : 'Context-only answer'}
                    </span>
                    <div className="flex items-center gap-3">
                      <button
                        onClick={() => copyMessage(message)}
                        className="flex items-center gap-1 hover:text-ink"
                      >
                        {copiedId === message.id ? <Check size={12} /> : <Copy size={12} />}
                        {copiedId === message.id ? 'Copied' : 'Copy'}
                      </button>
                      {index === isLastIndex && canRegenerate && (
                        <button
                          onClick={() => void regenerate()}
                          className="flex items-center gap-1 hover:text-ink"
                        >
                          <RefreshCw size={12} /> Regenerate
                        </button>
                      )}
                    </div>
                  </div>
                  {message.sources.length > 0 && (
                    <div className="border-t border-line/70 px-3 py-2">
                      <div className="mb-1.5 text-[10px] font-medium uppercase tracking-widest text-mute">
                        Sources
                      </div>
                      <ol className="flex flex-wrap gap-1.5">
                        {message.sources.map((source, sourceIndex) => (
                          <li
                            key={`${source.type}-${source.sourceId}`}
                            className="border border-line bg-white px-2 py-0.5 text-[11px] text-ink"
                            title={source.updatedAt ? `Updated ${source.updatedAt}` : undefined}
                          >
                            [{sourceIndex + 1}] {sourceLabel(source)}: {source.title}
                          </li>
                        ))}
                      </ol>
                    </div>
                  )}
                </div>
              ),
            )}

            {streamText !== null && (
              <div className="mr-auto w-full max-w-[92%] border border-accent/40 bg-accent/10">
                <div className="px-4 py-3">
                  <Markdown>{streamText}</Markdown>
                </div>
                {streamSources.length > 0 && (
                  <div className="border-t border-line/70 px-3 py-2">
                    <div className="mb-1.5 text-[10px] font-medium uppercase tracking-widest text-mute">
                      Sources
                    </div>
                    <ol className="flex flex-wrap gap-1.5">
                      {streamSources.map((source, sourceIndex) => (
                        <li
                          key={`${source.type}-${source.sourceId}`}
                          className="border border-line bg-white px-2 py-0.5 text-[11px] text-ink"
                        >
                          [{sourceIndex + 1}] {sourceLabel(source)}: {source.title}
                        </li>
                      ))}
                    </ol>
                  </div>
                )}
              </div>
            )}

            {busy && streamText === null && (
              <div className="mr-auto rounded-lg border border-accent/40 bg-accent/10 px-4 py-2.5 text-sm text-mute">
                Searching your Nexus knowledge…
              </div>
            )}
          </div>

          <div className="border-t border-line p-4">
            <div className="mb-3 flex flex-wrap gap-2">
              {suggestedPrompts.map((prompt) => (
                <button
                  key={prompt}
                  onClick={() => void send(prompt)}
                  disabled={busy}
                  className="rounded-full border border-line px-3 py-1 text-xs text-mute hover:bg-paper disabled:opacity-50"
                >
                  {prompt}
                </button>
              ))}
            </div>
            <form
              onSubmit={(e) => {
                e.preventDefault()
                void send(input)
              }}
              className="flex gap-2"
            >
              <input
                value={input}
                onChange={(e) => setInput(e.target.value)}
                placeholder="Ask about projects, documents, tasks, what changed..."
                maxLength={2000}
                className="w-full rounded-md border border-line px-3 py-2 text-sm outline-none focus:border-ink"
              />
              <button
                type="submit"
                disabled={busy || input.trim().length === 0}
                className="shrink-0 rounded-md bg-ink px-4 py-2 text-sm font-medium text-white hover:bg-black disabled:opacity-60"
              >
                {busy ? 'Working…' : 'Send'}
              </button>
            </form>
            <p className="mt-2 text-[11px] text-mute">
              {status?.configured
                ? `Answers are generated by ${status.model} using permission-filtered Nexus context retrieved for you.`
                : 'No OPENAI_API_KEY configured yet — answers summarize the retrieved Nexus context directly. Set it server-side to enable generated answers.'}
            </p>
          </div>
        </div>

        {/* Workspace pulse (preserves the Copilot side panels) */}
        <div className="flex w-full shrink-0 flex-col gap-4 lg:w-72">
          <div className="border border-line bg-white p-5">
            <h2 className="mb-3 text-xs font-semibold uppercase tracking-widest text-mute">
              Detected Risks
            </h2>
            {risks.length === 0 ? (
              <p className="text-sm text-mute">No risks detected.</p>
            ) : (
              <div className="flex flex-col gap-2">
                {risks.slice(0, 5).map((risk) => (
                  <div key={risk.taskId} className="text-sm">
                    <div className="font-medium text-ink">{risk.title}</div>
                    <div className="text-xs text-mute">{risk.reason}</div>
                  </div>
                ))}
              </div>
            )}
          </div>

          <div className="border border-line bg-white p-5">
            <h2 className="mb-3 text-xs font-semibold uppercase tracking-widest text-mute">
              Sprint Progress
            </h2>
            {!latestSprint ? (
              <p className="text-sm text-mute">No sprint data yet.</p>
            ) : (
              <div className="text-sm">
                <div className="font-medium text-ink">Sprint {latestSprint.number}</div>
                <div className="mt-1 text-xs text-mute">{latestSprint.goal}</div>
                <div className="mt-3 h-2 w-full rounded-full bg-line">
                  <div
                    className="h-2 rounded-full bg-info"
                    style={{
                      width: `${
                        latestSprint.committedPoints > 0
                          ? Math.min(
                              100,
                              Math.round(
                                (latestSprint.donePoints / latestSprint.committedPoints) * 100,
                              ),
                            )
                          : 0
                      }%`,
                    }}
                  />
                </div>
                <div className="mt-1 text-xs text-mute">
                  {latestSprint.donePoints} / {latestSprint.committedPoints} points
                </div>
              </div>
            )}
          </div>

          <div className="border border-line bg-white p-5">
            <h2 className="mb-3 text-xs font-semibold uppercase tracking-widest text-mute">
              Team Capacity
            </h2>
            {teamLoad.length === 0 ? (
              <p className="text-sm text-mute">No open assigned work.</p>
            ) : (
              <div className="flex flex-col gap-2">
                {teamLoad.map((member) => (
                  <div key={member.userId} className="flex justify-between text-sm">
                    <span>{member.name}</span>
                    <span className="text-mute">
                      {member.openPoints} pts · {member.openTasks} open
                    </span>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}
