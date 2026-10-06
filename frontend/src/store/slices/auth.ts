import type { StateCreator } from 'zustand'
import { api, apiErrorMessage, apiLogin, getStoredToken, storeToken, type ApiUser } from '../../lib/api'
import { supabase, supabaseEnabled, supabaseSignIn, supabaseSignOut } from '../../lib/supabase'
import { clearAvatarCache } from '../../hooks/useAvatar'
import type { AuthResult } from '../contracts'
import type { UserRole } from '../models'
import type { AppState } from '../state'

export interface AuthSlice {
  // Auth / session state
  isAuthenticated: boolean
  isBootstrapped: boolean
  currentUser: ApiUser | null
  userRole: UserRole

  // Auth
  login: (email: string, password: string) => Promise<AuthResult>
  logout: () => void
  bootstrapFromStoredToken: () => Promise<void>
  applyCurrentUser: (user: ApiUser) => void
}

export const createAuthSlice: StateCreator<AppState, [], [], AuthSlice> = (set, get) => ({
  isAuthenticated: Boolean(getStoredToken()),
  isBootstrapped: false,
  currentUser: null,
  userRole: 'MEMBER',

  /** Applies a freshly fetched profile to the session state. */
  applyCurrentUser: (user: ApiUser) => {
    set({
      isAuthenticated: true,
      currentUser: user,
      userRole: user.role,
      settings: { ...get().settings, displayName: user.name, role: user.role },
    })
  },

  login: async (email, password) => {
    try {
      if (supabaseEnabled && supabase) {
        const result = await supabaseSignIn(email, password)
        if (!result.ok) return { ok: false, error: result.error }
        // The Supabase JWT is accepted by the backend's auth filter, which
        // resolves (or auto-provisions) the matching local account.
        const { data } = await api.get<ApiUser>('/users/me')
        get().applyCurrentUser(data)
        await get().loadWorkspace()
        return { ok: true }
      }
      const auth = await apiLogin(email, password)
      storeToken(auth.token)
      get().applyCurrentUser(auth.user)
      await get().loadWorkspace()
      return { ok: true }
    } catch (err) {
      return { ok: false, error: apiErrorMessage(err, 'Login failed') }
    }
  },

  logout: () => {
    void supabaseSignOut()
    storeToken(null)
    // Avatar bytes are held as object URLs in a module-level cache; leaving them
    // behind would keep the previous user's picture on screen after sign-in.
    clearAvatarCache()
    api.defaults.headers.common['Authorization'] = undefined
    set({
      isAuthenticated: false,
      currentUser: null,
      userRole: 'MEMBER',
      // Signing out leaves a *resolved* anonymous session. The bootstrap effect
      // in App.tsx runs once on mount, so an unresolved flag here renders nothing
      // for the rest of the session with no way back.
      isBootstrapped: true,
      projects: [],
      tasks: [],
      sprints: [],
      members: [],
      syncError: null,
    })
  },

  bootstrapFromStoredToken: async () => {
    if (get().isBootstrapped) return
    if (supabaseEnabled && supabase) {
      const { data } = await supabase.auth.getSession()
      if (!data.session) {
        // No session is a valid answer, and the answer is final: mark it
        // resolved so App.tsx renders the public routes. Leaving the flag unset
        // made every browser without a stored session render a blank page.
        set({ isAuthenticated: false, isBootstrapped: true })
        return
      }
      try {
        const me = await api.get<ApiUser>('/users/me')
        get().applyCurrentUser(me.data)
        await get().loadWorkspace()
      } catch {
        // Token not accepted (backend Supabase not configured, user deleted…):
        // sign out so the login screen shows.
        await supabaseSignOut()
        set({ isAuthenticated: false, isBootstrapped: true })
      }
      return
    }
    if (!getStoredToken()) {
      // Same reasoning as above, for the built-in auth path.
      set({ isBootstrapped: true })
      return
    }
    try {
      // Restore the session profile on reload — loadWorkspace only fills the
      // workspace collections, leaving currentUser null (used by the sidebar,
      // profile page and unread badge).
      const me = await api.get<ApiUser>('/users/me')
      get().applyCurrentUser(me.data)
    } catch {
      // Stale/invalid token: loadWorkspace below surfaces the failure as usual.
    }
    await get().loadWorkspace()
  },
})
