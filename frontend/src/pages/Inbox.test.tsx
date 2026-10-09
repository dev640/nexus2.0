import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { Inbox } from './Inbox'
import { useAppStore } from '../store/useAppStore'
import {
  apiDeleteMessage,
  apiListMessages,
  apiListSentMessages,
  apiMarkMessageRead,
  apiMarkNotificationRead,
} from '../lib/api'
import type { MailMessage } from '../store/models'

vi.mock('../lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../lib/api')>()
  return {
    ...actual,
    apiListMessages: vi.fn(),
    apiListSentMessages: vi.fn(),
    apiDeleteMessage: vi.fn(async () => {}),
    apiMarkMessageRead: vi.fn(async () => {}),
    apiMarkNotificationRead: vi.fn(async () => {}),
  }
})

/** In the shape the API serves it: choosing the Mail tab refetches the list. */
const apiLetter = {
  id: 12,
  senderId: 1,
  senderName: 'Devendra',
  recipientId: 3,
  recipientName: 'Vidhi',
  subject: 'Sprint 8 handover',
  // Longer than the row's 120-character preview on purpose: the full text then
  // exists only in the opened message, so the assertion cannot pass on the row.
  body:
    'The board is yours while I am away. I have moved the open items to Sprint 9 ' +
    'and left notes on the checkout work. Ping me if anything urgent lands.',
  read: false,
  createdAt: new Date().toISOString(),
}

const letter: MailMessage = {
  id: apiLetter.id,
  senderId: 'u-1',
  senderName: apiLetter.senderName,
  recipientId: 'u-3',
  recipientName: apiLetter.recipientName,
  subject: apiLetter.subject,
  body: apiLetter.body,
  read: apiLetter.read,
  broadcast: false,
  time: 'just now',
}

function seedStore() {
  useAppStore.setState({
    currentUser: { id: 3, name: 'Vidhi', email: 'vidhi@nexus.com', role: 'MEMBER' },
    settings: { displayName: 'Vidhi', role: 'MEMBER', defaultAssigneeId: '', mutedCategories: [] },
    inboxMessages: [],
    sentMessages: [],
    notifications: [
      {
        id: 4,
        category: 'TASKS',
        text: 'You were assigned to "Write API documentation"',
        link: null,
        time: 'just now',
        read: false,
        archived: false,
      },
    ],
  })
}

function renderInbox() {
  return render(
    <MemoryRouter initialEntries={['/inbox']}>
      <Routes>
        <Route path="/inbox" element={<Inbox />} />
        <Route path="/my-work" element={<div>MY WORK PAGE</div>} />
      </Routes>
    </MemoryRouter>,
  )
}

/** The Mail view lists messages only after its tab is chosen. */
async function openMailView() {
  fireEvent.click(screen.getByRole('button', { name: /^Mail/ }))
  await waitFor(() => expect(screen.getByText(letter.subject)).toBeTruthy())
}

describe('Inbox', () => {
  beforeEach(() => {
    seedStore()
    vi.mocked(apiListMessages).mockReset().mockResolvedValue([apiLetter])
    vi.mocked(apiListSentMessages).mockReset().mockResolvedValue([])
    vi.mocked(apiDeleteMessage).mockClear()
    vi.mocked(apiMarkMessageRead).mockClear()
    vi.mocked(apiMarkNotificationRead).mockClear()
  })

  it('opens a message when its row is clicked, not only its Open button', async () => {
    renderInbox()
    await openMailView()

    // The row itself: a person clicks the mail, not the small button on the right.
    fireEvent.click(screen.getByText(letter.subject))

    const dialog = await screen.findByRole('heading', { name: letter.subject })
    expect(dialog).toBeTruthy()
    expect(screen.getByText(letter.body)).toBeTruthy()
    // Opening received mail is also reading it.
    await waitFor(() => expect(apiMarkMessageRead).toHaveBeenCalledWith(12))
  })

  it('deletes from a row without also opening the message it deletes', async () => {
    renderInbox()
    await openMailView()

    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))

    await waitFor(() => expect(apiDeleteMessage).toHaveBeenCalledWith(12))
    expect(screen.queryByRole('heading', { name: letter.subject })).toBeNull()
  })

  it('sends a notification\u2019s Open somewhere instead of doing nothing', async () => {
    renderInbox()
    fireEvent.click(screen.getByRole('button', { name: 'Open' }))

    await waitFor(() => expect(screen.getByText('MY WORK PAGE')).toBeTruthy())
    expect(apiMarkNotificationRead).toHaveBeenCalledWith(4)
  })
})
