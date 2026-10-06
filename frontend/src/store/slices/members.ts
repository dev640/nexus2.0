import type { StateCreator } from 'zustand'
import { apiDeleteUser, apiErrorMessage } from '../../lib/api'
import type { Member } from '../../lib/mockData'
import type { NewMemberInput, TaskResult } from '../contracts'
import { parseId } from '../ids'
import { initialsOf } from '../mappers'
import type { AppState } from '../state'

export interface MembersSlice {
  members: Member[]

  // Members
  addMember: (input: NewMemberInput) => Member
  removeMember: (memberId: string) => Promise<TaskResult>
}

export const createMembersSlice: StateCreator<AppState, [], [], MembersSlice> = (set) => ({
  members: [],

  addMember: (input) => {
    const id =
      input.name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/(^-|-$)/g, '') || `member-${Date.now()}`
    const newMember: Member = {
      id,
      name: input.name,
      initials: initialsOf(input.name),
      role: input.role,
      utilization: 0,
    }
    set((state) => ({ members: [...state.members, newMember] }))
    return newMember
  },

  removeMember: async (memberId) => {
    try {
      await apiDeleteUser(parseId(memberId))
      set((state) => ({
        members: state.members.filter((m) => m.id !== memberId),
        // Their tasks stay, but become unassigned rather than pointing at an
        // assignee that no longer exists.
        tasks: state.tasks.map((t) => (t.assigneeId === memberId ? { ...t, assigneeId: '' } : t)),
        teams: state.teams.map((team) => ({
          ...team,
          memberIds: team.memberIds.filter((m) => m !== memberId),
        })),
        syncError: null,
      }))
      return { ok: true }
    } catch (err) {
      const error = apiErrorMessage(err, 'Failed to remove member')
      set({ syncError: error })
      return { ok: false, error }
    }
  },
})
