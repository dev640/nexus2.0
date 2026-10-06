import type { StateCreator } from 'zustand'
import {
  apiCreateWhiteboardNote,
  apiDeleteWhiteboardNote,
  apiErrorMessage,
  apiListWhiteboardNotes,
  apiUpdateWhiteboardNote,
} from '../../lib/api'
import { connectWhiteboardSocket, type WhiteboardEventPayload } from '../../lib/whiteboardSocket'
import { parseId } from '../ids'
import { mapNote } from '../mappers'
import type { NoteColor, StickyNote } from '../models'
import type { AppState } from '../state'

export interface WhiteboardSlice {
  stickyNotes: StickyNote[]
  whiteboardConnected: boolean

  // Whiteboard (API-backed + live socket since Phase 6)
  loadStickyNotes: () => Promise<void>
  connectWhiteboard: () => void
  disconnectWhiteboard: () => void
  addStickyNote: (color: NoteColor) => Promise<StickyNote | null>
  updateStickyNoteText: (id: string, text: string) => void
  saveStickyNoteText: (id: string) => void
  moveStickyNote: (id: string, x: number, y: number) => void
  saveStickyNotePosition: (id: string) => void
  deleteStickyNote: (id: string) => Promise<void>
  applyWhiteboardEvent: (event: WhiteboardEventPayload) => void
}

/** Live whiteboard socket handle (module-level: not part of rendered state). */
let whiteboardDisconnect: (() => void) | null = null

export const createWhiteboardSlice: StateCreator<AppState, [], [], WhiteboardSlice> = (set, get) => ({
  stickyNotes: [],
  whiteboardConnected: false,

  loadStickyNotes: async () => {
    try {
      const notes = await apiListWhiteboardNotes()
      set({ stickyNotes: notes.map(mapNote) })
    } catch (err) {
      set({ syncError: apiErrorMessage(err, 'Failed to load whiteboard') })
    }
  },

  connectWhiteboard: () => {
    if (whiteboardDisconnect) return
    whiteboardDisconnect = connectWhiteboardSocket(
      (event) => get().applyWhiteboardEvent(event),
      (connected) => set({ whiteboardConnected: connected }),
    )
  },

  disconnectWhiteboard: () => {
    whiteboardDisconnect?.()
    whiteboardDisconnect = null
    set({ whiteboardConnected: false })
  },

  applyWhiteboardEvent: (event) => {
    const note = mapNote(event.note)
    set((state) => {
      if (event.type === 'deleted') {
        return { stickyNotes: state.stickyNotes.filter((n) => n.id !== note.id) }
      }
      const exists = state.stickyNotes.some((n) => n.id === note.id)
      return {
        stickyNotes: exists
          ? state.stickyNotes.map((n) => (n.id === note.id ? { ...note, text: note.text || n.text } : n))
          : [...state.stickyNotes, note],
      }
    })
  },

  addStickyNote: async (color) => {
    try {
      const created = await apiCreateWhiteboardNote(
        color,
        40 + Math.round(Math.random() * 120),
        40 + Math.round(Math.random() * 80),
      )
      const note = mapNote(created)
      set((state) =>
        state.stickyNotes.some((n) => n.id === note.id)
          ? state
          : { stickyNotes: [...state.stickyNotes, note] },
      )
      return note
    } catch (err) {
      set({ syncError: apiErrorMessage(err, 'Failed to add note') })
      return null
    }
  },

  updateStickyNoteText: (id, text) => {
    set((state) => ({
      stickyNotes: state.stickyNotes.map((n) => (n.id === id ? { ...n, text } : n)),
    }))
  },

  saveStickyNoteText: (id) => {
    const note = get().stickyNotes.find((n) => n.id === id)
    if (!note) return
    apiUpdateWhiteboardNote(parseId(id), { text: note.text }).catch((err) => {
      set({ syncError: apiErrorMessage(err, 'Failed to save note') })
    })
  },

  moveStickyNote: (id, x, y) => {
    set((state) => ({
      stickyNotes: state.stickyNotes.map((n) => (n.id === id ? { ...n, x, y } : n)),
    }))
  },

  saveStickyNotePosition: (id) => {
    const note = get().stickyNotes.find((n) => n.id === id)
    if (!note) return
    apiUpdateWhiteboardNote(parseId(id), { x: note.x, y: note.y }).catch((err) => {
      set({ syncError: apiErrorMessage(err, 'Failed to save note position') })
    })
  },

  deleteStickyNote: async (id) => {
    const previous = get().stickyNotes
    set({ stickyNotes: previous.filter((n) => n.id !== id) })
    try {
      await apiDeleteWhiteboardNote(parseId(id))
    } catch (err) {
      set({ stickyNotes: previous, syncError: apiErrorMessage(err, 'Failed to delete note') })
    }
  },
})
