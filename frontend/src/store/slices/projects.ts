import type { StateCreator } from 'zustand'
import { apiCreateProject, apiDeleteProject, apiErrorMessage, apiUpdateProject } from '../../lib/api'
import type { Project } from '../../lib/mockData'
import type { NewProjectInput, ProjectEditInput, TaskResult } from '../contracts'
import { parseId, toProjectId } from '../ids'
import type { AppState } from '../state'

export interface ProjectsSlice {
  projects: Project[]

  // Projects
  addProject: (input: NewProjectInput) => Promise<Project | null>
  updateProject: (id: string, input: ProjectEditInput) => Promise<TaskResult>
  deleteProject: (id: string) => Promise<TaskResult>
}

export const createProjectsSlice: StateCreator<AppState, [], [], ProjectsSlice> = (set) => ({
  projects: [],

  addProject: async (input) => {
    try {
      const created = await apiCreateProject({ name: input.name, description: input.description })
      const project: Project = {
        id: toProjectId(created.id),
        name: created.name,
        description: created.description ?? '',
        status: created.status,
        health: created.health,
        progress: created.progress,
        sprintNumber: created.sprintNumber,
        memberCount: created.memberCount,
      }
      set((state) => ({ projects: [...state.projects, project] }))
      return project
    } catch (err) {
      set({ syncError: apiErrorMessage(err, 'Failed to create project') })
      return null
    }
  },

  updateProject: async (id, input) => {
    try {
      const updated = await apiUpdateProject(parseId(id), {
        name: input.name,
        description: input.description,
        status: input.status,
      })
      set((state) => ({
        projects: state.projects.map((p) =>
          p.id === id
            ? {
                ...p,
                name: updated.name,
                description: updated.description ?? '',
                status: updated.status,
                health: updated.health,
                progress: updated.progress,
              }
            : p,
        ),
        syncError: null,
      }))
      return { ok: true }
    } catch (err) {
      set({ syncError: apiErrorMessage(err, 'Failed to update project') })
      return { ok: false, error: apiErrorMessage(err, 'Failed to update project') }
    }
  },

  deleteProject: async (id) => {
    try {
      await apiDeleteProject(parseId(id))
      // Sprints and tasks belonging to the project go with it, so leaving them
      // in local state would leave rows pointing at something that no longer
      // exists.
      set((state) => ({
        projects: state.projects.filter((p) => p.id !== id),
        sprints: state.sprints.filter((s) => s.projectId !== id),
        tasks: state.tasks.filter((t) => t.projectId !== id),
        syncError: null,
      }))
      return { ok: true }
    } catch (err) {
      const error = apiErrorMessage(err, 'Failed to delete project')
      set({ syncError: error })
      return { ok: false, error }
    }
  },
})
