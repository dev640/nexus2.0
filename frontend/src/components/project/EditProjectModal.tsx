import { useState } from 'react'
import { Modal } from '../ui/Modal'
import { useAppStore, type ProjectEditInput } from '../../store/useAppStore'
import type { ApiProjectStatus } from '../../lib/api'

const statuses: ApiProjectStatus[] = ['PLANNING', 'ACTIVE', 'ON_HOLD', 'COMPLETED', 'ARCHIVED']

interface Props {
  projectId: string | null
  onClose: () => void
}

/**
 * Edits a project, and offers deletion from the same place.
 *
 * Delete lives here rather than on the card because it is irreversible and
 * removes the project's sprints and tasks with it; putting it one deliberate
 * step away from the edit it shares a dialog with keeps it from being a stray
 * misclick on a list row.
 *
 * The caller keys this by projectId so switching projects remounts it. That is
 * why the fields can be seeded once from props instead of being re-synced by an
 * effect: there is no second project to re-sync for.
 */
export function EditProjectModal({ projectId, onClose }: Props) {
  const project = useAppStore((s) => s.projects.find((p) => p.id === projectId) ?? null)
  const updateProject = useAppStore((s) => s.updateProject)
  const deleteProject = useAppStore((s) => s.deleteProject)

  const [name, setName] = useState(() => project?.name ?? '')
  const [description, setDescription] = useState(() => project?.description ?? '')
  const [status, setStatus] = useState<ApiProjectStatus>(() => project?.status ?? 'PLANNING')
  const [error, setError] = useState('')
  const [confirmingDelete, setConfirmingDelete] = useState(false)
  const [busy, setBusy] = useState(false)

  if (!project) return null

  async function handleSave(e: React.FormEvent) {
    e.preventDefault()
    if (!name.trim()) {
      setError('Name is required')
      return
    }
    setBusy(true)
    const input: ProjectEditInput = { name: name.trim(), description: description.trim(), status }
    const result = await updateProject(project!.id, input)
    setBusy(false)
    if (!result.ok) {
      setError(result.error ?? 'Could not save the project')
      return
    }
    onClose()
  }

  async function handleDelete() {
    setBusy(true)
    const result = await deleteProject(project!.id)
    setBusy(false)
    if (!result.ok) {
      setError(result.error ?? 'Could not delete the project')
      return
    }
    onClose()
  }

  return (
    <Modal open={projectId != null} onClose={onClose} title="Edit Project">
      <form onSubmit={handleSave} className="flex flex-col gap-4">
        <div>
          <label className="mb-1 block text-xs font-medium uppercase tracking-wide text-mute">
            Name
          </label>
          <input
            autoFocus
            value={name}
            onChange={(e) => setName(e.target.value)}
            className="w-full rounded-md border border-line px-3 py-2 text-sm outline-none focus:border-ink"
          />
        </div>
        <div>
          <label className="mb-1 block text-xs font-medium uppercase tracking-wide text-mute">
            Description
          </label>
          <textarea
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            rows={3}
            className="w-full rounded-md border border-line px-3 py-2 text-sm outline-none focus:border-ink"
          />
        </div>
        <div>
          <label className="mb-1 block text-xs font-medium uppercase tracking-wide text-mute">
            Status
          </label>
          <select
            value={status}
            onChange={(e) => setStatus(e.target.value as ApiProjectStatus)}
            className="w-full rounded-md border border-line px-3 py-2 text-sm outline-none focus:border-ink"
          >
            {statuses.map((s) => (
              <option key={s} value={s}>
                {s.replace('_', ' ')}
              </option>
            ))}
          </select>
        </div>

        <div className="mt-2 flex flex-wrap items-center justify-end gap-2">
          {error && <p className="mr-auto text-xs text-red-600">{error}</p>}
          {confirmingDelete ? (
            <>
              <span className="mr-auto text-xs text-danger">
                Delete this project and all of its sprints and tasks?
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