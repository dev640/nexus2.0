import type { StateCreator } from 'zustand'
import { apiErrorMessage, apiListProjects, apiListSprints, apiListTasks, apiListUsers } from '../../lib/api'
import { toProjectId, toSprintId } from '../ids'
import { mapTask, mapUser } from '../mappers'
import type { AppState } from '../state'

export interface SyncSlice {
  isLoading: boolean
  syncError: string | null

  // Sync
  loadWorkspace: () => Promise<void>
}

export const createSyncSlice: StateCreator<AppState, [], [], SyncSlice> = (set, get) => ({
  isLoading: false,
  syncError: null,

  loadWorkspace: async () => {
    set({ isLoading: true, syncError: null })
    try {
      const [users, projects, sprints, tasks] = await Promise.all([
        apiListUsers(),
        apiListProjects(),
        apiListSprints(),
        apiListTasks(),
      ])

      void get().loadWikiPages()
      void get().loadNotifications()
      void get().loadMessages()

      const members = users.map(mapUser)
      // Fall back to the signed-in user's own profile, never to a teammate:
      // members[0] is whoever the API listed first, so the greeting could
      // greet someone with another member's name.
      const displayName = get().settings.displayName || get().currentUser?.name || ''

      set({
        members,
        projects: projects.map((p) => ({
          id: toProjectId(p.id),
          name: p.name,
          description: p.description ?? '',
          status: p.status,
          health: p.health,
          progress: p.progress,
          sprintNumber: p.sprintNumber,
          memberCount: p.memberCount,
        })),
        sprints: sprints.map((s) => ({
          id: toSprintId(s.id),
          projectId: toProjectId(s.projectId),
          number: s.number,
          goal: s.goal,
          startDate: s.startDate,
          endDate: s.endDate,
          committedPoints: s.committedPoints,
          status: s.status,
        })),
        tasks: tasks.map(mapTask),
        settings: { ...get().settings, displayName },
        isBootstrapped: true,
        isLoading: false,
      })
    } catch (err) {
      set({ isLoading: false, isBootstrapped: true, syncError: apiErrorMessage(err, 'Failed to load workspace') })
    }
  },
})
