import { useState } from 'react'
import { memberById } from '../lib/mockData'
import { useAppStore } from '../store/useAppStore'
import { NewTaskModal } from '../components/task/NewTaskModal'
import { EditTaskModal } from '../components/task/EditTaskModal'
import { LabelChips } from '../components/task/LabelChips'
import { UserAvatar } from '../components/user/UserAvatar'

const priorityColor: Record<string, string> = {
  URGENT: 'text-danger',
  HIGH: 'text-warning',
  MEDIUM: 'text-info',
  LOW: 'text-mute',
}

export function Backlog() {
  const tasks = useAppStore((s) => s.tasks)
  const members = useAppStore((s) => s.members)
  const canWrite = useAppStore((s) => s.currentUser?.role !== 'VIEWER')
  const [modalOpen, setModalOpen] = useState(false)
  const [editingId, setEditingId] = useState<string | null>(null)

  return (
    <div className="px-4 py-6 sm:px-8 sm:py-8 lg:px-16 lg:py-12">
      <div className="mb-1 flex items-center justify-between">
        <div className="text-xs font-medium uppercase tracking-widest text-mute">
          Delivery
        </div>
        {canWrite ? (
          <button
            onClick={() => setModalOpen(true)}
            className="rounded-md bg-ink px-4 py-2 text-sm font-medium text-white hover:bg-black"
          >
            + New Task
          </button>
        ) : (
          <span className="rounded-md bg-mute px-4 py-2 text-sm font-medium text-mute">+ New Task</span>
        )}
      </div>
      <h1 className="text-3xl font-semibold tracking-tight text-ink sm:text-4xl lg:text-5xl">Backlog</h1>

      <div className="mt-10 overflow-x-auto border border-line bg-white">
        <div className="min-w-[720px] grid grid-cols-[100px_1fr_120px_100px_100px_140px_80px] border-b border-line bg-paper px-4 py-2 text-xs font-semibold uppercase tracking-wide text-mute">
          <span>ID</span>
          <span>Title</span>
          <span>Status</span>
          <span>Priority</span>
          <span>Points</span>
          <span>Assignee</span>
          <span className="text-right">Actions</span>
        </div>
        {tasks.map((t) => {
          const assignee = memberById(members, t.assigneeId)
          return (
            <div
              key={t.id}
              className="min-w-[720px] grid grid-cols-[100px_1fr_120px_100px_100px_140px_80px] items-center border-b border-line px-4 py-3 text-sm last:border-0 hover:bg-paper/60"
            >
              <span className="font-mono text-xs text-mute">{t.id}</span>
              {canWrite ? (
                <button
                  onClick={() => setEditingId(t.id)}
                  className="flex min-w-0 flex-col items-start gap-0.5 text-left hover:underline"
                >
                  <span className="flex items-center gap-2">
                    {t.title}
                    {t.aiGenerated && (
                      <span className="rounded bg-accent px-1.5 py-0.5 text-[10px] font-semibold text-ink">
                        AI
                      </span>
                    )}
                  </span>
                  <LabelChips labels={t.labels} max={2} />
                </button>
              ) : (
                <div className="flex min-w-0 flex-col items-start gap-0.5 text-left">
                  <span className="flex items-center gap-2">
                    {t.title}
                    {t.aiGenerated && (
                      <span className="rounded bg-accent px-1.5 py-0.5 text-[10px] font-semibold text-ink">
                        AI
                      </span>
                    )}
                  </span>
                  <LabelChips labels={t.labels} max={2} />
                </div>
              )}

              {t.description && (
                <span className="truncate text-xs font-normal text-mute">{t.description}</span>
              )}

              <span>
                <LabelChips labels={t.labels} max={2} />
              </span>
              <span className="text-xs text-mute">
                {t.status.replace('_', ' ')}
                {t.blocked && (
                  <span className="ml-1.5 font-medium uppercase tracking-wide text-danger">
                    blocked
                  </span>
                )}
              </span>
              <span className={`text-xs font-medium ${priorityColor[t.priority]}`}>
                {t.priority}
              </span>
              <span className="text-xs text-mute">{t.storyPoints}</span>
              <span className="flex items-center gap-2 text-xs text-mute">
                {assignee && (
                  <UserAvatar
                    memberId={assignee.id}
                    name={assignee.name}
                    initials={assignee.initials}
                    size="xs"
                  />
                )}
                {assignee?.name}
              </span>
              <span className="flex justify-end">
                <button
                  onClick={() => setEditingId(t.id)}
                  className="rounded-md border border-line px-2.5 py-1 text-xs font-medium hover:bg-white"
                >
                  Edit
                </button>
              </span>
            </div>
          )
        })}
      </div>

      <NewTaskModal open={modalOpen} onClose={() => setModalOpen(false)} />
      <EditTaskModal taskId={editingId} onClose={() => setEditingId(null)} />
    </div>
  )
}
