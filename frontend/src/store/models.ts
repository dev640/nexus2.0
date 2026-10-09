import type { ApiUser } from '../lib/api'

// ---------------------------------------------------------------------------
// Local-only models (replaced by backend domains in later phases)
// ---------------------------------------------------------------------------

export interface Team {
  id: string
  name: string
  memberIds: string[]
  projectIds: string[]
}

export const seedTeams: Team[] = [
  {
    id: 'core-engineering',
    name: 'Core Engineering',
    memberIds: ['u-1', 'u-2', 'u-3', 'u-4'],
    projectIds: ['p-1'],
  },
]

export interface WikiPage {
  id: string
  title: string
  content: string
  projectId?: string
  author: string
  updatedAt: string
}

export const noteColors = ['#fff2a8', '#ffd6d6', '#d6ffe0', '#d6e8ff', '#ecd6ff'] as const
export type NoteColor = (typeof noteColors)[number]

export interface StickyNote {
  id: string
  text: string
  color: NoteColor
  x: number
  y: number
  author: string
}

export type NotificationCategory = 'MENTIONS' | 'TASKS' | 'PROJECTS' | 'AI' | 'SYSTEM'

export interface Notification {
  id: number
  category: NotificationCategory
  text: string
  /** Page this notification is about, as the API supplies it. */
  link: string | null
  time: string
  read: boolean
  archived: boolean
}

/** A transient in-app alert, shown when no system popup can be displayed. */
export interface AppAlert {
  id: number
  title: string
  body: string
  /** Where clicking the alert goes; null makes the toast purely informational. */
  link: string | null
}

/**
 * A message one member wrote to another, as the Inbox renders it. `senderId`
 * and `recipientId` use the store's `u-<id>` member form so rows can be matched
 * against the member list.
 */
export interface MailMessage {
  id: number
  senderId: string
  senderName: string
  recipientId: string
  recipientName: string
  subject: string
  body: string
  read: boolean
  /** Written to the whole workspace, rather than to one person. */
  broadcast: boolean
  time: string
}

export function timeAgo(iso: string): string {
  const seconds = Math.max(1, Math.floor((Date.now() - new Date(iso).getTime()) / 1000))
  if (seconds < 60) return 'just now'
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes}m ago`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}h ago`
  return `${Math.floor(hours / 24)}d ago`
}

export interface Settings {
  displayName: string
  role: string
  defaultAssigneeId: string
  mutedCategories: NotificationCategory[]
}

export type UserRole = ApiUser['role']
