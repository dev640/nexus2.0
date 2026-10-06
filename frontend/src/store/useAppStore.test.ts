import { beforeEach, describe, expect, it } from 'vitest'
import { useAppStore } from './useAppStore'

/**
 * The store was split into slices; this pins the wiring.
 *
 * ActionKeys is derived from the store's own type, so adding or removing an
 * action in AppState without updating `actions` below is a compile error, and
 * a slice that silently fails to make it into the composed store is a test
 * failure. Together they catch the mistake a slice split invites: forgetting
 * to spread one creator.
 */
type State = ReturnType<typeof useAppStore.getState>
type ActionKeys = {
  [K in keyof State]-?: State[K] extends (...args: never[]) => unknown ? K : never
}[keyof State]

const actions: Record<ActionKeys, true> = {
  // auth / sync
  login: true,
  logout: true,
  bootstrapFromStoredToken: true,
  applyCurrentUser: true,
  loadWorkspace: true,
  // tasks
  addTask: true,
  updateTaskStatus: true,
  updateTask: true,
  deleteTask: true,
  // projects
  addProject: true,
  updateProject: true,
  deleteProject: true,
  // sprints
  addSprint: true,
  setSprintStatus: true,
  updateSprint: true,
  deleteSprint: true,
  // members
  removeMember: true,
  // whiteboard
  loadStickyNotes: true,
  connectWhiteboard: true,
  disconnectWhiteboard: true,
  addStickyNote: true,
  updateStickyNoteText: true,
  saveStickyNoteText: true,
  moveStickyNote: true,
  saveStickyNotePosition: true,
  deleteStickyNote: true,
  applyWhiteboardEvent: true,
  // teams
  addMember: true,
  addTeam: true,
  deleteTeam: true,
  addMemberToTeam: true,
  removeMemberFromTeam: true,
  assignProjectToTeam: true,
  unassignProjectFromTeam: true,
  // wiki
  loadWikiPages: true,
  addWikiPage: true,
  updateWikiPage: true,
  deleteWikiPage: true,
  // notifications
  loadNotifications: true,
  markNotificationRead: true,
  archiveNotification: true,
  markAllNotificationsRead: true,
  // settings / profile
  updateSettings: true,
  saveProfile: true,
  changeUserRole: true,
  toggleMutedCategory: true,
  resetWorkspace: true,
}

/**
 * The state the store was created with, captured before any test mutates the
 * singleton. Resetting between tests is necessary, but it must not be mistaken
 * for evidence of what the store's creators declared.
 */
const initialSessionFlags = (() => {
  const s = useAppStore.getState()
  return {
    isAuthenticated: s.isAuthenticated,
    isBootstrapped: s.isBootstrapped,
    isLoading: s.isLoading,
    syncError: s.syncError,
    currentUser: s.currentUser,
    userRole: s.userRole,
  }
})()

describe('useAppStore', () => {
  beforeEach(() => {
    // Each test starts from the same place a browser does on first load: no
    // session, no stored token, nothing bootstrapped yet.
    useAppStore.setState({
      isAuthenticated: false,
      isBootstrapped: false,
      isLoading: false,
      syncError: null,
      currentUser: null,
      userRole: 'MEMBER',
    })
  })

  it('exposes every declared action', () => {
    const state = useAppStore.getState() as unknown as Record<string, unknown>
    for (const key of Object.keys(actions)) {
      expect(typeof state[key], `action "${key}" is missing`).toBe('function')
    }
  })

  it('starts with the seeded data and a signed-out session', () => {
    // Values each slice owns, so a slice losing its initial state (or never
    // being spread into the composed store) shows up here.
    const state = useAppStore.getState()
    // The session flags are asserted against the state the store was *created*
    // with, not the state beforeEach just wrote — otherwise these would only be
    // proving that setState works.
    expect(initialSessionFlags).toEqual({
      isAuthenticated: false,
      isBootstrapped: false,
      isLoading: false,
      syncError: null,
      currentUser: null,
      userRole: 'MEMBER',
    })
    expect(state.whiteboardConnected).toBe(false)
    expect(state.projects).toEqual([])
    expect(state.tasks).toEqual([])
    expect(state.sprints).toEqual([])
    expect(state.members).toEqual([])
    expect(state.stickyNotes).toEqual([])
    expect(state.notifications).toEqual([])
    expect(state.wikiPages).toEqual([])
    expect(state.teams.map((t) => t.id)).toEqual(['core-engineering'])
    expect(state.settings).toEqual({
      displayName: '',
      role: '',
      defaultAssigneeId: '',
      mutedCategories: [],
    })
  })

  it('resolves the session on first load so an anonymous visitor gets the login screen', async () => {
    // App.tsx renders nothing until isBootstrapped is true. A browser with no
    // stored session used to leave it false forever, which blanked the whole
    // page — the site only worked in a browser that already had a session.
    await useAppStore.getState().bootstrapFromStoredToken()
    const state = useAppStore.getState()
    expect(state.isBootstrapped).toBe(true)
    expect(state.isAuthenticated).toBe(false)
  })

  it('keeps the app renderable after signing out', () => {
    // Signing out is a resolved anonymous session, not an unresolved one: the
    // bootstrap effect only runs on mount, so leaving isBootstrapped false here
    // blanked the page with no way to recover.
    useAppStore.setState({ isAuthenticated: true, isBootstrapped: true })
    useAppStore.getState().logout()
    const state = useAppStore.getState()
    expect(state.isBootstrapped).toBe(true)
    expect(state.isAuthenticated).toBe(false)
    expect(state.currentUser).toBeNull()
  })

  it('updates one settings field without clobbering the others', () => {
    useAppStore.setState({
      settings: {
        displayName: 'Devendra',
        role: 'ADMIN',
        defaultAssigneeId: '',
        mutedCategories: ['AI'],
      },
    })
    useAppStore.getState().updateSettings({ displayName: 'Devendra N' })
    const settings = useAppStore.getState().settings
    expect(settings.displayName).toBe('Devendra N')
    expect(settings.role).toBe('ADMIN')
    expect(settings.mutedCategories).toEqual(['AI'])
  })
})
