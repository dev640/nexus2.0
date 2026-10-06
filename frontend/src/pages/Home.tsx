import { useAppStore, timeAgo } from '../store/useAppStore'
import type { Task } from '../lib/mockData'

function greetingForHour(hour: number) {
  if (hour < 5) return 'GOOD NIGHT'
  if (hour < 12) return 'GOOD MORNING'
  if (hour < 17) return 'GOOD AFTERNOON'
  if (hour < 21) return 'GOOD EVENING'
  return 'GOOD NIGHT'
}

const statusLabel: Record<Task['status'], string> = {
  BACKLOG: 'in the backlog',
  TODO: 'queued',
  IN_PROGRESS: 'in progress',
  IN_REVIEW: 'in review',
  TESTING: 'in testing',
  DONE: 'done',
}

/**
 * The same facts the Copilot grounds its answers in — sprint progress, open
 * urgent work and overall completion — computed from the loaded workspace.
 */
function buildBrief(
  activeSprint: { number: number; pct: number; remaining: number } | null,
  totalTasks: number,
  openUrgent: number,
  doneCount: number,
): string {
  if (totalTasks === 0) {
    return 'No tasks tracked yet. Create the first one from the Backlog page.'
  }
  const completion = Math.round((doneCount / totalTasks) * 100)
  const sprintPart = activeSprint
    ? `Sprint ${activeSprint.number} is ${activeSprint.pct}% complete with ${activeSprint.remaining} point${
        activeSprint.remaining === 1 ? '' : 's'
      } still to go.`
    : 'There is no active sprint, so nothing is currently committed to a delivery window.'
  const urgentPart =
    openUrgent === 0
      ? 'No urgent work is open right now.'
      : `${openUrgent} urgent task${openUrgent === 1 ? ' is' : 's are'} still open across the workspace.`
  return `${sprintPart} ${urgentPart} ${completion}% of ${totalTasks} tracked tasks are done.`
}

export function Home() {
  const tasks = useAppStore((s) => s.tasks)
  const sprints = useAppStore((s) => s.sprints)
  const members = useAppStore((s) => s.members)
  const displayName = useAppStore((s) => s.settings.displayName)
  const greeting = greetingForHour(new Date().getHours())

  const dueToday = tasks.filter((t) => t.status === 'IN_PROGRESS' || t.status === 'IN_REVIEW')
  const blocked = tasks.filter((t) => t.blocked)
  const highPriority = tasks.filter((t) => t.priority === 'URGENT' || t.priority === 'HIGH')
  const openUrgent = tasks.filter((t) => t.priority === 'URGENT' && t.status !== 'DONE')
  const doneCount = tasks.filter((t) => t.status === 'DONE').length

  const activeSprint =
    sprints.find((s) => s.status === 'ACTIVE') ?? sprints[sprints.length - 1]
  const activeSprintTasks = activeSprint
    ? tasks.filter((t) => t.sprintId === activeSprint.id)
    : []
  const sprintCompletedPoints = activeSprintTasks
    .filter((t) => t.status === 'DONE')
    .reduce((sum, t) => sum + t.storyPoints, 0)
  const sprintRemainingPoints = activeSprint
    ? Math.max(activeSprint.committedPoints - sprintCompletedPoints, 0)
    : 0
  const sprintPct =
    activeSprint && activeSprint.committedPoints > 0
      ? Math.min(100, Math.round((sprintCompletedPoints / activeSprint.committedPoints) * 100))
      : 0

  // Real recency, real state. We have no change history, so an entry states
  // where the task stands now and when it was last touched, rather than
  // inventing an event that never happened.
  const recentActivity = [...tasks]
    .sort((a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime())
    .slice(0, 6)
    .map((t) => ({
      id: t.id,
      text: `${t.id} · ${t.title} — ${statusLabel[t.status]}`,
      time: timeAgo(t.updatedAt),
    }))

  const brief = buildBrief(
    activeSprint
      ? { number: activeSprint.number, pct: sprintPct, remaining: sprintRemainingPoints }
      : null,
    tasks.length,
    openUrgent.length,
    doneCount,
  )

  // Load = open (not done) story points per member, shown relative to the
  // busiest teammate so the bars compare people rather than pretend a
  // percentage of some capacity nobody defined.
  const busiestPoints = Math.max(
    0,
    ...members.map((m) =>
      tasks
        .filter((t) => t.assigneeId === m.id && t.status !== 'DONE')
        .reduce((sum, t) => sum + t.storyPoints, 0),
    ),
  )
  const teamLoad = members.map((m) => {
    const openPoints = tasks
      .filter((t) => t.assigneeId === m.id && t.status !== 'DONE')
      .reduce((sum, t) => sum + t.storyPoints, 0)
    return {
      id: m.id,
      name: m.name,
      openPoints,
      pct: busiestPoints > 0 ? Math.min(100, Math.round((openPoints / busiestPoints) * 100)) : 0,
    }
  })

  return (
    <div className="px-4 py-6 sm:px-8 sm:py-8 lg:px-16 lg:py-12">
      <div className="mb-1 text-xs font-medium uppercase tracking-widest text-mute">
        Overview
      </div>
      <h1 className="text-3xl font-semibold leading-tight tracking-tight text-ink sm:text-4xl lg:text-5xl">
        {greeting}
        {displayName ? (
          <>
            ,<br />
            {displayName.toUpperCase()}.
          </>
        ) : (
          '.'
        )}
      </h1>
      <p className="mt-3 text-base text-mute">Here's what needs your attention.</p>

      <div className="mt-12 grid grid-cols-1 gap-6 lg:grid-cols-3 lg:gap-8">
        <section className="col-span-2 border border-line bg-white p-6">
          <h2 className="mb-4 text-xs font-semibold uppercase tracking-widest text-mute">
            Today's Focus
          </h2>
          {dueToday.length === 0 && blocked.length === 0 ? (
            <p className="py-2 text-sm text-mute">Nothing is in progress or in review right now.</p>
          ) : (
            <div className="flex flex-col gap-2">
              {dueToday.map((t) => (
                <div key={t.id} className="flex items-center justify-between border-b border-line py-2 last:border-0">
                  <div>
                    <span className="mr-2 font-mono text-xs text-mute">{t.id}</span>
                    <span className="text-sm">{t.title}</span>
                  </div>
                  <span className="text-xs text-mute">{t.status.replace('_', ' ')}</span>
                </div>
              ))}
              {blocked.map((t) => (
                <div key={t.id} className="flex items-center justify-between border-b border-line py-2 last:border-0">
                  <div>
                    <span className="mr-2 font-mono text-xs text-mute">{t.id}</span>
                    <span className="text-sm">{t.title}</span>
                  </div>
                  <span className="text-xs font-medium text-danger">Blocked</span>
                </div>
              ))}
            </div>
          )}
        </section>

        <section className="border border-line bg-ink p-6 text-white">
          <h2 className="mb-1 text-xs font-semibold uppercase tracking-widest text-white/50">
            Sprint Health
          </h2>
          {activeSprint ? (
            <>
              <div className="text-3xl font-semibold tracking-tight">SPRINT {activeSprint.number}</div>
              <div className="mt-4 h-1.5 w-full rounded-full bg-white/15">
                <div className="h-1.5 rounded-full bg-accent" style={{ width: `${sprintPct}%` }} />
              </div>
              <div className="mt-2 text-sm text-white/70">{sprintPct}% complete</div>
              <div className="mt-6 grid grid-cols-2 gap-y-2 text-sm">
                <div className="text-white/50">Committed</div>
                <div className="text-right">{activeSprint.committedPoints} pts</div>
                <div className="text-white/50">Completed</div>
                <div className="text-right">{sprintCompletedPoints} pts</div>
                <div className="text-white/50">Remaining</div>
                <div className="text-right">{sprintRemainingPoints} pts</div>
                <div className="text-white/50">Tasks</div>
                <div className="text-right">{activeSprintTasks.length}</div>
              </div>
            </>
          ) : (
            <p className="mt-4 text-sm text-white/70">No sprint yet. Create one from the Sprints page.</p>
          )}
        </section>
      </div>

      <div className="mt-8 grid grid-cols-1 gap-6 lg:grid-cols-3 lg:gap-8">
        <section className="col-span-2 border border-accent/40 bg-accent/10 p-6">
          <h2 className="mb-2 text-xs font-semibold uppercase tracking-widest text-ink/60">
            Workspace Brief
          </h2>
          <p className="text-lg leading-snug text-ink">{brief}</p>
          <a
            href="/copilot"
            className="mt-4 inline-block text-sm font-medium underline underline-offset-4"
          >
            Ask the Copilot →
          </a>
        </section>

        <section className="border border-line bg-white p-6">
          <h2 className="mb-1 text-xs font-semibold uppercase tracking-widest text-mute">
            Team Load
          </h2>
          <p className="mb-4 text-xs text-mute">
            Open story points, relative to the heaviest load.
          </p>
          {members.length === 0 ? (
            <p className="text-sm text-mute">No members loaded.</p>
          ) : (
            <div className="flex flex-col gap-3">
              {teamLoad.map((m) => (
                <div key={m.id}>
                  <div className="mb-1 flex justify-between text-sm">
                    <span>
                      {m.name}
                      <span className="ml-2 text-xs text-mute">{m.openPoints} pts open</span>
                    </span>
                    <span className="text-mute">{m.pct}%</span>
                  </div>
                  <div className="h-1.5 w-full rounded-full bg-line">
                    <div
                      className={`h-1.5 rounded-full ${m.pct > 85 ? 'bg-danger' : 'bg-ink'}`}
                      style={{ width: `${m.pct}%` }}
                    />
                  </div>
                </div>
              ))}
            </div>
          )}
        </section>
      </div>

      <div className="mt-8 grid grid-cols-1 gap-6 lg:grid-cols-3 lg:gap-8">
        <section className="col-span-2 border border-line bg-white p-6">
          <h2 className="mb-4 text-xs font-semibold uppercase tracking-widest text-mute">
            Recent Activity
          </h2>
          {recentActivity.length === 0 ? (
            <p className="text-sm text-mute">No task activity yet.</p>
          ) : (
            <div className="flex flex-col gap-2">
              {recentActivity.map((a) => (
                <div key={a.id} className="flex justify-between gap-3 border-b border-line py-2 text-sm last:border-0">
                  <span className="min-w-0 truncate">{a.text}</span>
                  <span className="shrink-0 text-mute">{a.time}</span>
                </div>
              ))}
            </div>
          )}
        </section>

        <section className="border border-line bg-white p-6">
          <h2 className="mb-4 text-xs font-semibold uppercase tracking-widest text-mute">
            High Priority
          </h2>
          {highPriority.length === 0 ? (
            <p className="text-sm text-mute">No high-priority work open.</p>
          ) : (
            <div className="flex flex-col gap-2">
              {highPriority.map((t) => (
                <div key={t.id} className="flex items-center justify-between border-b border-line py-2 text-sm">
                  <span className="min-w-0 truncate">{t.title}</span>
                  <span className="shrink-0 text-xs font-medium text-danger">{t.priority}</span>
                </div>
              ))}
            </div>
          )}
        </section>
      </div>
    </div>
  )
}