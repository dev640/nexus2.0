import { useState } from 'react'
import { useAppStore } from '../store/useAppStore'
import { NewSprintModal } from '../components/sprint/NewSprintModal'
import { EditSprintModal } from '../components/sprint/EditSprintModal'

const statusLabel: Record<string, string> = {
  PLANNED: 'Planned',
  ACTIVE: 'Active',
  COMPLETED: 'Completed',
}

const statusColor: Record<string, string> = {
  PLANNED: 'text-mute',
  ACTIVE: 'text-success',
  COMPLETED: 'text-info',
}

export function Sprints() {
  const sprints = useAppStore((s) => s.sprints)
  const tasks = useAppStore((s) => s.tasks)
  const setSprintStatus = useAppStore((s) => s.setSprintStatus)
  const canWrite = useAppStore((s) => s.currentUser?.role !== 'VIEWER')
  const [modalOpen, setModalOpen] = useState(false)
  const [modalProjectId, setModalProjectId] = useState<string | undefined>(undefined)
  const [editingSprintId, setEditingSprintId] = useState<string | null>(null)
  const projects = useAppStore((s) => s.projects)

  function openNewSprint(projectId?: string) {
    setModalProjectId(projectId)
    setModalOpen(true)
  }

  return (
    <div className="px-4 py-6 sm:px-8 sm:py-8 lg:px-16 lg:py-12">
      <div className="mb-1 flex flex-wrap items-center justify-between gap-2">
        <div className="text-xs font-medium uppercase tracking-widest text-mute">Delivery</div>
        {canWrite ? (
          <button
            onClick={() => openNewSprint(undefined)}
            className="rounded-md bg-ink px-4 py-2 text-sm font-medium text-white hover:bg-black"
          >
            + New Sprint
          </button>
        ) : (
          <span className="rounded-md bg-mute px-4 py-2 text-sm font-medium text-mute">+ New Sprint</span>
        )}
      </div>
      <h1 className="text-3xl font-semibold tracking-tight text-ink sm:text-4xl lg:text-5xl">Sprints</h1>

      <div className="mt-10 flex flex-col gap-8">
        {projects.map((project) => {
          const projectSprints = sprints
            .filter((s) => s.projectId === project.id)
            .sort((a, b) => b.number - a.number)

          return (
            <div key={project.id}>
              <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
                <h2 className="text-lg font-semibold tracking-tight text-ink">{project.name}</h2>
                <button
                  onClick={() => openNewSprint(project.id)}
                  className="rounded-md border border-line px-3 py-1.5 text-xs font-medium hover:bg-white"
                >
                  + Sprint for this project
                </button>
              </div>

              {projectSprints.length === 0 ? (
                <div className="border border-dashed border-line bg-white p-8 text-center text-sm text-mute">
                  No sprint yet for this project.{' '}
                  <button
                    onClick={() => openNewSprint(project.id)}
                    className="font-medium text-ink underline underline-offset-4"
                  >
                    Create one
                  </button>
                </div>
              ) : (
                <div className="flex flex-col gap-4">
                  {projectSprints.map((sprint) => {
                    const sprintTasks = tasks.filter((t) => t.sprintId === sprint.id)
                    const completedPoints = sprintTasks
                      .filter((t) => t.status === 'DONE')
                      .reduce((sum, t) => sum + t.storyPoints, 0)
                    const remainingPoints = Math.max(sprint.committedPoints - completedPoints, 0)
                    const pct =
                      sprint.committedPoints > 0
                        ? Math.round((completedPoints / sprint.committedPoints) * 100)
                        : 0

                    return (
                      <div key={sprint.id} className="border border-line bg-white p-4 sm:p-6 lg:p-8">
                        <div className="flex items-center justify-between">
                          <div className="text-xs font-semibold uppercase tracking-widest text-mute">
                            Sprint {sprint.number}
                          </div>
                          <div className="flex items-center gap-2">
                            {canWrite && (
                              <button
                                onClick={() => setEditingSprintId(sprint.id)}
                                className="shrink-0 rounded border border-line px-2 py-1 text-xs font-medium hover:bg-paper"
                              >
                                Edit
                              </button>
                            )}
                            <select
                              value={sprint.status}
                              onChange={(e) => setSprintStatus(sprint.id, e.target.value as typeof sprint.status)}
                              disabled={!canWrite}
                              className={`rounded border border-line bg-paper px-2 py-1 text-xs font-medium outline-none ${statusColor[sprint.status]}`}
                            >
                              {Object.entries(statusLabel).map(([key, label]) => (
                                <option key={key} value={key}>
                                  {label}
                                </option>
                              ))}
                            </select>
                          </div>
                        </div>
                        <h3 className="mt-2 text-2xl font-semibold tracking-tight">{sprint.goal}</h3>
                        <p className="mt-2 text-sm text-mute">
                          {sprint.startDate} → {sprint.endDate}
                        </p>

                        <div className="mt-6 grid grid-cols-2 gap-6 sm:grid-cols-4 lg:gap-8 border-t border-line pt-6">
                          <div>
                            <div className="mb-1 text-xs uppercase tracking-wide text-mute">Committed</div>
                            <div className="text-2xl font-semibold">{sprint.committedPoints} pts</div>
                          </div>
                          <div>
                            <div className="mb-1 text-xs uppercase tracking-wide text-mute">Completed</div>
                            <div className="text-2xl font-semibold">{completedPoints} pts</div>
                          </div>
                          <div>
                            <div className="mb-1 text-xs uppercase tracking-wide text-mute">Remaining</div>
                            <div className="text-2xl font-semibold">{remainingPoints} pts</div>
                          </div>
                          <div>
                            <div className="mb-1 text-xs uppercase tracking-wide text-mute">Tasks</div>
                            <div className="text-2xl font-semibold">{sprintTasks.length}</div>
                          </div>
                        </div>

                        <div className="mt-6 h-2 w-full rounded-full bg-line">
                          <div
                            className="h-2 rounded-full bg-ink"
                            style={{ width: `${pct}%` }}
                          />
                        </div>
                      </div>
                    )
                  })}
                </div>
              )}
            </div>
          )
        })}

        {projects.length === 0 && (
          <div className="border border-dashed border-line bg-white p-8 text-center text-sm text-mute">
            No projects yet. Create a project first.
          </div>
        )}
      </div>

      <NewSprintModal
        open={modalOpen}
        onClose={() => setModalOpen(false)}
        defaultProjectId={modalProjectId}
      />
      <EditSprintModal
        key={editingSprintId}
        sprintId={editingSprintId}
        onClose={() => setEditingSprintId(null)}
      />
    </div>
  )
}
