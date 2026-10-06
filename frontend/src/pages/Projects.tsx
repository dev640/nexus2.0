import { useState } from 'react'
import { memberById } from '../lib/mockData'
import { useAppStore } from '../store/useAppStore'
import { NewProjectModal } from '../components/project/NewProjectModal'
import { EditProjectModal } from '../components/project/EditProjectModal'
import { NewTaskModal } from '../components/task/NewTaskModal'
import { UserAvatar } from '../components/user/UserAvatar'

const healthColor: Record<string, string> = {
  ON_TRACK: 'text-success',
  AT_RISK: 'text-warning',
  OFF_TRACK: 'text-danger',
}

const healthLabel: Record<string, string> = {
  ON_TRACK: 'On Track',
  AT_RISK: 'At Risk',
  OFF_TRACK: 'Off Track',
}

export function Projects() {
  const projects = useAppStore((s) => s.projects)
  const teams = useAppStore((s) => s.teams)
  const members = useAppStore((s) => s.members)
  // VIEWER is read-only: the backend rejects project and task writes with 403,
  // so the page shows no controls that would fail.
  const canWrite = useAppStore((s) => s.currentUser?.role !== 'VIEWER')
  const [modalOpen, setModalOpen] = useState(false)
  const [editingProjectId, setEditingProjectId] = useState<string | null>(null)
  const [taskModalOpen, setTaskModalOpen] = useState(false)
  const [taskProjectId, setTaskProjectId] = useState<string | undefined>(undefined)

  function openNewTask(projectId: string) {
    setTaskProjectId(projectId)
    setTaskModalOpen(true)
  }

  return (
    <div className="px-4 py-6 sm:px-8 sm:py-8 lg:px-16 lg:py-12">
      <div className="mb-1 flex items-center justify-between">
        <div className="text-xs font-medium uppercase tracking-widest text-mute">
          Workspace
        </div>
        {canWrite ? (
          <button
            onClick={() => setModalOpen(true)}
            className="rounded-md bg-ink px-4 py-2 text-sm font-medium text-white hover:bg-black"
          >
            + New Project
          </button>
        ) : (
          <span className="rounded-md bg-mute px-4 py-2 text-sm font-medium text-mute">+ New Project</span>
        )}
      </div>
      <h1 className="text-3xl font-semibold tracking-tight text-ink sm:text-4xl lg:text-5xl">Projects</h1>

      <div className="mt-12 flex flex-col gap-6">
        {projects.map((project, i) => {
          const team = teams.find((t) => t.projectIds.includes(project.id))
          const teamMembers = team ? team.memberIds.map((id) => memberById(members, id)).filter(Boolean) : []

          return (
          <div key={project.id} className="border border-line bg-white p-4 sm:p-6 lg:p-8">
            <div className="flex items-center justify-between gap-3">
              <div className="text-xs font-semibold uppercase tracking-widest text-mute">
                Project {String(i + 1).padStart(2, '0')}
              </div>
              {canWrite ? (
                <div className="flex shrink-0 gap-2">
                  <button
                    onClick={() => setEditingProjectId(project.id)}
                    className="rounded-md border border-line px-3 py-1.5 text-xs font-medium hover:bg-paper"
                  >
                    Edit
                  </button>
                  <button
                    onClick={() => openNewTask(project.id)}
                    className="rounded-md border border-line px-3 py-1.5 text-xs font-medium hover:bg-paper"
                  >
                    + Add Task
                  </button>
                </div>
              ) : (
                <span className="shrink-0 rounded-md border border-line px-3 py-1.5 text-xs font-medium text-mute">+ Add Task</span>
              )}
            </div>
            <h2 className="mt-2 text-2xl font-semibold tracking-tight sm:text-3xl">{project.name}</h2>
            <p className="mt-2 max-w-xl text-sm text-mute">
              {project.description || 'No description yet.'}
            </p>

            <div className="mt-6 grid grid-cols-2 gap-6 border-t border-line pt-6 text-sm sm:grid-cols-3 lg:grid-cols-5 lg:gap-8">
              <div>
                <div className="mb-1 text-xs uppercase tracking-wide text-mute">Progress</div>
                <div className="text-lg font-medium">{project.progress}%</div>
              </div>
              <div>
                <div className="mb-1 text-xs uppercase tracking-wide text-mute">Sprint</div>
                <div className="text-lg font-medium">
                  {project.sprintNumber ? `Sprint ${project.sprintNumber}` : '—'}
                </div>
              </div>
              <div>
                <div className="mb-1 text-xs uppercase tracking-wide text-mute">Members</div>
                <div className="text-lg font-medium">{project.memberCount}</div>
              </div>
              <div>
                <div className="mb-1 text-xs uppercase tracking-wide text-mute">Health</div>
                <div className={`text-lg font-medium ${healthColor[project.health]}`}>
                  {healthLabel[project.health]}
                </div>
              </div>
              <div>
                <div className="mb-1 text-xs uppercase tracking-wide text-mute">Team</div>
                {team ? (
                  <>
                    <div className="text-sm font-medium">{team.name}</div>
                    <div className="mt-1 flex -space-x-2">
                      {teamMembers.map((m) =>
                        m ? (
                          <UserAvatar
                            key={m.id}
                            memberId={m.id}
                            name={m.name}
                            initials={m.initials}
                            size="md"
                            className="h-8 w-8 border-2 border-white text-xs"
                          />
                        ) : null,
                      )}
                    </div>
                  </>
                ) : (
                  <div className="text-sm text-mute">Unassigned</div>
                )}
              </div>
            </div>
          </div>
          )
        })}
      </div>

      <NewProjectModal open={modalOpen} onClose={() => setModalOpen(false)} />
      <EditProjectModal
        key={editingProjectId}
        projectId={editingProjectId}
        onClose={() => setEditingProjectId(null)}
      />
      <NewTaskModal
        open={taskModalOpen}
        onClose={() => setTaskModalOpen(false)}
        defaultProjectId={taskProjectId}
      />
    </div>
  )
}
