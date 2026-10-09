import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ChatSocketEvent } from '../lib/chatSocket'
import { saveAlertPreferences, setAlertNavigator } from '../lib/deviceAlerts'
import { useAppStore } from '../store/useAppStore'
import { emitAlert, useDeviceAlerts } from './useDeviceAlerts'

const socket = vi.hoisted(() => ({ handlers: [] as Array<(event: unknown) => void> }))

vi.mock('../lib/chatSocket', () => ({
  connectChatSocket: (onEvent: (event: unknown) => void) => {
    socket.handlers.push(onEvent)
    return { dispose: () => {}, send: () => {} }
  },
}))

const popups: Array<{ title: string; body?: string }> = []
const instances: FakeNotification[] = []
const plays: string[] = []

class FakeNotification {
  static permission: NotificationPermission = 'granted'
  static requestPermission = vi.fn(async () => FakeNotification.permission)
  onclick: (() => void) | null = null

  constructor(title: string, options?: NotificationOptions) {
    popups.push({ title, body: options?.body })
    instances.push(this)
  }

  close() {}
}

class FakeAudio {
  play() {
    plays.push('played')
    return Promise.resolve()
  }
}

/** The handler the hook handed to the (mocked) socket when it connected. */
function latestHandler(): (event: ChatSocketEvent) => void {
  const handler = socket.handlers.at(-1)
  if (!handler) throw new Error('the hook never connected to the socket')
  return handler as (event: ChatSocketEvent) => void
}

function notification(overrides: Partial<ApiNotificationish> = {}): ApiNotificationish {
  return {
    id: 1,
    category: 'TASKS',
    text: 'Devendra added the task "Ship it"',
    read: false,
    createdAt: new Date().toISOString(),
    ...overrides,
  }
}

type ApiNotificationish = {
  id: number
  category: string
  text: string
  link?: string | null
  read: boolean
  createdAt: string
}

beforeEach(() => {
  localStorage.clear()
  popups.length = 0
  instances.length = 0
  plays.length = 0
  socket.handlers.length = 0
  FakeNotification.permission = 'granted'
  FakeNotification.requestPermission.mockClear()
  vi.stubGlobal('Notification', FakeNotification)
  vi.stubGlobal('Audio', FakeAudio)
  useAppStore.setState({
    currentUser: { id: 3, name: 'Vidhi', email: 'vidhi@nexus.com', role: 'MEMBER' },
    notifications: [],
    activeAlerts: [],
    settings: { displayName: 'Vidhi', role: 'MEMBER', defaultAssigneeId: '', mutedCategories: [] },
  })
})

afterEach(() => {
  vi.unstubAllGlobals()
  setAlertNavigator(null)
})

describe('useDeviceAlerts', () => {
  it('pops a device notification for a pushed notification and files it in the feed', () => {
    renderHook(() => useDeviceAlerts())

    act(() => latestHandler()({ type: 'notification.created', notification: notification() }))

    expect(popups).toEqual([{ title: 'Nexus', body: 'Devendra added the task "Ship it"' }])
    expect(plays).toHaveLength(1)
    expect(useAppStore.getState().notifications.map((n) => n.id)).toEqual([1])
  })

  it('stays quiet for a mention, which the chat message already announced', () => {
    renderHook(() => useDeviceAlerts())

    act(() =>
      latestHandler()({
        type: 'notification.created',
        notification: notification({ category: 'MENTIONS', text: 'You were mentioned by Devendra in #general: hello' }),
      }),
    )

    expect(popups).toHaveLength(0)
    expect(plays).toHaveLength(0)
    // Still filed, so the Inbox list stays current.
    expect(useAppStore.getState().notifications).toHaveLength(1)
  })

  it('keeps muted categories out of the alerts as well as the Inbox', () => {
    useAppStore.setState((state) => ({
      settings: { ...state.settings, mutedCategories: ['TASKS'] },
    }))
    renderHook(() => useDeviceAlerts())

    act(() => latestHandler()({ type: 'notification.created', notification: notification() }))

    expect(popups).toHaveLength(0)
    expect(plays).toHaveLength(0)
  })

  it('falls back to an in-app toast when the browser will not show a popup', () => {
    FakeNotification.permission = 'denied'
    renderHook(() => useDeviceAlerts())

    act(() => latestHandler()({ type: 'notification.created', notification: notification() }))

    const alerts = useAppStore.getState().activeAlerts
    expect(alerts).toHaveLength(1)
    expect(alerts[0].body).toBe('Devendra added the task "Ship it"')
  })

  it('announces a chat message from somebody else, naming the channel', () => {
    renderHook(() => useDeviceAlerts())

    act(() =>
      latestHandler()({
        type: 'message.created',
        channelId: 4,
        channelName: 'general',
        message: chatMessage({ authorId: 1, authorName: 'Devendra', body: 'Sprint review at 4' }),
      }),
    )

    expect(popups).toEqual([{ title: 'Devendra in #general', body: 'Sprint review at 4' }])
    expect(plays).toHaveLength(1)
  })

  it('never alerts about your own message', () => {
    renderHook(() => useDeviceAlerts())

    act(() =>
      latestHandler()({
        type: 'message.created',
        channelId: 4,
        channelName: 'general',
        message: chatMessage({ authorId: 3, authorName: 'Vidhi', body: 'mine' }),
      }),
    )

    expect(popups).toHaveLength(0)
    expect(plays).toHaveLength(0)
  })

  it('respects the master switch', () => {
    saveAlertPreferences({ enabled: false, sound: true })
    renderHook(() => useDeviceAlerts())

    act(() => latestHandler()({ type: 'notification.created', notification: notification() }))

    expect(popups).toHaveLength(0)
    expect(useAppStore.getState().activeAlerts).toHaveLength(0)
  })

  it('does not ask for permission twice for the same session', () => {
    FakeNotification.permission = 'default'
    const { rerender } = renderHook(() => useDeviceAlerts())

    rerender()

    expect(FakeNotification.requestPermission).toHaveBeenCalledTimes(1)
  })

  it('carries the link into the fallback toast, so it stays clickable', () => {
    FakeNotification.permission = 'denied'
    renderHook(() => useDeviceAlerts())

    act(() =>
      latestHandler()({
        type: 'notification.created',
        notification: notification({ link: '/board?task=7' }),
      }),
    )

    expect(useAppStore.getState().activeAlerts[0].link).toBe('/board?task=7')
  })

  it('opens the task from the system popup when it is clicked', () => {
    const navigate = vi.fn()
    setAlertNavigator(navigate)
    renderHook(() => useDeviceAlerts())

    act(() =>
      latestHandler()({
        type: 'notification.created',
        notification: notification({ link: '/board?task=7' }),
      }),
    )
    act(() => instances.at(-1)?.onclick?.())

    expect(navigate).toHaveBeenCalledWith('/board?task=7')
  })

  it('opens the channel at the message an alert is about', () => {
    const navigate = vi.fn()
    setAlertNavigator(navigate)
    renderHook(() => useDeviceAlerts())

    act(() =>
      latestHandler()({
        type: 'message.created',
        channelId: 4,
        channelName: 'general',
        message: chatMessage({ authorId: 1, authorName: 'Devendra', body: 'Sprint review at 4' }),
      }),
    )
    act(() => instances.at(-1)?.onclick?.())

    expect(navigate).toHaveBeenCalledWith('/slack?channel=4&message=9')
  })

  it('sends a test alert through exactly the same path as a real one', () => {
    emitAlert('Nexus', 'Test alert — this is what new activity looks like.')

    expect(popups[0]).toEqual({
      title: 'Nexus',
      body: 'Test alert — this is what new activity looks like.',
    })
    expect(plays).toHaveLength(1)
  })
})

function chatMessage(overrides: { authorId: number; authorName: string; body: string }) {
  return {
    id: 9,
    channelId: 4,
    edited: false,
    reactions: {},
    createdAt: new Date().toISOString(),
    ...overrides,
  }
}
