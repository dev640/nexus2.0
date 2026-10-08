import type { StateCreator } from 'zustand'
import {
  apiDeleteMessage,
  apiErrorMessage,
  apiListMessages,
  apiListSentMessages,
  apiMarkMessageRead,
  apiSendMessage,
} from '../../lib/api'
import type { MessageResult, NewMessageInput } from '../contracts'
import { numericUserId } from '../ids'
import { mapMessage } from '../mappers'
import type { MailMessage } from '../models'
import type { AppState } from '../state'

export interface MessagesSlice {
  /** Mail written to me, newest first. */
  inboxMessages: MailMessage[]
  /** Mail I wrote, newest first. */
  sentMessages: MailMessage[]

  loadMessages: () => Promise<void>
  sendMessage: (input: NewMessageInput) => Promise<MessageResult>
  markMessageRead: (id: number) => void
  deleteMessage: (id: number) => Promise<void>
}

export const createMessagesSlice: StateCreator<AppState, [], [], MessagesSlice> = (set, get) => ({
  inboxMessages: [],
  sentMessages: [],

  loadMessages: async () => {
    try {
      const [inbox, sent] = await Promise.all([apiListMessages(), apiListSentMessages()])
      set({ inboxMessages: inbox.map(mapMessage), sentMessages: sent.map(mapMessage) })
    } catch (err) {
      set({ syncError: apiErrorMessage(err, 'Failed to load messages') })
    }
  },

  sendMessage: async (input) => {
    const recipientId = numericUserId(input.recipientId)
    if (recipientId == null) {
      return { ok: false, error: 'Pick someone to send this to' }
    }
    try {
      const created = await apiSendMessage({
        recipientId,
        subject: input.subject.trim(),
        body: input.body.trim(),
      })
      // The sender's own copy lands in Sent; the recipient sees it in their
      // inbox on their next load.
      set((state) => ({ sentMessages: [mapMessage(created), ...state.sentMessages] }))
      return { ok: true }
    } catch (err) {
      return { ok: false, error: apiErrorMessage(err, 'Could not send the message') }
    }
  },

  markMessageRead: (id) => {
    // Optimistic; roll back on failure, like notifications.
    const previous = get().inboxMessages
    set({ inboxMessages: previous.map((m) => (m.id === id ? { ...m, read: true } : m)) })
    apiMarkMessageRead(id).catch(() => set({ inboxMessages: previous }))
  },

  deleteMessage: async (id) => {
    const previousInbox = get().inboxMessages
    const previousSent = get().sentMessages
    set({
      inboxMessages: previousInbox.filter((m) => m.id !== id),
      sentMessages: previousSent.filter((m) => m.id !== id),
    })
    try {
      await apiDeleteMessage(id)
    } catch (err) {
      set({
        inboxMessages: previousInbox,
        sentMessages: previousSent,
        syncError: apiErrorMessage(err, 'Failed to delete the message'),
      })
    }
  },
})
