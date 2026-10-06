import { useState } from 'react'
import { useAppStore, toUserId } from '../store/useAppStore'
import type { Task } from '../lib/mockData'

const priorityColor: Record<string, string> = {
  URGENT: 'text-danger',
  HIGH: 'text-warning',
  MEDIUM: 'text-info',
  LOW: 'text-mute',
}

/**
 * There is no date on a task, so "today"/"upcoming" could never be computed.
 * These buckets are the honest partition of what is actually on the record:
 * what is in flight, what is queued, what is unscheduled, what is finished.
 */
const buckets: { key: string; title: string; empty: string; match: (status: Task['status']) => boolean }[] = [
  {
    key: 'wip',
    title: 'In Progress',
    empty: 'Nothing in flight right now.',
    match: (status) => status === 'IN_PROGRESS' || status === 'IN_REVIEW' || status === 'TESTING',
  },
  {
    key: 'todo',
    title: 'To Do',
    empty: 'Nothing queued and ready to start.',
    match: (status) => status === 'TODO',
  },
  {
    key: 'backlog',
    title: 'Backlog',
    empty: 'No unscheduled work assigned to you.',
    match: (status) => status === 'BACKLOG',
  },
  {
    key: 'done',
    title: 'Completed',
    empty: 'Nothing completed yet.',
    match: (status) => status === 'DONE',
  },
]

function TaskRow({
  id,
  title,
  status,
  priority,
  projectName,
}: {
  id: string
  title: string
  status: string
  priority: string
  projectName: string
}) {
  return (
    <div className="flex items-center justify-between gap-3 border-b border-line py-3 text-sm last:border-0">
      <div className="flex min-w-0 items-baseline gap-2">
        <span className="shrink-0 font-mono text-xs text-mute">{id}</span>
        <span className="truncate">{title}</span>
      </div>
      <div className="flex shrink-0 items-center gap-3">
        <span className="hidden text-xs text-mute sm:inline">{projectName}</span>
        <span className={`text-xs font-medium ${priorityColor[priority]}`}>{priority}</span>
        <span className="text-xs text-mute">{status.replace('_', ' ')}</span>
      </div>
    </div>
  )
}

export function MyWork() {
  const allTasks = useAppStore((s) => s.tasks)
  const projects = useAppStore((s) => s.projects)
  const currentUser = useAppStore((s) => s.currentUser)
  const displayName = useAppStore((s) => s.settings.displayName)
  const [briefDismissed, setBriefDismissed] = useState(false)

  // The old page filtered on a literal 'devendra' against assignee ids that the
  // store renders as `u-<id>`, so it never matched and was always empty.
  const myId = currentUser ? toUserId(currentUser.id) : ''
  const myTasks = allTasks.filter((t) => t.assigneeId === myId)
  const projectName = (id: string) => projects.find((p) => p.id === id)?.name ?? 'No project'

  const openCount = myTasks.filter((t) => t.status !== 'DONE').length
  const urgentCount = myTasks.filter((t) => t.priority === 'URGENT' && t.status !== 'DONE').length

  return (
    <div className="px-4 py-6 sm:px-8 sm:py-8 lg:px-16 lg:py-12">
      <div className="mb-1 text-xs font-medium uppercase tracking-widest text-mute">Personal</div>
      <h1 className="text-3xl font-semibold tracking-tight text-ink sm:text-4xl lg:text-5xl">My Work</h1>

      {!currentUser ? (
        <p className="mt-8 text-sm text-mute">Sign in to see the work assigned to you.</p>
      ) : (
        <>
          {!briefDismissed && openCount > 0 && (
            <div className="mt-8 border border-accent/40 bg-accent/10 p-6">
              <h2 className="mb-2 text-xs font-semibold uppercase tracking-widest text-ink/60">
                Your Load
              </h2>
              <p className="text-base leading-snug text-ink">
                You have {openCount} open task{openCount === 1 ? '' : 's'}
                {urgentCount > 0
                  ? `, ${urgentCount} of them marked urgent.`
                  : ', none of them marked urgent.'}
              </p>
              <div className="mt-4">
                <button
                  onClick={() => setBriefDismissed(true)}
                  className="rounded-md border border-line px-4 py-2 text-sm font-medium hover:bg-white"
                >
                  Dismiss
                </button>
              </div>
            </div>
          )}

          <div className="mt-8 grid grid-cols-1 gap-6 sm:grid-cols-2 sm:gap-8">
            {buckets.map((bucket) => {
              const rows = myTasks.filter((t) => bucket.match(t.status))
              return (
                <section key={bucket.key} className="border border-line bg-white p-6">
                  <div className="mb-2 flex items-center justify-between">
                    <h2 className="text-xs font-semibold uppercase tracking-widest text-mute">
                      {bucket.title}
                    </h2>
                    <span className="text-xs text-mute">{rows.length}</span>
                  </div>
                  {rows.length ? (
                    rows.map((t) => (
                      <TaskRow
                        key={t.id}
                        id={t.id}
                        title={t.title}
                        status={t.status}
                        priority={t.priority}
                        projectName={projectName(t.projectId)}
                      />
                    ))
                  ) : (
                    <p className="py-3 text-sm text-mute">{bucket.empty}</p>
                  )}
                </section>
              )
            })}
          </div>

          {myTasks.length === 0 && (
            <p className="mt-6 text-sm text-mute">
              Nothing is assigned to {displayName || currentUser.name} yet.
            </p>
          )}
        </>
      )}
    </div>
  )
}