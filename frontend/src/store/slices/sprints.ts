import type { StateCreator } from 'zustand'
import {
  apiCreateSprint,
  apiDeleteSprint,
  apiErrorMessage,
  apiUpdateSprint,
  apiUpdateSprintStatus,
} from '../../lib/api'
import type { Sprint, SprintStatus } from '../../lib/mockData'
import type { NewSprintInput, SprintEditInput, TaskResult } from '../contracts'
import { parseId, toProjectId, toSprintId } from '../ids'
import type { AppState } from '../state'

export interface SprintsSlice {
  sprints: Sprint[]

  // Sprints
  addSprint: (input: NewSprintInput) => Promise<Sprint | null>
  setSprintStatus: (sprintId: string, status: SprintStatus) => void
  updateSprint: (id: string, input: SprintEditInput) => Promise<TaskResult>
  deleteSprint: (id: string) => Promise<TaskResult>
}

export const createSprintsSlice: StateCreator<AppState, [], [], SprintsSlice> = (set, get) => ({
  sprints: [],

  addSprint: async (input) => {
    try {
      const created = await apiCreateSprint({
        projectId: parseId(input.projectId),
        goal: input.goal,
        startDate: input.startDate,
        endDate: input.endDate,
        committedPoints: input.committedPoints,
      })
      const sprint: Sprint = {
        id: toSprintId(created.id),
        projectId: toProjectId(created.projectId),
        number: created.number,
        goal: created.goal,
        startDate: created.startDate,
        endDate: created.endDate,
        committedPoints: created.committedPoints,
        status: created.status,
      }
      set((state) => ({
        sprints: [...state.sprints, sprint],
        projects: state.projects.map((p) =>
          p.id === sprint.projectId ? { ...p, sprintNumber: sprint.number } : p,
        ),
      }))
      return sprint
    } catch (err) {
      set({ syncError: apiErrorMessage(err, 'Failed to create sprint') })
      return null
    }
  },

  setSprintStatus: (sprintId, status) => {
    const previous = get().sprints
    set({
      sprints: previous.map((s) => (s.id === sprintId ? { ...s, status } : s)),
      syncError: null,
    })
    apiUpdateSprintStatus(parseId(sprintId), status).catch((err) => {
      set({ sprints: previous, syncError: apiErrorMessage(err, 'Failed to update sprint') })
    })
  },

  updateSprint: async (id, input) => {
    try {
      const updated = await apiUpdateSprint(parseId(id), {
        projectId: parseId(input.projectId),
        goal: input.goal,
        startDate: input.startDate,
        endDate: input.endDate,
        committedPoints: input.committedPoints,
        status: input.status,
      })
      set((state) => ({
        sprints: state.sprints.map((s) =>
          s.id === id
            ? {
                ...s,
                goal: updated.goal,
                startDate: updated.startDate,
                endDate: updated.endDate,
                committedPoints: updated.committedPoints,
                status: updated.status,
              }
            : s,
        ),
        syncError: null,
      }))
      return { ok: true }
    } catch (err) {
      const error = apiErrorMessage(err, 'Failed to update sprint')
      set({ syncError: error })
      return { ok: false, error }
    }
  },

  deleteSprint: async (id) => {
    try {
      await apiDeleteSprint(parseId(id))
      set((state) => ({ sprints: state.sprints.filter((s) => s.id !== id), syncError: null }))
      return { ok: true }
    } catch (err) {
      // The backend refuses while the sprint holds tasks, and says how many —
      // surface that rather than a generic failure.
      const error = apiErrorMessage(err, 'Failed to delete sprint')
      set({ syncError: error })
      return { ok: false, error }
    }
  },
})
