import { useEffect, useState } from 'react'
import { useDeepLink } from '../hooks/useDeepLink'
import { useAppStore } from '../store/useAppStore'
import { NewWikiPageModal } from '../components/wiki/NewWikiPageModal'

function formatDate(iso: string) {
  return new Date(iso).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' })
}

export function Wiki() {
  const projects = useAppStore((s) => s.projects)
  const wikiPages = useAppStore((s) => s.wikiPages)
  const updateWikiPage = useAppStore((s) => s.updateWikiPage)
  const deleteWikiPage = useAppStore((s) => s.deleteWikiPage)
  // VIEWER is read-only: the backend rejects wiki writes with 403, so the
  // page offers no controls that would fail.
  const canWrite = useAppStore((s) => s.currentUser?.role !== 'VIEWER')

  const [selectedId, setSelectedId] = useState<string | undefined>(wikiPages[0]?.id)
  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState(false)
  const [draftTitle, setDraftTitle] = useState('')
  const [draftContent, setDraftContent] = useState('')
  const [confirmingDelete, setConfirmingDelete] = useState(false)
  const [saving, setSaving] = useState(false)
  const loadWorkspace = useAppStore((s) => s.loadWorkspace)

  const selected = wikiPages.find((p) => p.id === selectedId)

  useEffect(() => {
    if (!selected && wikiPages.length > 0) {
      setSelectedId(wikiPages[0].id)
    }
  }, [selected, wikiPages])

  // An alert about a page arrives as ?page=<id>; open that page instead of the
  // first one the list happens to hold. A page created after this viewer loaded
  // the workspace is fetched rather than treated as deleted.
  useDeepLink(
    ['page'],
    (query) => {
      const target = wikiPages.find((p) => p.id === `w-${Number(query.get('page'))}`)
      if (!target) return 'gone'
      setSelectedId(target.id)
      setEditing(false)
      setConfirmingDelete(false)
      return 'open'
    },
    { refresh: loadWorkspace },
  )

  function selectPage(id: string) {
    setSelectedId(id)
    setEditing(false)
    setConfirmingDelete(false)
  }

  function startEditing() {
    if (!selected) return
    setDraftTitle(selected.title)
    setDraftContent(selected.content)
    setEditing(true)
  }

  async function handleSave() {
    if (!selected || !draftTitle.trim() || saving) return
    setSaving(true)
    const result = await updateWikiPage(selected.id, { title: draftTitle.trim(), content: draftContent })
    setSaving(false)
    if (result.ok) {
      setEditing(false)
    }
  }

  async function handleDelete() {
    if (!selected) return
    const result = await deleteWikiPage(selected.id)
    if (result.ok) {
      setConfirmingDelete(false)
      setSelectedId(undefined)
    }
  }

  return (
    <div className="px-4 py-6 sm:px-8 sm:py-8 lg:px-16 lg:py-12">
      <div className="mb-1 flex items-center justify-between">
        <div className="text-xs font-medium uppercase tracking-widest text-mute">Knowledge</div>
        {canWrite ? (
          <button
            onClick={() => setModalOpen(true)}
            className="rounded-md bg-ink px-4 py-2 text-sm font-medium text-white hover:bg-black"
          >
            + New Page
          </button>
        ) : null}
      </div>
      <h1 className="text-3xl font-semibold tracking-tight text-ink sm:text-4xl lg:text-5xl">Wiki</h1>

      <div className="mt-10 flex flex-col gap-6 lg:flex-row">
        <div className="w-full shrink-0 border border-line bg-white lg:w-64">
          {wikiPages.length === 0 ? (
            <div className="p-4 text-sm text-mute">No pages yet.</div>
          ) : (
            <nav className="flex flex-col">
              {wikiPages.map((p) => {
                const project = projects.find((proj) => proj.id === p.projectId)
                return (
                  <button
                    key={p.id}
                    onClick={() => selectPage(p.id)}
                    className={`border-b border-line px-4 py-3 text-left text-sm last:border-0 ${
                      p.id === selectedId ? 'bg-ink text-white' : 'hover:bg-paper'
                    }`}
                  >
                    <div className="font-medium">{p.title}</div>
                    <div className={`mt-0.5 text-xs ${p.id === selectedId ? 'text-white/60' : 'text-mute'}`}>
                      {project?.name ?? 'General'}
                    </div>
                  </button>
                )
              })}
            </nav>
          )}
        </div>

        <div className="min-w-0 flex-1 border border-line bg-white p-6 sm:p-8">
          {!selected ? (
            <div className="text-sm text-mute">
              {wikiPages.length === 0 ? 'Create a page to get started.' : 'Select a page.'}
            </div>
          ) : editing ? (
            <div className="flex flex-col gap-4">
              <input
                value={draftTitle}
                onChange={(e) => setDraftTitle(e.target.value)}
                className="w-full rounded-md border border-line px-3 py-2 text-xl font-semibold tracking-tight outline-none focus:border-ink"
              />
              <textarea
                value={draftContent}
                onChange={(e) => setDraftContent(e.target.value)}
                rows={14}
                className="w-full resize-y rounded-md border border-line px-3 py-2 text-sm leading-relaxed outline-none focus:border-ink"
                placeholder="Write in plain text or Markdown..."
              />
              <div className="flex gap-2">
                <button
                  onClick={handleSave}
                  disabled={saving}
                  className="rounded-md bg-ink px-4 py-2 text-sm font-medium text-white hover:bg-black disabled:opacity-60"
                >
                  {saving ? 'Saving…' : 'Save'}
                </button>
                <button
                  onClick={() => setEditing(false)}
                  className="rounded-md border border-line px-4 py-2 text-sm font-medium hover:bg-paper"
                >
                  Cancel
                </button>
              </div>
            </div>
          ) : (
            <div>
              <div className="flex flex-wrap items-start justify-between gap-3">
                <h2 className="text-2xl font-semibold tracking-tight text-ink sm:text-3xl">
                  {selected.title}
                </h2>
                <div className="flex shrink-0 gap-2">
                  {canWrite ? (
                  <button
                    onClick={startEditing}
                    className="rounded-md border border-line px-3 py-1.5 text-xs font-medium hover:bg-paper"
                  >
                    Edit
                  </button>
                  ) : null}
                  {canWrite && confirmingDelete ? (
                    <>
                <button
                  onClick={() => void handleDelete()}
                  className="rounded-md bg-danger px-3 py-1.5 text-xs font-medium text-white hover:bg-danger/90"
                >
                  Confirm
                </button>
                      <button
                        onClick={() => setConfirmingDelete(false)}
                        className="rounded-md border border-line px-3 py-1.5 text-xs font-medium hover:bg-paper"
                      >
                        Cancel
                      </button>
                    </>
                  ) : canWrite ? (
                    <button
                      onClick={() => setConfirmingDelete(true)}
                      className="rounded-md border border-danger/40 px-3 py-1.5 text-xs font-medium text-danger hover:bg-danger/10"
                    >
                      Delete
                    </button>
                  ) : null}
                </div>
              </div>
              <p className="mt-1 text-xs text-mute">
                {selected.author} · updated {formatDate(selected.updatedAt)}
              </p>
              <div className="mt-6 whitespace-pre-wrap text-sm leading-relaxed text-ink">
                {selected.content || (
                  <span className="text-mute">This page is empty. Click Edit to add content.</span>
                )}
              </div>
            </div>
          )}
        </div>
      </div>

      <NewWikiPageModal
        open={modalOpen}
        onClose={() => setModalOpen(false)}
        onCreated={(page) => {
          setModalOpen(false)
          setSelectedId(page.id)
          setConfirmingDelete(false)
          setDraftTitle(page.title)
          setDraftContent('')
          setEditing(true)
        }}
      />
    </div>
  )
}
