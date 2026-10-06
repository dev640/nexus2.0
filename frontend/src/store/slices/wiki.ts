import type { StateCreator } from 'zustand'
import {
  apiCreateWikiPage,
  apiDeleteWikiPage,
  apiErrorMessage,
  apiListWikiPages,
  apiUpdateWikiPage,
} from '../../lib/api'
import type { NewWikiPageInput, WikiResult } from '../contracts'
import { parseId, toProjectId } from '../ids'
import type { WikiPage } from '../models'
import type { AppState } from '../state'

export interface WikiSlice {
  wikiPages: WikiPage[]

  // Wiki (API-backed since Phase 3)
  loadWikiPages: () => Promise<void>
  addWikiPage: (input: NewWikiPageInput) => Promise<WikiPage | null>
  updateWikiPage: (id: string, input: { title: string; content: string }) => Promise<WikiResult>
  deleteWikiPage: (id: string) => Promise<WikiResult>
}

export const createWikiSlice: StateCreator<AppState, [], [], WikiSlice> = (set) => ({
  wikiPages: [],

  loadWikiPages: async () => {
    try {
      const pages = await apiListWikiPages()
      set({
        wikiPages: pages.map((p) => ({
          id: `w-${p.id}`,
          title: p.title,
          content: p.content ?? '',
          projectId: p.projectId != null ? toProjectId(p.projectId) : undefined,
          author: p.author ?? '',
          updatedAt: p.updatedAt,
        })),
      })
    } catch (err) {
      set({ syncError: apiErrorMessage(err, 'Failed to load wiki') })
    }
  },

  addWikiPage: async (input) => {
    try {
      const created = await apiCreateWikiPage({
        title: input.title,
        content: input.content,
        projectId: input.projectId ? parseId(input.projectId) : null,
      })
      const page: WikiPage = {
        id: `w-${created.id}`,
        title: created.title,
        content: created.content ?? '',
        projectId: created.projectId != null ? toProjectId(created.projectId) : undefined,
        author: created.author ?? '',
        updatedAt: created.updatedAt,
      }
      set((state) => ({ wikiPages: [...state.wikiPages, page] }))
      return page
    } catch (err) {
      set({ syncError: apiErrorMessage(err, 'Failed to create page') })
      return null
    }
  },

  updateWikiPage: async (id, input) => {
    try {
      const updated = await apiUpdateWikiPage(parseId(id), {
        title: input.title,
        content: input.content,
      })
      set((state) => ({
        wikiPages: state.wikiPages.map((p) =>
          p.id === id
            ? { ...p, title: updated.title, content: updated.content ?? '', updatedAt: updated.updatedAt }
            : p,
        ),
      }))
      return { ok: true }
    } catch (err) {
      return { ok: false, error: apiErrorMessage(err, 'Failed to save page') }
    }
  },

  deleteWikiPage: async (id) => {
    try {
      await apiDeleteWikiPage(parseId(id))
      set((state) => ({ wikiPages: state.wikiPages.filter((p) => p.id !== id) }))
      return { ok: true }
    } catch (err) {
      return { ok: false, error: apiErrorMessage(err, 'Failed to delete page') }
    }
  },
})
