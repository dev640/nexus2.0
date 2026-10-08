import { Link } from 'react-router-dom'
import { timeAgo, toUserId, useAppStore } from '../store/useAppStore'
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

/** Work that occupies someone today: started, in review, or being tested. */
function isInFlight(task: Task): boolean {
  return task.status === 'IN_PROGRESS' || task.status === 'IN_REVIEW' || task.status === 'TESTING'
}

function plural(count: number, one: string, many = `${one}s`): string {
  return `${count} ${count === 1 ? one : many}`
}

/**
 * The workspace as a whole — the same facts the Copilot grounds its answers in,
 * and the brief the manager and admin overviews are read for.
 */
function buildWorkspaceBrief(
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
    ? `Sprint ${activeSprint.number} is ${activeSprint.pct}% complete with ${plural(
        activeSprint.remaining,
        'point',
      )} still to go.`
    : 'There is no active sprint, so nothing is currently committed to a delivery window.'
  const urgentPart =
    openUrgent === 0
      ? 'No urgent work is open right now.'
      : `${plural(openUrgent, 'urgent task')} still open across the workspace.`
  return `${sprintPart} ${urgentPart} ${completion}% of ${totalTasks} tracked tasks are done.`
}

/**
 * What the person reading the page is carrying. The overview belongs to them,
 * so this is the headline for every role: the workspace totals sit underneath
 * it, and only for the roles that act on the whole workspace.
 */
function buildPersonalBrief(
  name: string,
  own: {
    open: number
    openPoints: number
    inReview: number
    blocked: number
    urgent: number
    done: number
  },
): string {
  if (own.open === 0) {
    return own.done > 0
      ? `${name}, nothing is open on your plate — all ${plural(own.done, 'task')} assigned to you are done.`
      : `${name}, nothing is assigned to you yet.`
  }
  const parts = [`${plural(own.open, 'open task')} worth ${plural(own.openPoints, 'point')}`]
  if (own.inReview > 0) parts.push(`${plural(own.inReview, 'is', 'are')} in review`)
  if (own.blocked > 0) parts.push(`${plural(own.blocked, 'is', 'are')} blocked`)
  if (own.urgent > 0) parts.push(`${plural(own.urgent, 'is', 'are')} marked urgent`)
  return `${name}, you are carrying ${parts.join(', ')}.`
}

/** One row of the focus list: the task, its project and where it stands. */
function FocusRow({
  task,
  projectName,
}: {
  task: Task
  projectName: string
}) {
  const blocked = task.blocked && task.status !== 'DONE'
  return (
    <div className="flex items-center justify-between gap-3 border-b border-line py-2 last:border-0">
      <div className="flex min-w-0 items-baseline gap-2">
        <span className="shrink-0 font-mono text-xs text-mute">{task.id}</span>
        <span className="truncate text-sm">{task.title}</span>
      </div>
      <div className="flex shrink-0 items-center gap-3">
        <span className="hidden text-xs text-mute sm:inline">{projectName}</span>
        <span className={blocked ? 'text-xs font-medium text-danger' : 'text-xs text-mute'}>
          {blocked ? 'Blocked' : task.status.replace('_', ' ')}
        </span>
      </div>
    </div>
  )
}

export function Home() {
  const tasks = useAppStore((s) => s.tasks)
  const sprints = useAppStore((s) => s.sprints)
  const projects = useAppStore((s) => s.projects)
  const members = useAppStore((s) => s.members)
  const currentUser = useAppStore((s) => s.currentUser)
  const userRole = useAppStore((s) => s.userRole)
  const displayName = useAppStore((s) => s.settings.displayName)
  const inboxMessages = useAppStore((s) => s.inboxMessages)
  const notifications = useAppStore((s) => s.notifications)
  const greeting = greetingForHour(new Date().getHours())

  // Everyone gets their own overview. The panels split in two: what is *yours*
  // (shown for every role) and what belongs to the whole workspace (the roles
  // that act on it — ADMIN and MANAGER).
  const isAdmin = userRole === 'ADMIN'
  const isManager = isAdmin || userRole === 'MANAGER'
  const canWrite = userRole !== 'VIEWER'
  const me = currentUser ? toUserId(currentUser.id) : ''
  const myTasks = currentUser ? tasks.filter((t) => t.assigneeId === me) : []

  const myOpen = myTasks.filter((t) => t.status !== 'DONE')
  const myOpenPoints = myOpen.reduce((sum, t) => sum + t.storyPoints, 0)
  const myFocus = myTasks.filter(isInFlight)
  const myBlocked = myTasks.filter((t) => t.blocked && t.status !== 'DONE')
  const myUrgent = myTasks.filter((t) => t.priority === 'URGENT' && t.status !== 'DONE')
  const myDone = myTasks.filter((t) => t.status === 'DONE')
  const myHighPriority = myTasks.filter(
    (t) => (t.priority === 'URGENT' || t.priority === 'HIGH') && t.status !== 'DONE',
  )

  const unreadMail = inboxMessages.filter((m) => !m.read).length
  const unreadNotifications = notifications.filter((n) => !n.read).length

  const activeSprint = sprints.find((s) => s.status === 'ACTIVE') ?? sprints[sprints.length - 1]
  const activeSprintTasks = activeSprint ? tasks.filter((t) => t.sprintId === activeSprint.id) : []
  const mySprintTasks = activeSprint ? myTasks.filter((t) => t.sprintId === activeSprint.id) : []
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

  const openUrgent = tasks.filter((t) => t.priority === 'URGENT' && t.status !== 'DONE')
  const doneCount = tasks.filter((t) => t.status === 'DONE').length
  const workspaceBrief = buildWorkspaceBrief(
    activeSprint
      ? { number: activeSprint.number, pct: sprintPct, remaining: sprintRemainingPoints }
      : null,
    tasks.length,
    openUrgent.length,
    doneCount,
  )

  const name = displayName || currentUser?.name || ''
  const personalBrief = buildPersonalBrief(name || 'You', {
    open: myOpen.length,
    openPoints: myOpenPoints,
    inReview: myTasks.filter((t) => t.status === 'IN_REVIEW').length,
    blocked: myBlocked.length,
    urgent: myUrgent.length,
    done: myDone.length,
  })

  // Managers read the whole workspace's priority list; everyone else reads
  // their own.
  const priorityRows = isManager
    ? openUrgent.concat(tasks.filter((t) => t.priority === 'HIGH' && t.status !== 'DONE'))
    : myHighPriority

  // Real recency, real state. We have no change history, so an entry states
  // where the task stands now and when it was last touched, rather than
  // inventing an event that never happened.
  const activityRows = (source: Task[]) =>
    [...source]
      .sort((a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime())
      .slice(0, 6)
      .map((t) => ({
        id: t.id,
        text: `${t.id} · ${t.title} — ${statusLabel[t.status]}`,
        time: timeAgo(t.updatedAt),
      }))
  const myActivity = activityRows(myTasks)
  const workspaceActivity = activityRows(tasks)

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

  const projectName = (id: string) => projects.find((p) => p.id === id)?.name ?? 'No project'
  const focusRows = [...myFocus, ...myBlocked.filter((t) => !isInFlight(t))]

  return (
    <div className="px-4 py-6 sm:px-8 sm:py-8 lg:px-16 lg:py-12">
      <div className="mb-1 flex flex-wrap items-center gap-2 text-xs font-medium uppercase tracking-widest text-mute">
        <span>Overview</span>
        <span className="rounded-full border border-line px-2 py-0.5 text-[10px] font-semibold text-ink/70">
          {userRole}
        </span>
      </div>
      <h1 className="text-3xl font-semibold leading-tight tracking-tight text-ink sm:text-4xl lg:text-5xl">
        {greeting}
        {name ? (
          <>
            ,<br />
            {name.toUpperCase()}.
          </>
        ) : (
          '.'
        )}
      </h1>
      <p className="mt-3 text-base text-mute">
        {isManager
          ? 'Here is where the workspace stands and what needs your attention.'
          : 'Here is what needs your attention.'}
      </p>

      {/* A VIEWER can read every page and is refused every write, so the page
          says so up front instead of showing controls that would 403. */}
      {!canWrite && (
        <div className="mt-6 border border-line bg-white p-4 text-sm text-mute">
          <span className="font-medium text-ink">Read-only access.</span> You can browse the whole
          workspace, but creating and editing is disabled for the VIEWER role.
        </div>
      )}

      <div className="mt-12 grid grid-cols-1 gap-6 lg:grid-cols-3 lg:gap-8">
        <section className="col-span-2 border border-line bg-white p-6">
          <div className="mb-4 flex flex-wrap items-baseline justify-between gap-2">
            <h2 className="text-xs font-semibold uppercase tracking-widest text-mute">
              Your Focus
            </h2>
            {currentUser && (
              <span className="text-xs text-mute">
                {plural(myOpen.length, 'open task')} assigned to you
              </span>
            )}
          </div>
          {!currentUser ? (
            <p className="py-2 text-sm text-mute">Sign in to see the work assigned to you.</p>
          ) : focusRows.length === 0 ? (
            <p className="py-2 text-sm text-mute">
              Nothing assigned to you is in progress, in review or blocked right now.
            </p>
          ) : (
            <div className="flex flex-col gap-2">
              {focusRows.map((t) => (
                <FocusRow key={t.id} task={t} projectName={projectName(t.projectId)} />
              ))}
            </div>
          )}
          <Link
            to="/my-work"
            className="mt-4 inline-block text-sm font-medium underline underline-offset-4"
          >
            See all your work →
          </Link>
        </section>

        <section className="border border-line bg-ink p-6 text-white">
          <h2 className="mb-1 text-xs font-semibold uppercase tracking-widest text-white/50">
            Sprint Health
          </h2>
          {activeSprint ? (
            <>
              <div className="text-3xl font-semibold tracking-tight">
                SPRINT {activeSprint.number}
              </div>
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
              {currentUser && (
                <div className="mt-4 border-t border-white/15 pt-3 text-sm">
                  <span className="text-white/50">Yours in this sprint</span>{' '}
                  {plural(mySprintTasks.length, 'task')}
                </div>
              )}
            </>
          ) : (
            <p className="mt-4 text-sm text-white/70">
              {isManager
                ? 'No sprint yet. Create one from the Sprints page.'
                : 'No sprint is running yet.'}
            </p>
          )}
        </section>
      </div>

      <div className="mt-8 grid grid-cols-1 gap-6 lg:grid-cols-3 lg:gap-8">
        <section className="col-span-2 border border-accent/40 bg-accent/10 p-6">
          <h2 className="mb-2 text-xs font-semibold uppercase tracking-widest text-ink/60">
            Your Brief
          </h2>
          <p className="text-lg leading-snug text-ink">{personalBrief}</p>
          {isManager && (
            <p className="mt-3 border-t border-ink/10 pt-3 text-sm text-ink/80">
              <span className="font-medium">Workspace:</span> {workspaceBrief}
            </p>
          )}
          <Link
            to="/copilot"
            className="mt-4 inline-block text-sm font-medium underline underline-offset-4"
          >
            Ask the Copilot →
          </Link>
        </section>

        <section className="border border-line bg-white p-6">
          <h2 className="mb-4 text-xs font-semibold uppercase tracking-widest text-mute">
            Your Inbox
          </h2>
          <div className="flex flex-col gap-3 text-sm">
            <div className="flex items-baseline justify-between gap-3">
              <span className="text-mute">Unread mail</span>
              <span className={unreadMail > 0 ? 'font-semibold text-ink' : 'text-mute'}>
                {unreadMail}
              </span>
            </div>
            <div className="flex items-baseline justify-between gap-3">
              <span className="text-mute">Unread notifications</span>
              <span className={unreadNotifications > 0 ? 'font-semibold text-ink' : 'text-mute'}>
                {unreadNotifications}
              </span>
            </div>
          </div>
          <Link
            to="/inbox"
            className="mt-4 inline-block text-sm font-medium underline underline-offset-4"
          >
            Open the Inbox →
          </Link>
        </section>
      </div>

      <div className="mt-8 grid grid-cols-1 gap-6 lg:grid-cols-3 lg:gap-8">
        {/* Team load and every task's priority are what a manager opens the
            overview for; for everyone else the same space answers "what have I
            been touching" and "what of mine is urgent". */}
        {isManager ? (
          <section className="col-span-2 border border-line bg-white p-6">
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
        ) : (
          <section className="col-span-2 border border-line bg-white p-6">
            <h2 className="mb-4 text-xs font-semibold uppercase tracking-widest text-mute">
              Your Recent Activity
            </h2>
            {myActivity.length === 0 ? (
              <p className="text-sm text-mute">Nothing of yours has moved yet.</p>
            ) : (
              <div className="flex flex-col gap-2">
                {myActivity.map((a) => (
                  <div
                    key={a.id}
                    className="flex justify-between gap-3 border-b border-line py-2 text-sm last:border-0"
                  >
                    <span className="min-w-0 truncate">{a.text}</span>
                    <span className="shrink-0 text-mute">{a.time}</span>
                  </div>
                ))}
              </div>
            )}
          </section>
        )}

        <section className="border border-line bg-white p-6">
          <h2 className="mb-4 text-xs font-semibold uppercase tracking-widest text-mute">
            {isManager ? 'Workspace Priority' : 'Your Priority'}
          </h2>
          {priorityRows.length === 0 ? (
            <p className="text-sm text-mute">
              {isManager
                ? 'No high-priority work open.'
                : 'None of your open work is urgent or high priority.'}
            </p>
          ) : (
            <div className="flex flex-col gap-2">
              {priorityRows.slice(0, 6).map((t) => (
                <div
                  key={t.id}
                  className="flex items-center justify-between border-b border-line py-2 text-sm last:border-0"
                >
                  <span className="min-w-0 truncate">{t.title}</span>
                  <span className="shrink-0 text-xs font-medium text-danger">{t.priority}</span>
                </div>
              ))}
            </div>
          )}
        </section>
      </div>

      {isManager && (
        <div className="mt-8 grid grid-cols-1 gap-6 lg:grid-cols-3 lg:gap-8">
          <section className="col-span-2 border border-line bg-white p-6">
            <h2 className="mb-4 text-xs font-semibold uppercase tracking-widest text-mute">
              Workspace Activity
            </h2>
            {workspaceActivity.length === 0 ? (
              <p className="text-sm text-mute">No task activity yet.</p>
            ) : (
              <div className="flex flex-col gap-2">
                {workspaceActivity.map((a) => (
                  <div
                    key={a.id}
                    className="flex justify-between gap-3 border-b border-line py-2 text-sm last:border-0"
                  >
                    <span className="min-w-0 truncate">{a.text}</span>
                    <span className="shrink-0 text-mute">{a.time}</span>
                  </div>
                ))}
              </div>
            )}
          </section>

          <section className="border border-line bg-white p-6">
            <h2 className="mb-1 text-xs font-semibold uppercase tracking-widest text-mute">
              {isAdmin ? 'Admin' : 'Team'}
            </h2>
            <p className="mb-4 text-sm text-mute">
              {isAdmin
                ? 'Accounts, roles and password requests live here.'
                : 'Roster, workload and delivery trends for the whole team.'}
            </p>
            <div className="flex flex-col gap-2 text-sm">
              <Link to="/team" className="underline underline-offset-4">
                Team roster →
              </Link>
              <Link to="/analytics" className="underline underline-offset-4">
                Analytics →
              </Link>
              {isAdmin && (
                <Link to="/admin" className="underline underline-offset-4">
                  Admin console →
                </Link>
              )}
            </div>
          </section>
        </div>
      )}

      {!isManager && (
        <p className="mt-8 text-xs text-mute">
          Workspace-wide panels — team load, the whole sprint and every high-priority task — are on
          the manager and admin overviews.
        </p>
      )}
    </div>
  )
}
