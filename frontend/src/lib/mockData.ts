export type TaskStatus =
  | 'BACKLOG'
  | 'TODO'
  | 'IN_PROGRESS'
  | 'IN_REVIEW'
  | 'TESTING'
  | 'DONE'

export type TaskPriority = 'LOW' | 'MEDIUM' | 'HIGH' | 'URGENT'

export interface Member {
  id: string
  name: string
  initials: string
  role: string
  utilization: number
  /** Human-facing code from the backend (NX-0007), when it has one. */
  employeeCode?: string
}

export interface Task {
  id: string
  projectId: string
  sprintId?: string
  title: string
  description?: string
  status: TaskStatus
  priority: TaskPriority
  storyPoints: number
  assigneeId: string
  labels: string[]
  aiGenerated?: boolean
  blocked?: boolean
  /** ISO timestamps from the server, used for real activity feeds. */
  createdAt: string
  updatedAt: string
}

export type SprintStatus = 'PLANNED' | 'ACTIVE' | 'COMPLETED'

export interface Sprint {
  id: string
  projectId: string
  number: number
  goal: string
  startDate: string
  endDate: string
  committedPoints: number
  status: SprintStatus
}

export interface Project {
  id: string
  name: string
  description: string
  status: 'PLANNING' | 'ACTIVE' | 'ON_HOLD' | 'COMPLETED' | 'ARCHIVED'
  health: 'ON_TRACK' | 'AT_RISK' | 'OFF_TRACK'
  progress: number
  sprintNumber: number
  memberCount: number
}

export function memberById(members: Member[], id: string): Member | undefined {
  return members.find((m) => m.id === id)
}
