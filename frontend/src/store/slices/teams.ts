import type { StateCreator } from 'zustand'
import { seedTeams, type Team } from '../models'
import type { AppState } from '../state'

export interface TeamsSlice {
  teams: Team[]

  // Members / teams (local until Phase 2/6)
  addTeam: (name: string) => Team
  deleteTeam: (teamId: string) => void
  addMemberToTeam: (teamId: string, memberId: string) => void
  removeMemberFromTeam: (teamId: string, memberId: string) => void
  assignProjectToTeam: (teamId: string, projectId: string) => void
  unassignProjectFromTeam: (teamId: string, projectId: string) => void
}

export const createTeamsSlice: StateCreator<AppState, [], [], TeamsSlice> = (set) => ({
  teams: seedTeams,

  addTeam: (name) => {
    const newTeam: Team = {
      id:
        name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/(^-|-$)/g, '') || `team-${Date.now()}`,
      name,
      memberIds: [],
      projectIds: [],
    }
    set((state) => ({ teams: [...state.teams, newTeam] }))
    return newTeam
  },

  deleteTeam: (teamId) => {
    set((state) => ({ teams: state.teams.filter((t) => t.id !== teamId) }))
  },

  addMemberToTeam: (teamId, memberId) => {
    set((state) => ({
      teams: state.teams.map((t) =>
        t.id === teamId && !t.memberIds.includes(memberId)
          ? { ...t, memberIds: [...t.memberIds, memberId] }
          : t,
      ),
    }))
  },

  removeMemberFromTeam: (teamId, memberId) => {
    set((state) => ({
      teams: state.teams.map((t) =>
        t.id === teamId ? { ...t, memberIds: t.memberIds.filter((id) => id !== memberId) } : t,
      ),
    }))
  },

  assignProjectToTeam: (teamId, projectId) => {
    set((state) => ({
      teams: state.teams.map((t) =>
        t.id === teamId && !t.projectIds.includes(projectId)
          ? { ...t, projectIds: [...t.projectIds, projectId] }
          : t,
      ),
    }))
  },

  unassignProjectFromTeam: (teamId, projectId) => {
    set((state) => ({
      teams: state.teams.map((t) =>
        t.id === teamId ? { ...t, projectIds: t.projectIds.filter((id) => id !== projectId) } : t,
      ),
    }))
  },
})
