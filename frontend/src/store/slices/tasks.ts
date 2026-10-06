import type { StateCreator } from 'zustand'
import {
  apiCreateTask,
  apiDeleteTask,
  apiErrorMessage,
  apiUpdateTask,
  apiUpdateTaskStatus,
} from '../../lib/api'
import type { Task, TaskStatus } from '../../lib/mockData'
import type { NewTaskInput, TaskEditInput, TaskResult } from '../contracts'
import { parseId } from '../ids'
import { mapTask } from '../mappers'
import type { AppState } from '../state'

export interface TasksSlice {
  tasks: Task[]

  // Tasks
  addTask: (input: NewTaskInput) => Promise<Task | null>
  updateTaskStatus: (id: string, status: TaskStatus) => void
  updateTask: (id: string, input: TaskEditInput) => Promise<TaskResult>
  deleteTask: (id: string) => Promise<TaskResult>
}

export const createTasksSlice: StateCreator<AppState, [], [], TasksSlice> = (set, get) => ({
  tasks: [],

  addTask: async (input) => {
    try {
      const created = await apiCreateTask({
        title: input.title,
        projectId: parseId(input.projectId),
        sprintId: input.sprintId ? parseId(input.sprintId) : null,
        status: input.status,
        priority: input.priority,
        storyPoints: input.storyPoints,
        assigneeId: input.assigneeId ? parseId(input.assigneeId) : null,
        labels: [],
      })
      const task = mapTask(created)
      set((state) => ({ tasks: [...state.tasks, task] }))
      return task
    } catch (err) {
      set({ syncError: apiErrorMessage(err, 'Failed to create task') })
      return null
    }
  },

  updateTask: async (id, input) => {
    const existing = get().tasks.find((t) => t.id === id)
    if (!existing) return { ok: false, error: 'Task not found' }
    try {
      const updated = await apiUpdateTask(parseId(id), {
        title: input.title,
        description: input.description ?? '',
        projectId: parseId(input.projectId),
        sprintId: input.sprintId ? parseId(input.sprintId) : null,
        status: input.status,
        priority: input.priority,
        storyPoints: input.storyPoints,
        assigneeId: input.assigneeId ? parseId(input.assigneeId) : null,
        labels: input.labels ?? existing.labels,
        blocked: input.blocked ?? existing.blocked,
      })
      const task = { ...mapTask(updated), aiGenerated: existing.aiGenerated }
      set((state) => ({ tasks: state.tasks.map((t) => (t.id === id ? task : t)), syncError: null }))
      return { ok: true }
    } catch (err) {
      return { ok: false, error: apiErrorMessage(err, 'Failed to update task') }
    }
  },

  deleteTask: async (id) => {
    const previous = get().tasks
    // Optimistic like updateTaskStatus, with rollback if the server refuses.
    set({ tasks: previous.filter((t) => t.id !== id), syncError: null })
    try {
      await apiDeleteTask(parseId(id))
      return { ok: true }
    } catch (err) {
      set({ tasks: previous, syncError: apiErrorMessage(err, 'Failed to delete task') })
      return { ok: false, error: apiErrorMessage(err, 'Failed to delete task') }
    }
  },

  updateTaskStatus: (id, status) => {
    // Optimistic update with rollback on failure.
    const previous = get().tasks
    set({
      tasks: previous.map((t) => (t.id === id ? { ...t, status } : t)),
      syncError: null,
    })
    apiUpdateTaskStatus(parseId(id), status).catch((err) => {
      set({ tasks: previous, syncError: apiErrorMessage(err, 'Failed to update task') })
    })
  },
})
