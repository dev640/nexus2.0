import { useState } from 'react'
import { memberById, type TaskStatus } from '../lib/mockData'
import { useDeepLink } from '../hooks/useDeepLink'
import { toTaskId } from '../store/ids'
import { useAppStore } from '../store/useAppStore'
import { NewTaskModal } from '../components/task/NewTaskModal'
import { EditTaskModal } from '../components/task/EditTaskModal'
import { LabelChips } from '../components/task/LabelChips'
import { UserAvatar } from '../components/user/UserAvatar'

const columns: { key: TaskStatus; label: string }[] = [
  { key: 'BACKLOG', label: 'Backlog' },
  { key: 'TODO', label: 'To Do' },
  { key: 'IN_PROGRESS', label: 'In Progress' },
  { key: 'IN_REVIEW', label: 'In Review' },
  { key: 'TESTING', label: 'Testing' },
  { key: 'DONE', label: 'Done' },
]

const priorityColor: Record<string, string> = {
  URGENT: 'text-danger',
  HIGH: 'text-warning',
  MEDIUM: 'text-info',
  LOW: 'text-mute',
}

const statusLabel: Record<string, string> = {
  BACKLOG: 'Backlog',
  TODO: 'To Do',
  IN_PROGRESS: 'In Progress',
  IN_REVIEW: 'In Review',
  TESTING: 'Testing',
  DONE: 'Done',
}

export function Board() {
  const tasks = useAppStore((s) => s.tasks)
  const members = useAppStore((s) => s.members)
  const updateTaskStatus = useAppStore((s) => s.updateTaskStatus)
  const canWrite = useAppStore((s) => s.currentUser?.role !== 'VIEWER')
  const [modalOpen, setModalOpen] = useState(false)
  const [modalStatus, setModalStatus] = useState<TaskStatus>('BACKLOG')
  const [editingId, setEditingId] = useState<string | null>(null)
  const loadWorkspace = useAppStore((s) => s.loadWorkspace)

  // Clicking an alert about a task lands here with ?task=<id>, and the card it
  // refers to opens ready to edit. A miss refreshes the workspace once, since
  // the alert can describe a task created after this viewer's last load.
  useDeepLink(
    ['task'],
    (query) => {
      const target = tasks.find((t) => t.id === toTaskId(Number(query.get('task'))))
      if (!target) return 'gone'
      setEditingId(target.id)
      return 'open'
    },
    { refresh: loadWorkspace },
  )

  function openNewTask(status: TaskStatus) {
    setModalStatus(status)
    setModalOpen(true)
  }

  return (
    <div className="flex h-full flex-col py-6 lg:py-8">
      <div className="mb-1 flex items-center justify-between px-4 sm:px-6 lg:px-8">
        <div className="text-xs font-medium uppercase tracking-widest text-mute">
          Delivery
        </div>          {canWrite ? (
            <button
              onClick={() => openNewTask('BACKLOG')}
              className="rounded-md bg-ink px-4 py-2 text-sm font-medium text-white hover:bg-black"
            >
              + New Task
            </button>
          ) : (
            <span className="rounded-md bg-mute px-4 py-2 text-sm font-medium text-mute">+ New Task</span>
          )}
      </div>
      <h1 className="px-4 text-3xl font-semibold tracking-tight text-ink sm:px-6 lg:px-8">Board</h1>

      <div className="mt-6 flex flex-1 gap-4 overflow-x-auto px-4 pb-4 sm:px-6 lg:px-8">
        {columns.map((col) => {
          const colTasks = tasks.filter((t) => t.status === col.key)
          return (
    <div key={col.key} className="flex w-64 shrink-0 flex-col">
      <div className="mb-2 flex items-center justify-between px-1">
        <span className="text-xs font-semibold uppercase tracking-wide text-mute">
          {col.label}
        </span>
        <span className="text-xs text-mute">{colTasks.length}</span>
      </div>
      <div className="flex flex-col gap-2">
        {colTasks.map((t) => {
          const assignee = memberById(members, t.assigneeId)
          return (
            <div
              key={t.id}
              className="rounded-md border border-line bg-white p-3 text-sm"
            >
              <div className="mb-1 flex items-center justify-between">
                <span className="font-mono text-xs text-mute">{t.id}</span>
                <div className="flex items-center gap-1.5">
                  {t.blocked && (
                    <span className="text-xs font-medium text-danger">Blocked</span>
                  )}
                  {t.aiGenerated && (
                    <span className="rounded bg-accent px-1.5 py-0.5 text-[10px] font-semibold text-ink">
                      AI
                    </span>
                  )}
                </div>
              </div>
              {canWrite ? (
                <button
                  onClick={() => setEditingId(t.id)}
                  className="mb-2 block w-full text-left leading-snug hover:underline"
                >
                  {t.title}
                  {t.description && (
                    <span className="mt-1 block text-xs font-normal leading-snug text-mute">
                      {t.description}
                    </span>
                  )}
                </button>
              ) : (
                <div className="mb-2 block w-full text-left leading-snug">
                  {t.title}
                  {t.description && (
                    <span className="mt-1 block text-xs font-normal leading-snug text-mute">
                      {t.description}
                    </span>
                  )}
                </div>
              )}

              <div className="mb-2 flex items-center justify-between">
                <span className={`text-xs font-medium ${priorityColor[t.priority]}`}>
                  {t.priority}
                </span>
                <div className="flex items-center gap-2">
                  <span className="text-xs text-mute">{t.storyPoints} pt</span>
                  {assignee && (
                    <UserAvatar
                      memberId={assignee.id}
                      name={assignee.name}
                      initials={assignee.initials}
                      size="xs"
                    />
                  )}
                </div>
              </div>
              {canWrite ? (
                <select
                  value={t.status}
                  onChange={(e) => updateTaskStatus(t.id, e.target.value as TaskStatus)}
                  className="w-full rounded border border-line bg-paper px-1.5 py-1 text-xs text-mute outline-none"
                >
                  {columns.map((c) => (
                    <option key={c.key} value={c.key}>
                      {c.label}
                    </option>
                  ))}
                </select>
              ) : (
                <span className="w-full rounded border border-line bg-paper px-1.5 py-1 text-xs text-mute">
                  {statusLabel[t.status]}
                </span>
              )}
                      <LabelChips labels={t.labels} max={2} className="mb-2" />
                      <div className="mb-2 flex items-center justify-between">
                        <span className={`text-xs font-medium ${priorityColor[t.priority]}`}>
                          {t.priority}
                        </span>
                        <div className="flex items-center gap-2">
                          <span className="text-xs text-mute">{t.storyPoints} pt</span>
                          {assignee && (
                            <UserAvatar
                              memberId={assignee.id}
                              name={assignee.name}
                              initials={assignee.initials}
                              size="xs"
                            />
                          )}
                        </div>
                      </div>
                      <select
                        value={t.status}
                        onChange={(e) => updateTaskStatus(t.id, e.target.value as TaskStatus)}
                        className="w-full rounded border border-line bg-paper px-1.5 py-1 text-xs text-mute outline-none"
                      >
                        {columns.map((c) => (
                          <option key={c.key} value={c.key}>
                            {c.label}
                          </option>
                        ))}
                      </select>
                    </div>
                  )
                })}
                <button
                  onClick={() => openNewTask(col.key)}
                  className="rounded-md border border-dashed border-line py-2 text-xs text-mute hover:border-ink hover:text-ink"
                >
                  + Add task
                </button>
              </div>
            </div>
          )
        })}
      </div>

      <NewTaskModal open={modalOpen} onClose={() => setModalOpen(false)} defaultStatus={modalStatus} />
      <EditTaskModal taskId={editingId} onClose={() => setEditingId(null)} />
    </div>
  )
}
