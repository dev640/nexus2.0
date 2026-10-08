import { useEffect, useState } from 'react'
import { Modal } from '../ui/Modal'
import { useAppStore } from '../../store/useAppStore'
import type { TaskPriority, TaskStatus } from '../../lib/mockData'
import { canManageWorkspace } from '../../lib/roles'

const statuses: TaskStatus[] = ['BACKLOG', 'TODO', 'IN_PROGRESS', 'IN_REVIEW', 'TESTING', 'DONE']
const priorities: TaskPriority[] = ['LOW', 'MEDIUM', 'HIGH', 'URGENT']

export function NewTaskModal({
  open,
  onClose,
  defaultStatus = 'BACKLOG',
  defaultProjectId,
}: {
  open: boolean
  onClose: () => void
  defaultStatus?: TaskStatus
  defaultProjectId?: string
}) {
  const projects = useAppStore((s) => s.projects)
  const sprints = useAppStore((s) => s.sprints)
  const members = useAppStore((s) => s.members)
  const addTask = useAppStore((s) => s.addTask)
  const defaultAssigneeId = useAppStore((s) => s.settings.defaultAssigneeId)
  // Assigning work is admin-or-manager only, so everyone else gets an empty
  // (disabled) assignee field and the task is created unassigned.
  const canAssign = useAppStore((s) => canManageWorkspace(s.currentUser?.role))

  const [title, setTitle] = useState('')
  const [projectId, setProjectId] = useState(defaultProjectId ?? projects[0]?.id ?? '')
  const [sprintId, setSprintId] = useState('')
  const [status, setStatus] = useState<TaskStatus>(defaultStatus)
  const [priority, setPriority] = useState<TaskPriority>('MEDIUM')
  const [storyPoints, setStoryPoints] = useState(3)
  const [assigneeId, setAssigneeId] = useState(canAssign ? defaultAssigneeId : '')
  const [error, setError] = useState('')

  const projectSprints = sprints.filter((s) => s.projectId === projectId)

  useEffect(() => {
    if (open && defaultProjectId) {
      setProjectId(defaultProjectId)
    }
  }, [open, defaultProjectId])

  useEffect(() => {
    if (open) {
      setAssigneeId(canAssign ? defaultAssigneeId : '')
    }
  }, [open, defaultAssigneeId, canAssign])

  useEffect(() => {
    if (!projectSprints.some((s) => s.id === sprintId)) {
      setSprintId('')
    }
  }, [projectId, projectSprints, sprintId])

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!title.trim()) {
      // Returning silently here made an empty title look like a dead button.
      setError('Title is required')
      return
    }
    if (!projectId) {
      setError('Pick a project')
      return
    }
    setError('')
    const created = await addTask({
      title: title.trim(),
      projectId,
      sprintId: sprintId || undefined,
      status,
      priority,
      storyPoints,
      assigneeId,
    })
    if (!created) {
      setError('Could not create the task. Please try again.')
      return
    }
    setTitle('')
    setStoryPoints(3)
    setSprintId('')
    onClose()
  }

  return (
    <Modal open={open} onClose={onClose} title="New Task">
      <form onSubmit={handleSubmit} className="flex flex-col gap-4">
        <div>
          <label className="mb-1 block text-xs font-medium uppercase tracking-wide text-mute">
            Title
          </label>
          <input
            autoFocus
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            className="w-full rounded-md border border-line px-3 py-2 text-sm outline-none focus:border-ink"
            placeholder="Integrate payment API"
          />
        </div>

        <div>
          <label className="mb-1 block text-xs font-medium uppercase tracking-wide text-mute">
            Project
          </label>
          <select
            value={projectId}
            onChange={(e) => setProjectId(e.target.value)}
            className="w-full rounded-md border border-line px-3 py-2 text-sm outline-none focus:border-ink"
          >
            {projects.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name}
              </option>
            ))}
          </select>
        </div>

        <div>
          <label className="mb-1 block text-xs font-medium uppercase tracking-wide text-mute">
            Sprint
          </label>
          <select
            value={sprintId}
            onChange={(e) => setSprintId(e.target.value)}
            className="w-full rounded-md border border-line px-3 py-2 text-sm outline-none focus:border-ink"
          >
            <option value="">Backlog (no sprint)</option>
            {projectSprints.map((s) => (
              <option key={s.id} value={s.id}>
                Sprint {s.number}
              </option>
            ))}
          </select>
        </div>

        <div className="grid grid-cols-2 gap-4">
          <div>
            <label className="mb-1 block text-xs font-medium uppercase tracking-wide text-mute">
              Status
            </label>
            <select
              value={status}
              onChange={(e) => setStatus(e.target.value as TaskStatus)}
              className="w-full rounded-md border border-line px-3 py-2 text-sm outline-none focus:border-ink"
            >
              {statuses.map((s) => (
                <option key={s} value={s}>
                  {s.replace('_', ' ')}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label className="mb-1 block text-xs font-medium uppercase tracking-wide text-mute">
              Priority
            </label>
            <select
              value={priority}
              onChange={(e) => setPriority(e.target.value as TaskPriority)}
              className="w-full rounded-md border border-line px-3 py-2 text-sm outline-none focus:border-ink"
            >
              {priorities.map((p) => (
                <option key={p} value={p}>
                  {p}
                </option>
              ))}
            </select>
          </div>
        </div>

        <div className="grid grid-cols-2 gap-4">
          <div>
            <label className="mb-1 block text-xs font-medium uppercase tracking-wide text-mute">
              Story Points
            </label>
            <input
              type="number"
              min={0}
              value={storyPoints}
              onChange={(e) => setStoryPoints(Number(e.target.value))}
              className="w-full rounded-md border border-line px-3 py-2 text-sm outline-none focus:border-ink"
            />
          </div>
          <div>
            <label className="mb-1 block text-xs font-medium uppercase tracking-wide text-mute">
              Assignee
            </label>
            <select
              value={assigneeId}
              onChange={(e) => setAssigneeId(e.target.value)}
              disabled={!canAssign}
              className="w-full rounded-md border border-line px-3 py-2 text-sm outline-none focus:border-ink disabled:bg-paper disabled:text-mute"
            >
              <option value="">Unassigned</option>
              {members.map((m) => (
                <option key={m.id} value={m.id}>
                  {m.name}
                </option>
              ))}
            </select>
            {!canAssign && (
              <p className="mt-1 text-xs text-mute">Only admins and managers can assign work.</p>
            )}
          </div>
        </div>

        <div className="mt-2 flex justify-end gap-2">
          {error && <p className="mr-auto text-xs text-red-600">{error}</p>}
          <button
            type="button"
            onClick={onClose}
            className="rounded-md border border-line px-4 py-2 text-sm font-medium hover:bg-paper"
          >
            Cancel
          </button>
          <button
            type="submit"
            className="rounded-md bg-ink px-4 py-2 text-sm font-medium text-white hover:bg-black"
          >
            Create Task
          </button>
        </div>
      </form>
    </Modal>
  )
}
