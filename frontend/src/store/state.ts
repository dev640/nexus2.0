import type { AlertsSlice } from './slices/alerts'
import type { AuthSlice } from './slices/auth'
import type { MembersSlice } from './slices/members'
import type { MessagesSlice } from './slices/messages'
import type { NotificationsSlice } from './slices/notifications'
import type { ProjectsSlice } from './slices/projects'
import type { SettingsSlice } from './slices/settings'
import type { SprintsSlice } from './slices/sprints'
import type { SyncSlice } from './slices/sync'
import type { TasksSlice } from './slices/tasks'
import type { TeamsSlice } from './slices/teams'
import type { WhiteboardSlice } from './slices/whiteboard'
import type { WikiSlice } from './slices/wiki'

/**
 * The store is composed from slices, each owning one area of the workspace.
 * This intersection is the single source of truth for what the store holds, so
 * a new action is added in one place and picked up everywhere.
 *
 * The imports above are `import type` on purpose: the slices import AppState
 * back to type their creators, and type-only imports are erased at build time,
 * which keeps that relationship from becoming a runtime import cycle.
 */
export type AppState = AuthSlice &
  AlertsSlice &
  SyncSlice &
  TasksSlice &
  ProjectsSlice &
  SprintsSlice &
  MembersSlice &
  WhiteboardSlice &
  TeamsSlice &
  WikiSlice &
  NotificationsSlice &
  MessagesSlice &
  SettingsSlice
