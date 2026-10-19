import type { StateCreator } from 'zustand'
import {
  api,
  apiChangePassword,
  apiErrorMessage,
  apiLogin,
  getStoredToken,
  registerPasswordChangeRequiredHandler,
  storeToken,
  type ApiUser,
} from '../../lib/api'
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
  changePassword: (currentPassword: string, newPassword: string) => Promise<AuthResult>
}

export const createAuthSlice: StateCreator<AppState, [], [], AuthSlice> = (set, get) => {
  // The server refuses every workspace request from an account that still holds
  // an admin-issued password. Its profile carries the flag that draws the
  // forced-change screen, so a refusal means this client's copy was stale: the
  // fix is to read the profile again, from an endpoint the gate allows.
  registerPasswordChangeRequiredHandler(() => {
    void api
      .get<ApiUser>('/users/me')
      .then(({ data }) => get().applyCurrentUser(data))
      // Nothing to do on failure: the request that hit the gate already failed.
      .catch(() => undefined)
  })

  /**
   * Applies a profile the server just accepted as this session's, then fills the
   * workspace from it.
   *
   * A gated account cannot reach workspace endpoints, and a SUPER_ADMIN has no
   * tenant scope. Neither should trigger tenant workspace requests on sign-in.
   */
  const enterWorkspace = async (user: ApiUser): Promise<void> => {
    get().applyCurrentUser(user)
    if (user.mustChangePassword === true || user.role === 'SUPER_ADMIN') return
    await get().loadWorkspace()
  }

  return {
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
          await enterWorkspace(data)
          return { ok: true }
        }
        const auth = await apiLogin(email, password)
        storeToken(auth.token)
        await enterWorkspace(auth.user)
        return { ok: true }
      } catch (err) {
        return { ok: false, error: apiErrorMessage(err, 'Login failed') }
      }
    },

    /**
     * Replaces the caller's own password. On success the profile is read back
     * rather than patched locally, so the gate lifts from the same value that
     * raised it and the client cannot end up believing itself ungated while the
     * server still refuses it.
     *
     * <p>Under Supabase the account's own sessions end with its password, so the
     * session this browser holds is not something to keep using; signing out now
     * is what turns a later mystery 401 into a sign-in prompt at the right
     * moment.
     */
    changePassword: async (currentPassword, newPassword) => {
      try {
        const changed = await apiChangePassword(currentPassword, newPassword)
        if (supabaseEnabled && supabase) {
          get().logout()
          return { ok: true }
        }
        if (!changed) throw new Error('The server did not return a renewed session')
        storeToken(changed.token)
        // The response carries the new token and cleared profile because the old
        // token was invalidated in the same transaction as the password change.
        await enterWorkspace(changed.user)
        return { ok: true }
      } catch (err) {
        return { ok: false, error: apiErrorMessage(err, 'Could not change your password') }
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
          await enterWorkspace(me.data)
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
      // A gated account reloads straight onto the forced-change screen: every
      // workspace endpoint would refuse it, so the workspace is not asked for.
      let gated = false
      let superAdmin = false
      try {
        // Restore the session profile on reload — loadWorkspace only fills the
        // workspace collections, leaving currentUser null (used by the sidebar,
        // profile page and unread badge).
        const me = await api.get<ApiUser>('/users/me')
        get().applyCurrentUser(me.data)
        gated = me.data.mustChangePassword === true
        superAdmin = me.data.role === 'SUPER_ADMIN'
      } catch {
        // Stale/invalid token: loadWorkspace below surfaces the failure as usual.
      }
      if (!gated && !superAdmin) await get().loadWorkspace()
    },
  }
}
