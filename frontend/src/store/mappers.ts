import type { ApiTask, ApiUser } from '../lib/api'
import type { Member, Task } from '../lib/mockData'
import { toProjectId, toSprintId, toTaskId, toUserId } from './ids'
import type { NoteColor, StickyNote } from './models'

export function initialsOf(name: string): string {
  return name
    .trim()
    .split(/\s+/)
    .map((part) => part[0])
    .slice(0, 2)
    .join('')
    .toUpperCase()
}

export function mapUser(u: ApiUser): Member {
  return {
    id: toUserId(u.id),
    name: u.name,
    initials: initialsOf(u.name),
    role: u.role,
    utilization: 0,
    employeeCode: u.employeeCode ?? undefined,
  }
}

/** Single place where an API task becomes the shape the UI renders. */
export function mapTask(t: ApiTask): Task {
  return {
    id: toTaskId(t.id),
    projectId: toProjectId(t.projectId),
    sprintId: t.sprintId != null ? toSprintId(t.sprintId) : undefined,
    title: t.title,
    description: t.description ?? '',
    status: t.status,
    priority: t.priority,
    storyPoints: t.storyPoints ?? 0,
    assigneeId: t.assignee ? toUserId(t.assignee.id) : '',
    labels: t.labels ?? [],
    blocked: t.blocked ?? false,
    createdAt: t.createdAt,
    updatedAt: t.updatedAt,
  }
}

export function mapNote(n: {
  id: number
  text: string
  color: string
  x: number
  y: number
  author: string | null
}): StickyNote {
  return {
    id: `n-${n.id}`,
    text: n.text ?? '',
    color: n.color as NoteColor,
    x: Math.round(n.x),
    y: Math.round(n.y),
    author: n.author ?? '',
  }
}
