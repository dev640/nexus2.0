import { create } from 'zustand'
import { createAlertsSlice } from './slices/alerts'
import { createAuthSlice } from './slices/auth'
import { createMembersSlice } from './slices/members'
import { createMessagesSlice } from './slices/messages'
import { createNotificationsSlice } from './slices/notifications'
import { createProjectsSlice } from './slices/projects'
import { createSettingsSlice } from './slices/settings'
import { createSprintsSlice } from './slices/sprints'
import { createSyncSlice } from './slices/sync'
import { createTasksSlice } from './slices/tasks'
import { createTeamsSlice } from './slices/teams'
import { createWhiteboardSlice } from './slices/whiteboard'
import { createWikiSlice } from './slices/wiki'
import type { AppState } from './state'

/**
 * One workspace store, assembled from a slice per domain (see ./state.ts for
 * the shape). Pages still import everything they need from here; the slices are
 * an implementation detail and are imported directly only by this file and the
 * store's own tests.
 */
export const useAppStore = create<AppState>()((set, get, store) => ({
  ...createAuthSlice(set, get, store),
  ...createAlertsSlice(set, get, store),
  ...createSyncSlice(set, get, store),
  ...createTasksSlice(set, get, store),
  ...createProjectsSlice(set, get, store),
  ...createSprintsSlice(set, get, store),
  ...createMembersSlice(set, get, store),
  ...createWhiteboardSlice(set, get, store),
  ...createTeamsSlice(set, get, store),
  ...createWikiSlice(set, get, store),
  ...createNotificationsSlice(set, get, store),
  ...createMessagesSlice(set, get, store),
  ...createSettingsSlice(set, get, store),
}))

export { numericUserId, toUserId } from './ids'
export { noteColors, timeAgo } from './models'
export type {
  AppAlert,
  MailMessage,
  NoteColor,
  Notification,
  NotificationCategory,
  Settings,
  StickyNote,
  Team,
  UserRole,
  WikiPage,
} from './models'
export type {
  AuthResult,
  MessageResult,
  NewMessageInput,
  ProjectEditInput,
  ProfileResult,
  RoleChangeResult,
  SprintEditInput,
  TaskResult,
  WikiResult,
} from './contracts'
export type { AppState }
