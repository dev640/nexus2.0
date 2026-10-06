import { useEffect, useState } from 'react'
import { Modal } from '../ui/Modal'
import { useAppStore } from '../../store/useAppStore'
import { labelsToInput, parseLabels } from '../../lib/labels'
import type { TaskPriority, TaskStatus } from '../../lib/mockData'

const statuses: TaskStatus[] = ['BACKLOG', 'TODO', 'IN_PROGRESS', 'IN_REVIEW', 'TESTING', 'DONE']
const priorities: TaskPriority[] = ['LOW', 'MEDIUM', 'HIGH', 'URGENT']

const fieldLabel = 'mb-1 block text-xs font-medium uppercase tracking-wide text-mute'
const field =
  'w-full rounded-md border border-line px-3 py-2 text-sm outline-none focus:border-ink'

export function EditTaskModal({
  taskId,
  onClose,
}: {
  taskId: string | null
  onClose: () => void
}) {
  const tasks = useAppStore((s) => s.tasks)
  const projects = useAppStore((s) => s.projects)
  const sprints = useAppStore((s) => s.sprints)
  const members = useAppStore((s) => s.members)
  const updateTask = useAppStore((s) => s.updateTask)
  const deleteTask = useAppStore((s) => s.deleteTask)

  const task = taskId ? tasks.find((t) => t.id === taskId) : undefined

  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [labelsInput, setLabelsInput] = useState('')
  const [projectId, setProjectId] = useState('')
  const [sprintId, setSprintId] = useState('')
  const [status, setStatus] = useState<TaskStatus>('BACKLOG')
  const [priority, setPriority] = useState<TaskPriority>('MEDIUM')
  const [storyPoints, setStoryPoints] = useState(0)
  const [assigneeId, setAssigneeId] = useState('')
  const [blocked, setBlocked] = useState(false)
  const [error, setError] = useState('')
  const [confirmingDelete, setConfirmingDelete] = useState(false)
  const [saving, setSaving] = useState(false)

  // Re-seed the form whenever a different task is opened, so a cancelled edit
  // never leaks its values into the next one.
  useEffect(() => {
    if (!task) return
    setTitle(task.title)
    setDescription(task.description ?? '')
    setLabelsInput(labelsToInput(task.labels))
    setProjectId(task.projectId)
    setSprintId(task.sprintId ?? '')
    setStatus(task.status)
    setPriority(task.priority)
    setStoryPoints(task.storyPoints)
    setAssigneeId(task.assigneeId)
    setBlocked(task.blocked ?? false)
    setError('')
    setConfirmingDelete(false)
    setSaving(false)
  }, [task])

  const projectSprints = sprints
    .filter((s) => s.projectId === projectId)
    .sort((a, b) => b.number - a.number)

  // A sprint belonging to another project is rejected by the API, so drop the
  // selection rather than letting the user submit a guaranteed failure.
  useEffect(() => {
    if (sprintId && !projectSprints.some((s) => s.id === sprintId)) {
      setSprintId('')
    }
  }, [projectId, sprintId, projectSprints])

  // Every hook above must run on every render, so the closed-modal bail-out
  // comes last.
  if (!task) return null

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!task) return
    if (!title.trim()) {
      // Previously this returned silently, so an empty title looked like a
      // button that did nothing.
      setError('Title is required')
      return
    }
    if (!projectId) {
      setError('Pick a project')
      return
    }
    setSaving(true)
    setError('')
    const result = await updateTask(task.id, {
      title: title.trim(),
      description: description.trim(),
      projectId,
      sprintId: sprintId || undefined,
      status,
      priority,
      storyPoints,
      assigneeId,
      labels: parseLabels(labelsInput),
      blocked,
    })
    setSaving(false)
    if (!result.ok) {
      setError(result.error ?? 'Could not save the task.')
      return
    }
    onClose()
  }

  async function handleDelete() {
    if (!task) return
    setSaving(true)
    setError('')
    const result = await deleteTask(task.id)
    setSaving(false)
    if (!result.ok) {
      setError(result.error ?? 'Could not delete the task.')
      setConfirmingDelete(false)
      return
    }
    onClose()
  }

  return (
    <Modal open onClose={onClose} title={`Edit ${task.id}`}>
      <form onSubmit={handleSubmit} className="flex flex-col gap-4">
        <div>
          <label className={fieldLabel}>Title</label>
          <input
            autoFocus
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            className={field}
            placeholder="Integrate payment API"
          />
        </div>

        <div>
          <label className={fieldLabel}>Description</label>
          <textarea
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            rows={3}
            className={field}
            placeholder="What does done look like?"
          />
          <p className="mt-1 text-xs text-mute">
            Shown on the task card. Leave blank if not needed.
          </p>
        </div>

        <div>
          <label className={fieldLabel}>Labels</label>
          <input
            value={labelsInput}
            onChange={(e) => setLabelsInput(e.target.value)}
            className={field}
            placeholder="backend, urgent"
          />
          <p className="mt-1 text-xs text-mute">
            Separate labels with commas. Blank entries are ignored.
          </p>
        </div>

        <div>
          <label className={fieldLabel}>Project</label>
          <select
            value={projectId}
            onChange={(e) => setProjectId(e.target.value)}
            className={field}
          >
            {projects.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name}
              </option>
            ))}
          </select>
        </div>

        <div>
          <label className={fieldLabel}>Sprint</label>
          <select
            value={sprintId}
            onChange={(e) => setSprintId(e.target.value)}
            className={field}
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
            <label className={fieldLabel}>Status</label>
            <select
              value={status}
              onChange={(e) => setStatus(e.target.value as TaskStatus)}
              className={field}
            >
              {statuses.map((s) => (
                <option key={s} value={s}>
                  {s.replace('_', ' ')}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label className={fieldLabel}>Priority</label>
            <select
              value={priority}
              onChange={(e) => setPriority(e.target.value as TaskPriority)}
              className={field}
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
            <label className={fieldLabel}>Story Points</label>
            <input
              type="number"
              min={0}
              value={storyPoints}
              onChange={(e) => setStoryPoints(Math.max(0, Number(e.target.value)))}
              className={field}
            />
          </div>
          <div>
            <label className={fieldLabel}>Assignee</label>
            <select
              value={assigneeId}
              onChange={(e) => setAssigneeId(e.target.value)}
              className={field}
            >
              <option value="">Unassigned</option>
              {members.map((m) => (
                <option key={m.id} value={m.id}>
                  {m.name}
                </option>
              ))}
            </select>
          </div>
        </div>

        <label className="flex cursor-pointer items-center gap-2 text-sm">
          <input
            type="checkbox"
            checked={blocked}
            onChange={(e) => setBlocked(e.target.checked)}
            className="h-4 w-4 accent-danger"
          />
          Blocked
          {/* Blocked is a flag, not a status: a blocked task is still IN_PROGRESS
              or TODO, and the two answers are different questions. */}
          <span className="text-xs text-mute">
            waiting on something — status stays as it is
          </span>
        </label>

        {error && <p className="text-xs text-danger">{error}</p>}

        <div className="mt-2 flex items-center justify-end gap-2">
          {confirmingDelete ? (
            <>
              <span className="mr-auto text-xs text-mute">Delete this task?</span>
              <button
                type="button"
                disabled={saving}
                onClick={() => void handleDelete()}
                className="rounded-md bg-danger px-4 py-2 text-sm font-medium text-white hover:bg-danger/90 disabled:opacity-60"
              >
                {saving ? 'Deleting…' : 'Confirm delete'}
              </button>
              <button
                type="button"
                onClick={() => setConfirmingDelete(false)}
                className="rounded-md border border-line px-4 py-2 text-sm font-medium hover:bg-paper"
              >
                Cancel
              </button>
            </>
          ) : (
            <>
              <button
                type="button"
                onClick={() => setConfirmingDelete(true)}
                className="mr-auto rounded-md border border-danger/40 px-3 py-2 text-sm font-medium text-danger hover:bg-danger/10"
              >
                Delete
              </button>
              <button
                type="button"
                onClick={onClose}
                className="rounded-md border border-line px-4 py-2 text-sm font-medium hover:bg-paper"
              >
                Cancel
              </button>
              <button
                type="submit"
                disabled={saving}
                className="rounded-md bg-ink px-4 py-2 text-sm font-medium text-white hover:bg-black disabled:opacity-60"
              >
                {saving ? 'Saving…' : 'Save changes'}
              </button>
            </>
          )}
        </div>
      </form>
    </Modal>
  )
}