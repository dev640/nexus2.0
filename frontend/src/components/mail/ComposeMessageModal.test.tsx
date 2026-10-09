import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ComposeMessageModal } from './ComposeMessageModal'
import { useAppStore } from '../../store/useAppStore'
import { apiBroadcastMessage, apiSendMessage } from '../../lib/api'

vi.mock('../../lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../lib/api')>()
  return {
    ...actual,
    apiSendMessage: vi.fn(),
    apiBroadcastMessage: vi.fn(),
  }
})

const SUBJECT = 'Sprint 8 handover'
const BODY = 'Notes are in the wiki.'

const created = {
  id: 20,
  senderId: 3,
  senderName: 'Vidhi',
  recipientId: 1,
  recipientName: 'Devendra',
  subject: SUBJECT,
  body: BODY,
  read: false,
  createdAt: new Date().toISOString(),
}

function seedStore() {
  useAppStore.setState({
    currentUser: { id: 3, name: 'Vidhi', email: 'vidhi@nexus.com', role: 'MEMBER' },
    members: [
      { id: 'u-1', name: 'Devendra', initials: 'D', role: 'ADMIN', utilization: 0 },
      { id: 'u-2', name: 'Achal K', initials: 'AK', role: 'MEMBER', utilization: 0 },
    ],
  })
}

/** The form's two text fields, in DOM order: subject, then the message. */
function fillMessage() {
  const [subjectField, bodyField] = Array.from(document.querySelectorAll('input, textarea'))
  fireEvent.change(subjectField, { target: { value: SUBJECT } })
  fireEvent.change(bodyField, { target: { value: BODY } })
}

describe('ComposeMessageModal', () => {
  beforeEach(() => {
    seedStore()
    vi.mocked(apiSendMessage).mockReset().mockResolvedValue(created)
    vi.mocked(apiBroadcastMessage).mockReset().mockResolvedValue({ ...created, broadcast: true })
  })

  it('offers everyone as well as the individual teammates', () => {
    render(<ComposeMessageModal open onClose={() => {}} />)

    const options = Array.from(document.querySelectorAll('option')).map((o) => o.textContent)
    expect(options[0]).toBe('Pick a teammate…')
    expect(options[1]).toBe('Everyone (2 teammates)')
    expect(options).toContain('Devendra')
    expect(options).toContain('Achal K')
  })

  it('sends to the whole workspace when everyone is chosen', async () => {
    render(<ComposeMessageModal open onClose={() => {}} />)
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'everyone' } })
    expect(screen.getByRole('button', { name: 'Send to everyone' })).toBeTruthy()

    fillMessage()
    fireEvent.click(screen.getByRole('button', { name: 'Send to everyone' }))

    // One request the server fans out, not one call per teammate.
    await waitFor(() =>
      expect(apiBroadcastMessage).toHaveBeenCalledWith({ subject: SUBJECT, body: BODY }),
    )
    expect(apiSendMessage).not.toHaveBeenCalled()
  })

  it('sends to one person when a teammate is chosen', async () => {
    render(<ComposeMessageModal open onClose={() => {}} />)
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'u-1' } })

    fillMessage()
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))

    await waitFor(() =>
      expect(apiSendMessage).toHaveBeenCalledWith({
        recipientId: 1,
        subject: SUBJECT,
        body: BODY,
      }),
    )
    expect(apiBroadcastMessage).not.toHaveBeenCalled()
  })

  it('refuses an empty recipient before talking to the server', async () => {
    render(<ComposeMessageModal open onClose={() => {}} />)

    fillMessage()
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))

    expect(await screen.findByText(/Pick someone to send this to, or choose Everyone/)).toBeTruthy()
    expect(apiSendMessage).not.toHaveBeenCalled()
    expect(apiBroadcastMessage).not.toHaveBeenCalled()
  })
})
