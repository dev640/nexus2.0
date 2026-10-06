import { useEffect, useRef, useState } from 'react'
import {
  apiAskCopilot,
  apiErrorMessage,
  apiGetAnalyticsOverview,
  type ApiAnalyticsOverview,
} from '../lib/api'

interface ChatMessage {
  id: number
  role: 'user' | 'ai'
  text: string
}

const suggestedPrompts = [
  'What is blocking the sprint?',
  'Summarize urgent tasks',
  'How is the active sprint tracking?',
  'Who is overloaded right now?',
]

/** Cheap deterministic reply builder grounded in live analytics data. */
function buildReply(question: string, data: ApiAnalyticsOverview): string {
  const q = question.toLowerCase()
  const risks = data.risks
  const summary = data.summary

  if (q.includes('block') || q.includes('risk')) {
    if (risks.length === 0) return 'No risks detected right now — nothing is blocked or stalled.'
    const top = risks.slice(0, 4).map((r) => `"${r.title}" (${r.reason})`)
    return `${risks.length} risk${risks.length > 1 ? 's' : ''} detected in real task data: ${top.join('; ')}.`
  }

  if (q.includes('urgent') || q.includes('priority')) {
    const urgentRisks = risks.filter((r) => r.priority === 'URGENT')
    if (urgentRisks.length === 0) {
      return 'No urgent-priority work is at risk. Urgent work that has already started is on track.'
    }
    return `${urgentRisks.length} urgent task${urgentRisks.length > 1 ? 's need' : ' needs'} attention: ${urgentRisks
      .map((r) => r.title)
      .join(', ')}.`
  }

  if (q.includes('sprint') || q.includes('track') || q.includes('velocity')) {
    const latest = data.velocity.perSprint[data.velocity.perSprint.length - 1]
    if (!latest) return 'No sprint data yet — create a sprint from the Sprints page.'
    const pct = latest.committedPoints > 0
      ? Math.round((latest.donePoints / latest.committedPoints) * 100)
      : 0
    return `Sprint ${latest.number} ("${latest.goal}") is ${pct}% complete — ${latest.donePoints} of ${latest.committedPoints} committed points done. Overall completion across ${summary.totalTasks} tasks is ${summary.completionRate}%.`
  }

  if (q.includes('overload') || q.includes('capacity') || q.includes('workload') || q.includes('who')) {
    if (data.teamLoad.length === 0) return 'No open assigned work — nobody is carrying a backlog.'
    const [top, ...rest] = data.teamLoad
    const others = rest.slice(0, 2).map((m) => `${m.name} (${m.openPoints} pts)`)
    return `${top.name} is carrying the most open work at ${top.openPoints} points across ${top.openTasks} task${top.openTasks === 1 ? '' : 's'}${others.length ? `. Also loaded: ${others.join(', ')}` : ''}.`
  }

  return `Right now there are ${summary.totalTasks} tasks with a ${summary.completionRate}% completion rate, ${risks.length} detected risk${risks.length === 1 ? '' : 's'}, and ${data.teamLoad.length} people with open work. Ask about blockers, urgent items, sprint progress, or workload.`
}

export function AICopilot() {
  const [data, setData] = useState<ApiAnalyticsOverview | null>(null)
  const [loadError, setLoadError] = useState('')
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [input, setInput] = useState('')
  const [answerMode, setAnswerMode] = useState<'llm' | 'grounded'>('grounded')
  const [answerReason, setAnswerReason] = useState<string | null>(null)
  const [thinking, setThinking] = useState(false)
  const scrollRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    let cancelled = false
    apiGetAnalyticsOverview()
      .then((overview) => {
        if (cancelled) return
        setData(overview)
        setMessages([
          {
            id: 0,
            role: 'ai',
            text: buildReply('overview', overview),
          },
        ])
      })
      .catch((err) => {
        if (!cancelled) setLoadError(apiErrorMessage(err, 'Failed to load project data'))
      })
    return () => {
      cancelled = true
    }
  }, [])

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' })
  }, [messages])

  const counterRef = useRef(1)

  async function send(text: string) {
    const trimmed = text.trim()
    if (!trimmed || !data) return
    const baseId = counterRef.current
    counterRef.current = baseId + 2
    const userMsg: ChatMessage = { id: baseId, role: 'user', text: trimmed }
    setMessages((prev) => [...prev, userMsg])
    setInput('')
    setThinking(true)
    try {
      // Preferred path: server-side answer (uses an LLM when one is configured,
      // otherwise the same grounded computation, and it has live data.
      const reply = await apiAskCopilot(trimmed)
      setAnswerMode(reply.mode)
      setAnswerReason(reply.reason ?? null)
      setMessages((prev) => [...prev, { id: baseId + 1, role: 'ai', text: reply.answer }])
    } catch (err) {
      // Safety net: answer locally from the workspace snapshot we already have.
      setMessages((prev) => [
        ...prev,
        { id: baseId + 1, role: 'ai', text: buildReply(trimmed, data) },
      ])
      setAnswerMode('grounded')
      setAnswerReason('Copilot could not be reached')
      setLoadError(apiErrorMessage(err, 'Copilot is unreachable — answered from cached data'))
    } finally {
      setThinking(false)
    }
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    void send(input)
  }

  const risks = data?.risks ?? []
  const teamLoad = data?.teamLoad ?? []
  const latestSprint = data?.velocity.perSprint[data.velocity.perSprint.length - 1]

  return (
    <div className="px-4 py-6 sm:px-8 sm:py-8 lg:px-16 lg:py-12">
      <div className="mb-2 text-xs font-medium uppercase tracking-widest text-mute">Intelligence</div>
      <h1 className="text-3xl font-semibold tracking-tight text-ink sm:text-4xl lg:text-5xl">
        AI Copilot
      </h1>

      {loadError && (
        <div className="mt-6 border border-red-200 bg-red-50 p-4 text-sm text-red-700">{loadError}</div>
      )}

      <div className="mt-10 flex flex-col gap-6 lg:flex-row">
        <div className="flex min-w-0 flex-1 flex-col border border-line bg-white">
          <div ref={scrollRef} className="flex h-[440px] flex-col gap-3 overflow-y-auto p-6">
            {!data && !loadError && <p className="text-sm text-mute">Loading project data…</p>}
            {messages.map((m) => (
              <div
                key={m.id}
                className={`max-w-[85%] rounded-lg px-4 py-2.5 text-sm leading-relaxed ${
                  m.role === 'user'
                    ? 'ml-auto bg-ink text-white'
                    : 'mr-auto border border-accent/40 bg-accent/10 text-ink'
                }`}
              >
                {m.text}
              </div>
            ))}
            {thinking && (
              <div className="mr-auto rounded-lg border border-accent/40 bg-accent/10 px-4 py-2.5 text-sm text-mute">
                Thinking…
              </div>
            )}
          </div>

          <div className="border-t border-line p-4">
            <div className="mb-3 flex flex-wrap gap-2">
              {suggestedPrompts.map((p) => (
                <button
                  key={p}
                  onClick={() => void send(p)}
                  disabled={!data}
                  className="rounded-full border border-line px-3 py-1 text-xs text-mute hover:bg-paper disabled:opacity-50"
                >
                  {p}
                </button>
              ))}
            </div>
            <form onSubmit={handleSubmit} className="flex gap-2">
              <input
                value={input}
                onChange={(e) => setInput(e.target.value)}
                placeholder="Ask about sprints, blockers, workload..."
                className="w-full rounded-md border border-line px-3 py-2 text-sm outline-none focus:border-ink"
              />
              <button
                type="submit"
                disabled={!data}
                className="shrink-0 rounded-md bg-ink px-4 py-2 text-sm font-medium text-white hover:bg-black disabled:opacity-60"
              >
                Send
              </button>
            </form>
            <p className="mt-2 text-[11px] text-mute">
              {answerMode === 'llm'
                ? 'Answered by the configured language model using live workspace data as context.'
                : answerReason
                  ? `${answerReason} — answering from live task, sprint and workload data instead.`
                  : 'Answers are computed from live task, sprint and workload data — no external AI service.'}
            </p>
          </div>
        </div>

        <div className="flex w-full shrink-0 flex-col gap-4 lg:w-72">
          <div className="border border-line bg-white p-5">
            <h2 className="mb-3 text-xs font-semibold uppercase tracking-widest text-mute">
              Detected Risks
            </h2>
            {risks.length === 0 ? (
              <p className="text-sm text-mute">No risks detected.</p>
            ) : (
              <div className="flex flex-col gap-2">
                {risks.slice(0, 5).map((r) => (
                  <div key={r.taskId} className="text-sm">
                    <div className="font-medium text-ink">{r.title}</div>
                    <div className="text-xs text-mute">{r.reason}</div>
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
                <div className="font-medium text-ink">
                  Sprint {latestSprint.number}
                </div>
                <div className="mt-1 text-xs text-mute">{latestSprint.goal}</div>
                <div className="mt-3 h-2 w-full rounded-full bg-line">
                  <div
                    className="h-2 rounded-full bg-info"
                    style={{
                      width: `${
                        latestSprint.committedPoints > 0
                          ? Math.min(100, Math.round((latestSprint.donePoints / latestSprint.committedPoints) * 100))
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
                {teamLoad.map((m) => (
                  <div key={m.userId} className="flex justify-between text-sm">
                    <span>{m.name}</span>
                    <span className="text-mute">{m.openPoints} pts · {m.openTasks} open</span>
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
