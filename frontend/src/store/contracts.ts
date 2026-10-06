import type { ApiProjectStatus } from '../lib/api'
import type { SprintStatus, TaskPriority, TaskStatus } from '../lib/mockData'

// ---------------------------------------------------------------------------
// Action contracts — the argument and result shapes the store actions accept.
// Kept apart from the models so pages can import a type without dragging in
// the store implementation.
// ---------------------------------------------------------------------------

export interface NewProjectInput {
  name: string
  description: string
}

export interface NewTaskInput {
  title: string
  projectId: string
  sprintId?: string
  status: TaskStatus
  priority: TaskPriority
  storyPoints: number
  assigneeId: string
}

export interface TaskEditInput {
  title: string
  description?: string
  projectId: string
  sprintId?: string
  status: TaskStatus
  priority: TaskPriority
  storyPoints: number
  assigneeId: string
  labels?: string[]
  /** Undefined leaves the current value alone. */
  blocked?: boolean
}

export interface TaskResult {
  ok: boolean
  error?: string
}

/** Fields editable on an existing project. projectId itself never changes. */
export interface ProjectEditInput {
  name: string
  description?: string | null
  status?: ApiProjectStatus
}

/**
 * Fields editable on an existing sprint. projectId is sent but must match the
 * sprint's current project — the backend refuses to move a sprint, because that
 * would orphan its tasks and renumber the target project's sequence.
 */
export interface SprintEditInput {
  projectId: string
  goal: string
  startDate: string
  endDate: string
  committedPoints?: number
  status?: SprintStatus
}

export interface NewSprintInput {
  projectId: string
  goal: string
  startDate: string
  endDate: string
  committedPoints: number
}

export interface NewMemberInput {
  name: string
  role: string
}

export interface AuthResult {
  ok: boolean
  error?: string
}

export interface ProfileResult {
  ok: boolean
  error?: string
}

export interface RoleChangeResult {
  ok: boolean
  error?: string
}

export interface NewWikiPageInput {
  title: string
  content: string
  projectId?: string
}

export interface WikiResult {
  ok: boolean
  error?: string
}
