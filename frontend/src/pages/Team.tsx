import { useState } from 'react'
import { memberById, type Member } from '../lib/mockData'
import { useAppStore } from '../store/useAppStore'
import { NewTeamModal } from '../components/team/NewTeamModal'
import { UserAvatar } from '../components/user/UserAvatar'

export function Team() {
  const teams = useAppStore((s) => s.teams)
  const projects = useAppStore((s) => s.projects)
  const members = useAppStore((s) => s.members)
  const addMemberToTeam = useAppStore((s) => s.addMemberToTeam)
  const removeMemberFromTeam = useAppStore((s) => s.removeMemberFromTeam)
  const assignProjectToTeam = useAppStore((s) => s.assignProjectToTeam)
  const unassignProjectFromTeam = useAppStore((s) => s.unassignProjectFromTeam)
  const deleteTeam = useAppStore((s) => s.deleteTeam)

  const [modalOpen, setModalOpen] = useState(false)

  return (
    <div className="px-4 py-6 sm:px-8 sm:py-8 lg:px-16 lg:py-12">
      <div className="mb-1 flex items-center justify-between">
        <div className="text-xs font-medium uppercase tracking-widest text-mute">
          Organization
        </div>
        <button
          onClick={() => setModalOpen(true)}
          className="rounded-md bg-ink px-4 py-2 text-sm font-medium text-white hover:bg-black"
        >
          + New Team
        </button>
      </div>
      <h1 className="text-3xl font-semibold tracking-tight text-ink sm:text-4xl lg:text-5xl">Team</h1>

      <div className="mt-12 flex flex-col gap-6">
        {teams.length === 0 && (
          <div className="border border-line bg-white p-8 text-sm text-mute">
            No teams yet. Create one to start assigning members and projects.
          </div>
        )}

        {teams.map((team) => {
          const teamMembers = team.memberIds
            .map((id) => memberById(members, id))
            .filter((m): m is Member => m !== undefined)
          const teamProjects = team.projectIds
            .map((pid) => projects.find((p) => p.id === pid))
            .filter((p): p is (typeof projects)[number] => p !== undefined)
          const availableMembers = members.filter((m) => !team.memberIds.includes(m.id))
          const availableProjects = projects.filter((p) => !team.projectIds.includes(p.id))
          const avgUtilization = teamMembers.length
            ? Math.round(teamMembers.reduce((sum, m) => sum + m.utilization, 0) / teamMembers.length)
            : 0

          return (
            <div key={team.id} className="border border-line bg-white p-4 sm:p-6 lg:p-8">
              <div className="flex items-start justify-between gap-3">
                <div>
                  <h2 className="text-xl font-semibold tracking-tight sm:text-2xl">{team.name}</h2>
                  <p className="mt-1 text-sm text-mute">
                    {teamMembers.length} member{teamMembers.length === 1 ? '' : 's'} · avg
                    utilization {avgUtilization}%
                  </p>
                </div>
                <button
                  onClick={() => deleteTeam(team.id)}
                  className="shrink-0 text-xs font-medium text-mute hover:text-danger"
                >
                  Delete team
                </button>
              </div>

              <div className="mt-2 h-1.5 w-full max-w-xs rounded-full bg-line">
                <div
                  className={`h-1.5 rounded-full ${avgUtilization > 85 ? 'bg-danger' : 'bg-ink'}`}
                  style={{ width: `${avgUtilization}%` }}
                />
              </div>

              <div className="mt-6 grid grid-cols-1 gap-6 border-t border-line pt-6 sm:grid-cols-2 sm:gap-8">
                <div>
                  <h3 className="mb-3 text-xs font-semibold uppercase tracking-widest text-mute">
                    Members
                  </h3>
                  <div className="flex flex-col gap-2">
                    {teamMembers.map((m) => (
                      <div key={m.id} className="flex items-center justify-between text-sm">
                        <div className="flex items-center gap-2">
                          <UserAvatar memberId={m.id} name={m.name} initials={m.initials} />
                          <span>{m.name}</span>
                          {m.employeeCode && (
                            <span className="font-mono text-xs text-mute">{m.employeeCode}</span>
                          )}
                          <span className="text-xs text-mute">{m.role}</span>
                        </div>
                        <button
                          onClick={() => removeMemberFromTeam(team.id, m.id)}
                          className="text-xs text-mute hover:text-danger"
                        >
                          Remove
                        </button>
                      </div>
                    ))}
                    {teamMembers.length === 0 && (
                      <p className="text-sm text-mute">No members assigned.</p>
                    )}
                  </div>

                  {availableMembers.length > 0 && (
                    <select
                      value=""
                      onChange={(e) => e.target.value && addMemberToTeam(team.id, e.target.value)}
                      className="mt-3 w-full rounded-md border border-line px-3 py-2 text-sm outline-none focus:border-ink"
                    >
                      <option value="">+ Add member</option>
                      {availableMembers.map((m) => (
                        <option key={m.id} value={m.id}>
                          {m.name} — {m.role}
                        </option>
                      ))}
                    </select>
                  )}
                </div>

                <div>
                  <h3 className="mb-3 text-xs font-semibold uppercase tracking-widest text-mute">
                    Assigned Projects
                  </h3>
                  <div className="flex flex-col gap-2">
                    {teamProjects.map((p) => (
                      <div key={p.id} className="flex items-center justify-between text-sm">
                        <span>{p.name}</span>
                        <button
                          onClick={() => unassignProjectFromTeam(team.id, p.id)}
                          className="text-xs text-mute hover:text-danger"
                        >
                          Unassign
                        </button>
                      </div>
                    ))}
                    {teamProjects.length === 0 && (
                      <p className="text-sm text-mute">No projects assigned.</p>
                    )}
                  </div>

                  {availableProjects.length > 0 && (
                    <select
                      value=""
                      onChange={(e) => e.target.value && assignProjectToTeam(team.id, e.target.value)}
                      className="mt-3 w-full rounded-md border border-line px-3 py-2 text-sm outline-none focus:border-ink"
                    >
                      <option value="">+ Assign project</option>
                      {availableProjects.map((p) => (
                        <option key={p.id} value={p.id}>
                          {p.name}
                        </option>
                      ))}
                    </select>
                  )}
                </div>
              </div>
            </div>
          )
        })}
      </div>

      <NewTeamModal open={modalOpen} onClose={() => setModalOpen(false)} />
    </div>
  )
}
