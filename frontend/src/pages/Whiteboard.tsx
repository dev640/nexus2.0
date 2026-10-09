import { useEffect, useRef, useState } from 'react'
import { useDeepLink } from '../hooks/useDeepLink'
import { useAppStore, noteColors, type NoteColor } from '../store/useAppStore'

/** How long an alert's note stays outlined after the board opens it. */
const HIGHLIGHT_MS = 2500

export function Whiteboard() {
  const stickyNotes = useAppStore((s) => s.stickyNotes)
  const addStickyNote = useAppStore((s) => s.addStickyNote)
  const updateStickyNoteText = useAppStore((s) => s.updateStickyNoteText)
  const saveStickyNoteText = useAppStore((s) => s.saveStickyNoteText)
  const moveStickyNote = useAppStore((s) => s.moveStickyNote)
  const saveStickyNotePosition = useAppStore((s) => s.saveStickyNotePosition)
  const deleteStickyNote = useAppStore((s) => s.deleteStickyNote)
  const loadStickyNotes = useAppStore((s) => s.loadStickyNotes)
  const connectWhiteboard = useAppStore((s) => s.connectWhiteboard)
  const disconnectWhiteboard = useAppStore((s) => s.disconnectWhiteboard)
  // VIEWER is read-only: the backend rejects note writes with 403, so the
  // board shows no controls that would fail.
  const canWrite = useAppStore((s) => s.currentUser?.role !== 'VIEWER')

  const boardRef = useRef<HTMLDivElement>(null)
  const dragState = useRef<{ id: string; offsetX: number; offsetY: number } | null>(null)
  const [draggingId, setDraggingId] = useState<string | null>(null)
  const [highlightId, setHighlightId] = useState<string | null>(null)
  const live = useAppStore((s) => s.whiteboardConnected)

  // An alert about a sticky arrives as ?note=<id>. The note cannot be opened in
  // a modal, so it is outlined where it sits on the board — after fetching the
  // board once more, since the note may have been written after this viewer
  // last loaded it.
  useDeepLink(
    ['note'],
    (query) => {
      const target = stickyNotes.find((n) => n.id === `n-${Number(query.get('note'))}`)
      if (!target) return 'gone'
      setHighlightId(target.id)
      return 'open'
    },
    { refresh: loadStickyNotes },
  )

  useEffect(() => {
    if (!highlightId) return
    const timer = window.setTimeout(() => setHighlightId(null), HIGHLIGHT_MS)
    return () => window.clearTimeout(timer)
  }, [highlightId])

  // Load notes and subscribe to live updates while the board is open.
  useEffect(() => {
    void loadStickyNotes()
    connectWhiteboard()
    return () => {
      disconnectWhiteboard()
    }
  }, [loadStickyNotes, connectWhiteboard, disconnectWhiteboard])

  function handlePointerDown(e: React.PointerEvent, id: string, noteX: number, noteY: number) {
    const board = boardRef.current
    if (!board) return
    const rect = board.getBoundingClientRect()
    dragState.current = {
      id,
      offsetX: e.clientX - rect.left - noteX,
      offsetY: e.clientY - rect.top - noteY,
    }
    setDraggingId(id)
    ;(e.target as Element).setPointerCapture(e.pointerId)
  }

  function handlePointerMove(e: React.PointerEvent) {
    const drag = dragState.current
    const board = boardRef.current
    if (!drag || !board) return
    const rect = board.getBoundingClientRect()
    const x = Math.max(0, Math.min(e.clientX - rect.left - drag.offsetX, rect.width - 176))
    const y = Math.max(0, Math.min(e.clientY - rect.top - drag.offsetY, rect.height - 176))
    if (!canWrite) return
    moveStickyNote(drag.id, x, y)
  }

  function handlePointerUp() {
    const dragged = dragState.current
    dragState.current = null
    setDraggingId(null)
    // Persist the final position once, not on every pointer move.
    if (dragged) saveStickyNotePosition(dragged.id)
  }

  return (
    <div className="px-4 py-6 sm:px-8 sm:py-8 lg:px-16 lg:py-12">
      <div className="mb-1 flex flex-wrap items-center justify-between gap-3">
        <div className="text-xs font-medium uppercase tracking-widest text-mute">Collaboration</div>
        <div className="flex items-center gap-3">
          <span
            className={`flex items-center gap-1.5 text-xs ${live ? 'text-success' : 'text-mute'}`}
            title={live ? 'Live — changes sync across sessions' : 'Connecting…'}
          >
            <span className={`h-1.5 w-1.5 rounded-full ${live ? 'bg-success' : 'bg-mute'}`} />
            {live ? 'Live' : 'Offline'}
          </span>
          {noteColors.map((color) =>
            canWrite ? (
              <button
                key={color}
                onClick={() => void addStickyNote(color)}
                className="h-7 w-7 rounded-full border border-line shadow-sm transition hover:scale-110"
                style={{ backgroundColor: color }}
                aria-label={`Add ${color} sticky note`}
              />
            ) : (
              <span
                key={color}
                className="h-7 w-7 rounded-full border border-line shadow-sm opacity-50"
                style={{ backgroundColor: color }}
                aria-hidden="true"
              />
            ),
          )}
        </div>
      </div>
      <h1 className="text-3xl font-semibold tracking-tight text-ink sm:text-4xl lg:text-5xl">
        Whiteboard
      </h1>

      <div
        ref={boardRef}
        onPointerMove={handlePointerMove}
        onPointerUp={handlePointerUp}
        className="relative mt-8 h-[560px] w-full overflow-hidden border border-line bg-paper bg-[radial-gradient(#e7e6e2_1px,transparent_1px)] [background-size:20px_20px]"
      >
        {stickyNotes.length === 0 && (
          <div className="flex h-full items-center justify-center text-sm text-mute">
            {canWrite ? 'Click a color above to drop a sticky note.' : 'No sticky notes yet.'}
          </div>
        )}
        {stickyNotes.map((note) => (
          <div
            key={note.id}
            style={{ left: note.x, top: note.y, backgroundColor: note.color as NoteColor }}
            className={`group absolute flex h-44 w-44 flex-col rounded-sm shadow-md ${
              canWrite ? 'touch-none' : ''
            } ${draggingId === note.id ? 'z-10 shadow-lg' : ''} ${
              highlightId === note.id ? 'z-10 ring-4 ring-ink/60' : ''
            }`}
          >
            <div
              onPointerDown={canWrite ? (e) => handlePointerDown(e, note.id, note.x, note.y) : undefined}
              className={`flex h-5 shrink-0 items-center justify-center gap-0.5 ${
                canWrite ? 'cursor-grab active:cursor-grabbing' : ''
              }`}
            >
              <span className="h-1 w-1 rounded-full bg-black/25" />
              <span className="h-1 w-1 rounded-full bg-black/25" />
              <span className="h-1 w-1 rounded-full bg-black/25" />
            </div>
            {canWrite ? (
              <button
                onClick={() => void deleteStickyNote(note.id)}
                className="absolute right-1.5 top-1.5 hidden h-5 w-5 items-center justify-center rounded-full bg-black/10 text-xs text-black/60 hover:bg-black/20 group-hover:flex"
                aria-label="Delete note"
              >
                ✕
              </button>
            ) : null}
            <textarea
              value={note.text}
              onChange={(e) => updateStickyNoteText(note.id, e.target.value)}
              onBlur={() => saveStickyNoteText(note.id)}
              placeholder="Write something..."
              readOnly={!canWrite}
              className="min-h-0 w-full flex-1 resize-none bg-transparent px-3 pb-1 text-sm leading-snug text-black/80 outline-none placeholder:text-black/40"
            />
            <div className="shrink-0 px-3 pb-2 text-[10px] font-medium uppercase tracking-wide text-black/40">
              {note.author}
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}