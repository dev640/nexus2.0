import { useState } from 'react'
import { Modal } from '../ui/Modal'
import { useAppStore, type SprintEditInput } from '../../store/useAppStore'
import type { ApiSprintStatus } from '../../lib/api'

const statuses: ApiSprintStatus[] = ['PLANNED', 'ACTIVE', 'COMPLETED']

interface Props {
  sprintId: string | null
  onClose: () => void
}

/**
 * Edits a sprint, and offers deletion from the same place.
 *
 * The project is shown read-only: the backend refuses to move a sprint between
 * projects because that would orphan its tasks and renumber the target
 * project's sprint sequence, so offering the choice would only produce an error.
 *
 * The caller keys this by sprintId so switching sprints remounts it, which is
 * why the fields are seeded once from props rather than re-synced by an effect.
 */
export function EditSprintModal({ sprintId, onClose }: Props) {
  const sprint = useAppStore((s) => s.sprints.find((x) => x.id === sprintId) ?? null)
  const project = useAppStore((s) =>
    sprint ? s.projects.find((p) => p.id === sprint.projectId) ?? null : null,
  )
  const updateSprint = useAppStore((s) => s.updateSprint)
  const deleteSprint = useAppStore((s) => s.deleteSprint)
  const taskCount = useAppStore(
    (s) => (sprintId ? s.tasks.filter((t) => t.sprintId === sprintId).length : 0),
  )

  const [goal, setGoal] = useState(() => sprint?.goal ?? '')
  const [startDate, setStartDate] = useState(() => sprint?.startDate ?? '')
  const [endDate, setEndDate] = useState(() => sprint?.endDate ?? '')
  const [committedPoints, setCommittedPoints] = useState(() => String(sprint?.committedPoints ?? 0))
  const [status, setStatus] = useState<ApiSprintStatus>(() => sprint?.status ?? 'PLANNED')
  const [error, setError] = useState('')
  const [confirmingDelete, setConfirmingDelete] = useState(false)
  const [busy, setBusy] = useState(false)

  if (!sprint) return null

  async function handleSave(e: React.FormEvent) {
    e.preventDefault()
    if (!goal.trim()) {
      setError('Goal is required')
      return
    }
    if (endDate < startDate) {
      setError('End date must be after the start date')
      return
    }
    setBusy(true)
    const input: SprintEditInput = {
      projectId: sprint!.projectId,
      goal: goal.trim(),
      startDate,
      endDate,
      committedPoints: Number(committedPoints) || 0,
      status,
    }
    const result = await updateSprint(sprint!.id, input)
    setBusy(false)
    if (!result.ok) {
      setError(result.error ?? 'Could not save the sprint')
      return
    }
    onClose()
  }

  async function handleDelete() {
    setBusy(true)
    const result = await deleteSprint(sprint!.id)
    setBusy(false)
    if (!result.ok) {
      setError(result.error ?? 'Could not delete the sprint')
      return
    }
    onClose()
  }

  return (
    <Modal open={sprintId != null} onClose={onClose} title={`Edit Sprint ${sprint.number}`}>
      <form onSubmit={handleSave} className="flex flex-col gap-4">
        <div>
          <label className="mb-1 block text-xs font-medium uppercase tracking-wide text-mute">
            Project
          </label>
          {/* Read-only on purpose: sprints cannot be moved between projects. */}
          <div className="rounded-md border border-line bg-paper px-3 py-2 text-sm text-mute">
            {project?.name ?? 'Unknown project'}
            <span className="ml-2 text-[10px] uppercase tracking-wide">(cannot be changed)</span>
          </div>
        </div>
        <div>
          <label className="mb-1 block text-xs font-medium uppercase tracking-wide text-mute">
            Goal
          </label>
          <input
            autoFocus
            value={goal}
            onChange={(e) => setGoal(e.target.value)}
            className="w-full rounded-md border border-line px-3 py-2 text-sm outline-none focus:border-ink"
          />
        </div>
        <div className="grid grid-cols-2 gap-3">
          <div>
            <label className="mb-1 block text-xs font-medium uppercase tracking-wide text-mute">
              Start
            </label>
            <input
              type="date"
              value={startDate}
              onChange={(e) => setStartDate(e.target.value)}
              className="w-full rounded-md border border-line px-3 py-2 text-sm outline-none focus:border-ink"
            />
          </div>
          <div>
            <label className="mb-1 block text-xs font-medium uppercase tracking-wide text-mute">
              End
            </label>
            <input
              type="date"
              value={endDate}
              onChange={(e) => setEndDate(e.target.value)}
              className="w-full rounded-md border border-line px-3 py-2 text-sm outline-none focus:border-ink"
            />
          </div>
        </div>
        <div className="grid grid-cols-2 gap-3">
          <div>
            <label className="mb-1 block text-xs font-medium uppercase tracking-wide text-mute">
              Committed points
            </label>
            <input
              type="number"
              min={0}
              value={committedPoints}
              onChange={(e) => setCommittedPoints(e.target.value)}
              className="w-full rounded-md border border-line px-3 py-2 text-sm outline-none focus:border-ink"
            />
          </div>
          <div>
            <label className="mb-1 block text-xs font-medium uppercase tracking-wide text-mute">
              Status
            </label>
            <select
              value={status}
              onChange={(e) => setStatus(e.target.value as ApiSprintStatus)}
              className="w-full rounded-md border border-line px-3 py-2 text-sm outline-none focus:border-ink"
            >
              {statuses.map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
          </div>
        </div>

        <div className="mt-2 flex flex-wrap items-center justify-end gap-2">
          {error && <p className="mr-auto text-xs text-red-600">{error}</p>}
          {confirmingDelete ? (
            <>
              <span className="mr-auto text-xs text-danger">
                Delete this sprint?
                {taskCount > 0
                  ? ` It still holds ${taskCount} task${taskCount === 1 ? '' : 's'}, so the server will refuse.`
                  : ''}
              </span>
              <button
                type="button"
                onClick={() => setConfirmingDelete(false)}
                disabled={busy}
                className="rounded-md border border-line px-3 py-2 text-sm font-medium hover:bg-paper"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={() => void handleDelete()}
                disabled={busy}
                className="rounded-md bg-danger px-3 py-2 text-sm font-medium text-white hover:opacity-90"
              >
                {busy ? 'Deleting…' : 'Delete for good'}
              </button>
            </>
          ) : (
            <>
              <button
                type="button"
                onClick={() => setConfirmingDelete(true)}
                className="mr-auto rounded-md border border-line px-3 py-2 text-sm font-medium text-danger hover:bg-paper"
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
                disabled={busy}
                className="rounded-md bg-ink px-4 py-2 text-sm font-medium text-white hover:bg-black"
              >
                {busy ? 'Saving…' : 'Save Changes'}
              </button>
            </>
          )}
        </div>
      </form>
    </Modal>
  )
}