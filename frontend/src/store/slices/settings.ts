import type { StateCreator } from 'zustand'
import { apiErrorMessage, apiUpdateMe, apiUpdateUserRole, type ApiUser } from '../../lib/api'
import type { ProfileResult, RoleChangeResult } from '../contracts'
import { parseId, toUserId } from '../ids'
import { initialsOf } from '../mappers'
import type { NotificationCategory, Settings } from '../models'
import type { AppState } from '../state'

export interface SettingsSlice {
  settings: Settings

  // Settings (profile is API-backed; the rest is local until Phase 4)
  updateSettings: (input: Partial<Pick<Settings, 'displayName' | 'role' | 'defaultAssigneeId'>>) => void
  saveProfile: (name: string) => Promise<ProfileResult>
  changeUserRole: (memberId: string, role: ApiUser['role']) => Promise<RoleChangeResult>
  toggleMutedCategory: (category: NotificationCategory) => void
  resetWorkspace: () => void
}

export const createSettingsSlice: StateCreator<AppState, [], [], SettingsSlice> = (set, get) => ({
  settings: {
    displayName: '',
    role: '',
    defaultAssigneeId: '',
    mutedCategories: [],
  },

  updateSettings: (input) => {
    set((state) => ({ settings: { ...state.settings, ...input } }))
  },

  saveProfile: async (name) => {
    try {
      const updated = await apiUpdateMe(name)
      set((state) => ({
        currentUser: state.currentUser ? { ...state.currentUser, name: updated.name } : updated,
        members: state.members.map((m) =>
          m.id === toUserId(updated.id) ? { ...m, name: updated.name, initials: initialsOf(updated.name) } : m,
        ),
        settings: { ...state.settings, displayName: updated.name },
      }))
      return { ok: true }
    } catch (err) {
      return { ok: false, error: apiErrorMessage(err, 'Failed to update profile') }
    }
  },

  changeUserRole: async (memberId, role) => {
    try {
      const updated = await apiUpdateUserRole(parseId(memberId), role)
      set((state) => ({
        members: state.members.map((m) => (m.id === toUserId(updated.id) ? { ...m, role: updated.role } : m)),
        currentUser:
          state.currentUser && state.currentUser.id === updated.id
            ? { ...state.currentUser, role: updated.role }
            : state.currentUser,
        userRole:
          state.currentUser && state.currentUser.id === updated.id ? updated.role : state.userRole,
      }))
      return { ok: true }
    } catch (err) {
      return { ok: false, error: apiErrorMessage(err, 'Failed to change role') }
    }
  },

  toggleMutedCategory: (category) => {
    set((state) => ({
      settings: {
        ...state.settings,
        mutedCategories: state.settings.mutedCategories.includes(category)
          ? state.settings.mutedCategories.filter((c) => c !== category)
          : [...state.settings.mutedCategories, category],
      },
    }))
  },

  resetWorkspace: () => {
    void get().loadWorkspace()
  },
})
