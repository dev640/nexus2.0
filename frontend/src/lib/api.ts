import axios from 'axios'
import { supabase, supabaseEnabled } from './supabase'

const TOKEN_KEY = 'nexus-auth-token'

export function getStoredToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function storeToken(token: string | null) {
  if (token) {
    localStorage.setItem(TOKEN_KEY, token)
  } else {
    localStorage.removeItem(TOKEN_KEY)
  }
}

export const api = axios.create({
  baseURL: import.meta.env.VITE_API_URL || '/api',
  headers: { 'Content-Type': 'application/json' },
})

api.interceptors.request.use(async (config) => {
  // When Supabase auth is enabled it is the source of truth: its session
  // manager refreshes tokens before they expire, so always send the live one.
  if (supabaseEnabled && supabase) {
    const { data } = await supabase.auth.getSession()
    const token = data.session?.access_token
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
      return config
    }
  }
  const token = getStoredToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// ---------- API types (mirror backend DTOs) ----------

export type ApiTaskStatus = 'BACKLOG' | 'TODO' | 'IN_PROGRESS' | 'IN_REVIEW' | 'TESTING' | 'DONE'
export type ApiTaskPriority = 'LOW' | 'MEDIUM' | 'HIGH' | 'URGENT'
export type ApiSprintStatus = 'PLANNED' | 'ACTIVE' | 'COMPLETED'
export type ApiProjectStatus = 'PLANNING' | 'ACTIVE' | 'ON_HOLD' | 'COMPLETED' | 'ARCHIVED'
export type ApiProjectHealth = 'ON_TRACK' | 'AT_RISK' | 'OFF_TRACK'
export type ApiUserRole = 'ADMIN' | 'MEMBER' | 'VIEWER' | 'DEVELOPER'

export interface ApiUser {
  id: number
  name: string
  email: string
  role: ApiUserRole
  /** Human-facing code (NX-0007). Always present on rows created after V11. */
  employeeCode?: string | null
}

export interface ApiProject {
  id: number
  name: string
  description: string | null
  status: ApiProjectStatus
  health: ApiProjectHealth
  progress: number
  sprintNumber: number
  memberCount: number
  createdAt: string
  updatedAt: string
}

export interface ApiSprint {
  id: number
  projectId: number
  projectName: string
  number: number
  goal: string
  startDate: string
  endDate: string
  committedPoints: number
  status: ApiSprintStatus
  createdAt: string
  updatedAt: string
}

export interface ApiTask {
  id: number
  title: string
  description: string | null
  projectId: number
  projectName: string
  sprintId: number | null
  status: ApiTaskStatus
  priority: ApiTaskPriority
  storyPoints: number
  assignee: ApiUser | null
  labels: string[]
  /** Waiting on something. Independent of status. */
  blocked: boolean
  createdAt: string
  updatedAt: string
}

export interface ApiAuthResponse {
  token: string
  refreshToken: string
  user: ApiUser
}

// ---------- Auth ----------

export async function apiLogin(email: string, password: string): Promise<ApiAuthResponse> {
  const { data } = await api.post<ApiAuthResponse>('/auth/login', { email, password })
  return data
}

export async function apiRegister(name: string, email: string, password: string): Promise<ApiAuthResponse> {
  const { data } = await api.post<ApiAuthResponse>('/auth/register', { name, email, password })
  return data
}

/**
 * Files a password-reset request. Public, and always resolves successfully —
 * the endpoint never reveals whether the address is registered.
 */
export async function apiRequestPasswordReset(email: string, note?: string): Promise<void> {
  await api.post('/auth/password-reset-request', { email, note: note || null })
}

// ---------- Users ----------

export async function apiListUsers(): Promise<ApiUser[]> {
  const { data } = await api.get<ApiUser[]>('/users')
  return data
}

export async function apiUpdateMe(name: string): Promise<ApiUser> {
  const { data } = await api.patch<ApiUser>('/users/me', { name })
  return data
}

export async function apiUpdateUserRole(id: number, role: ApiUserRole): Promise<ApiUser> {
  const { data } = await api.patch<ApiUser>(`/users/${id}/role`, { role })
  return data
}

/**
 * Uploads the signed-in user's avatar. The server re-encodes whatever is sent,
 * so the client only has to pick a file.
 *
 * axios drops the instance Content-Type for FormData and lets the browser add
 * the multipart boundary, which is why no explicit header is set here.
 */
export async function apiUploadAvatar(file: File): Promise<void> {
  const form = new FormData()
  form.append('file', file)
  await api.post('/users/me/avatar', form)
}

export async function apiDeleteAvatar(): Promise<void> {
  await api.delete('/users/me/avatar')
}

/**
 * Permanently removes an account. ADMIN-only. The backend refuses self-deletion
 * and refuses to remove the last admin, so neither mistake can lock the
 * workspace out of its own management.
 */
export async function apiDeleteUser(id: number): Promise<void> {
  await api.delete(`/users/${id}`)
}

export interface ApiCreateUserInput {
  name: string
  email: string
  /** Omit to have the server generate one. */
  password?: string
  role?: ApiUserRole
}

export interface ApiCreatedUser {
  id: number
  name: string
  email: string
  role: ApiUserRole
  /** Present only when the server generated it; shown once and never stored. */
  password: string | null
}

/** ADMIN-only. There is no public signup: accounts are provisioned from here. */
export async function apiCreateUser(input: ApiCreateUserInput): Promise<ApiCreatedUser> {
  const { data } = await api.post<ApiCreatedUser>('/users', input)
  return data
}

export type ApiResetRequestStatus = 'PENDING' | 'RESOLVED' | 'DISMISSED'

export interface ApiPasswordResetRequest {
  id: number
  userId: number
  userName: string
  userEmail: string
  status: ApiResetRequestStatus
  requestedAt: string
  resolvedAt: string | null
  resolvedByName: string | null
  note: string | null
}

export async function apiListPasswordResets(pendingOnly = false): Promise<ApiPasswordResetRequest[]> {
  const { data } = await api.get<ApiPasswordResetRequest[]>('/admin/password-resets', {
    params: { pendingOnly },
  })
  return data
}

/** Issues a new password and returns it once, for the admin to hand over. */
export async function apiResolvePasswordReset(id: number): Promise<ApiCreatedUser> {
  const { data } = await api.post<ApiCreatedUser>(`/admin/password-resets/${id}/resolve`)
  return data
}

export async function apiDismissPasswordReset(id: number): Promise<void> {
  await api.post(`/admin/password-resets/${id}/dismiss`)
}

// ---------- Projects ----------

export async function apiListProjects(): Promise<ApiProject[]> {
  const { data } = await api.get<ApiProject[]>('/projects')
  return data
}

export interface ApiProjectInput {
  name: string
  description?: string | null
  status?: ApiProjectStatus
}

export async function apiCreateProject(input: ApiProjectInput): Promise<ApiProject> {
  const { data } = await api.post<ApiProject>('/projects', input)
  return data
}

export async function apiUpdateProject(id: number, input: ApiProjectInput): Promise<ApiProject> {
  const { data } = await api.put<ApiProject>(`/projects/${id}`, input)
  return data
}

export async function apiDeleteProject(id: number): Promise<void> {
  await api.delete(`/projects/${id}`)
}

// ---------- Sprints ----------

export async function apiListSprints(projectId?: number): Promise<ApiSprint[]> {
  const { data } = await api.get<ApiSprint[]>('/sprints', {
    params: projectId != null ? { projectId } : undefined,
  })
  return data
}

export interface ApiSprintInput {
  projectId: number
  goal: string
  startDate: string
  endDate: string
  committedPoints?: number
  status?: ApiSprintStatus
}

export async function apiCreateSprint(input: ApiSprintInput): Promise<ApiSprint> {
  const { data } = await api.post<ApiSprint>('/sprints', input)
  return data
}

/**
 * projectId must match the sprint's current project: the backend refuses to
 * move a sprint because that would orphan its tasks and renumber the target
 * project's sprint sequence.
 */
export async function apiUpdateSprint(id: number, input: ApiSprintInput): Promise<ApiSprint> {
  const { data } = await api.put<ApiSprint>(`/sprints/${id}`, input)
  return data
}

export async function apiDeleteSprint(id: number): Promise<void> {
  await api.delete(`/sprints/${id}`)
}

export async function apiUpdateSprintStatus(id: number, status: ApiSprintStatus): Promise<ApiSprint> {
  const { data } = await api.patch<ApiSprint>(`/sprints/${id}/status`, { status })
  return data
}

// ---------- Tasks ----------

export async function apiListTasks(params?: { projectId?: number; sprintId?: number }): Promise<ApiTask[]> {
  const { data } = await api.get<ApiTask[]>('/tasks', { params })
  return data
}

export interface ApiTaskInput {
  title: string
  description?: string | null
  projectId: number
  sprintId?: number | null
  status: ApiTaskStatus
  priority: ApiTaskPriority
  storyPoints?: number
  assigneeId?: number | null
  labels?: string[]
  /** Waiting on something. Omit on update to leave the current value alone. */
  blocked?: boolean
}

export async function apiCreateTask(input: ApiTaskInput): Promise<ApiTask> {
  const { data } = await api.post<ApiTask>('/tasks', input)
  return data
}

export async function apiUpdateTaskStatus(id: number, status: ApiTaskStatus): Promise<ApiTask> {
  const { data } = await api.patch<ApiTask>(`/tasks/${id}/status`, { status })
  return data
}

export async function apiUpdateTask(id: number, input: ApiTaskInput): Promise<ApiTask> {
  const { data } = await api.put<ApiTask>(`/tasks/${id}`, input)
  return data
}

export async function apiDeleteTask(id: number): Promise<void> {
  await api.delete(`/tasks/${id}`)
}

// ---------- Wiki ----------

export interface ApiWikiPage {
  id: number
  title: string
  content: string
  author: string | null
  projectId: number | null
  projectName: string | null
  createdAt: string
  updatedAt: string
}

export interface ApiWikiPageInput {
  title: string
  content: string
  projectId?: number | null
}

export async function apiListWikiPages(): Promise<ApiWikiPage[]> {
  const { data } = await api.get<ApiWikiPage[]>('/wiki')
  return data
}

export async function apiCreateWikiPage(input: ApiWikiPageInput): Promise<ApiWikiPage> {
  const { data } = await api.post<ApiWikiPage>('/wiki', input)
  return data
}

export async function apiUpdateWikiPage(id: number, input: ApiWikiPageInput): Promise<ApiWikiPage> {
  const { data } = await api.patch<ApiWikiPage>(`/wiki/${id}`, input)
  return data
}

export async function apiDeleteWikiPage(id: number): Promise<void> {
  await api.delete(`/wiki/${id}`)
}

// ---------- Notifications ----------

export interface ApiNotification {
  id: number
  category: string
  text: string
  read: boolean
  createdAt: string
}

export async function apiListNotifications(): Promise<ApiNotification[]> {
  const { data } = await api.get<ApiNotification[]>('/notifications')
  return data
}

export async function apiMarkNotificationRead(id: number): Promise<void> {
  await api.patch(`/notifications/${id}/read`)
}

export async function apiMarkAllNotificationsRead(): Promise<void> {
  await api.post('/notifications/read-all')
}

export async function apiArchiveNotification(id: number): Promise<void> {
  await api.delete(`/notifications/${id}`)
}

// ---------- Analytics ----------

export interface ApiAnalyticsOverview {
  velocity: {
    perSprint: {
      sprintId: number
      number: number
      goal: string
      committedPoints: number
      donePoints: number
    }[]
  }
  statusBreakdown: { status: string; count: number }[]
  priorityBreakdown: { priority: string; count: number }[]
  teamLoad: { userId: number; name: string; openTasks: number; openPoints: number }[]
  risks: { taskId: number; title: string; reason: string; priority: string; status: string }[]
  summary: {
    totalTasks: number
    doneTasks: number
    completionRate: number
    committedPointsActiveSprint: number
    donePointsActiveSprint: number
  }
}

export async function apiGetAnalyticsOverview(projectId?: number): Promise<ApiAnalyticsOverview> {
  const { data } = await api.get<ApiAnalyticsOverview>('/analytics/overview', {
    params: projectId != null ? { projectId } : undefined,
  })
  return data
}

// ---------- Whiteboard ----------

export interface ApiWhiteboardNote {
  id: number
  board: string
  text: string
  color: string
  x: number
  y: number
  author: string | null
}

export async function apiListWhiteboardNotes(): Promise<ApiWhiteboardNote[]> {
  const { data } = await api.get<ApiWhiteboardNote[]>('/whiteboard/notes')
  return data
}

export async function apiCreateWhiteboardNote(color: string, x: number, y: number): Promise<ApiWhiteboardNote> {
  const { data } = await api.post<ApiWhiteboardNote>('/whiteboard/notes', { color, x, y })
  return data
}

export async function apiUpdateWhiteboardNote(
  id: number,
  patch: { text?: string; color?: string; x?: number; y?: number },
): Promise<ApiWhiteboardNote> {
  const { data } = await api.patch<ApiWhiteboardNote>(`/whiteboard/notes/${id}`, patch)
  return data
}

export async function apiDeleteWhiteboardNote(id: number): Promise<void> {
  await api.delete(`/whiteboard/notes/${id}`)
}

// ---------- Chat (Slack) ----------

export type ApiChatChannelType = 'PUBLIC' | 'PRIVATE' | 'DM'

export interface ApiChatChannel {
  id: number
  name: string | null
  type: ApiChatChannelType
  topic: string | null
  member: boolean
  partnerId: number | null
  partnerName: string | null
  /** Email of the creator. Channels are deletable by the creator or an admin. */
  createdBy: string | null
  createdAt: string
}

export interface ApiChatMessage {
  id: number
  channelId: number | null
  authorId: number | null
  authorName: string | null
  body: string
  edited: boolean
  reactions: Record<string, number[]>
  createdAt: string
}

export interface ApiChatUnread {
  total: number
  channels: { channelId: number; type: string; partnerId: number | null; count: number }[]
}

export async function apiChatChannels(): Promise<ApiChatChannel[]> {
  const { data } = await api.get<ApiChatChannel[]>('/chat/channels')
  return data
}

export async function apiChatDiscoverChannels(): Promise<ApiChatChannel[]> {
  const { data } = await api.get<ApiChatChannel[]>('/chat/channels/discover')
  return data
}

export async function apiChatCreateChannel(name: string, type: 'PUBLIC' | 'PRIVATE', topic?: string): Promise<ApiChatChannel> {
  const { data } = await api.post<ApiChatChannel>('/chat/channels', { name, type, topic })
  return data
}

export async function apiChatOpenDm(userId: number): Promise<ApiChatChannel> {
  const { data } = await api.post<ApiChatChannel>(`/chat/channels/dm/${userId}`)
  return data
}

export async function apiChatJoinChannel(id: number): Promise<ApiChatChannel> {
  const { data } = await api.post<ApiChatChannel>(`/chat/channels/${id}/join`)
  return data
}

export async function apiChatLeaveChannel(id: number): Promise<void> {
  await api.post(`/chat/channels/${id}/leave`)
}

/**
 * Deletes a channel and its history. Creator or admin only, and direct messages
 * are refused: two people share a DM, so deleting it would destroy the other
 * person's history too.
 */
export async function apiChatDeleteChannel(id: number): Promise<void> {
  await api.delete(`/chat/channels/${id}`)
}

export async function apiChatMessages(id: number, before?: number): Promise<ApiChatMessage[]> {
  const { data } = await api.get<ApiChatMessage[]>(`/chat/channels/${id}/messages`, {
    params: before != null ? { before } : undefined,
  })
  return data
}

export async function apiChatPostMessage(id: number, body: string): Promise<ApiChatMessage> {
  const { data } = await api.post<ApiChatMessage>(`/chat/channels/${id}/messages`, { body })
  return data
}

export async function apiChatEditMessage(id: number, body: string): Promise<ApiChatMessage> {
  const { data } = await api.patch<ApiChatMessage>(`/chat/messages/${id}`, { body })
  return data
}

export async function apiChatDeleteMessage(id: number): Promise<void> {
  await api.delete(`/chat/messages/${id}`)
}

export async function apiChatReact(id: number, emoji: string, add: boolean): Promise<ApiChatMessage> {
  const { data } = await api.post<ApiChatMessage>(`/chat/messages/${id}/reactions`, { emoji, add })
  return data
}

export async function apiChatMarkRead(id: number): Promise<void> {
  await api.post(`/chat/channels/${id}/read`)
}

export async function apiChatUnread(): Promise<ApiChatUnread> {
  const { data } = await api.get<ApiChatUnread>('/chat/unread')
  return data
}

export async function apiChatSearch(q: string): Promise<ApiChatMessage[]> {
  const { data } = await api.get<ApiChatMessage[]>('/chat/search', { params: { q } })
  return data
}

// ---------- Copilot ----------

export interface ApiCopilotAnswer {
  answer: string
  mode: 'llm' | 'grounded'
  /**
   * Why the grounded answer was used, or null when the LLM answered. Safe to
   * display: the backend sends a fixed string, never upstream error text.
   */
  reason?: string | null
}

export async function apiAskCopilot(question: string, projectId?: number): Promise<ApiCopilotAnswer> {
  const { data } = await api.post<ApiCopilotAnswer>('/copilot/ask',
    { question },
    { params: projectId != null ? { projectId } : undefined },
  )
  return data
}

// ---------- Nexus AI (RAG assistant) ----------

export interface AiSource {
  type: string
  sourceId: number
  title: string
  category: string | null
  projectId: number | null
  updatedAt: string | null
}

export interface AiConversation {
  id: string
  title: string
  createdAt: string
  updatedAt: string
  messageCount: number
}

export interface AiMessage {
  id: string
  role: 'USER' | 'ASSISTANT'
  content: string
  sources: AiSource[]
  mode: string | null
  model: string | null
  createdAt: string
}

export interface AiConversationDetail {
  conversation: AiConversation
  messages: AiMessage[]
}

export interface AiKnowledgeStatus {
  configured: boolean
  model: string
  embeddingModel: string
  indexedChunks: number
  embeddedChunks: number
  vectorsLoaded: number
  bySource: Record<string, number>
}

export interface AiReindexResult {
  sources: number
  reindexed: number
  removed: number
  vectors: number
}

export async function apiListAiConversations(): Promise<AiConversation[]> {
  const { data } = await api.get<AiConversation[]>('/ai/conversations')
  return data
}

export async function apiGetAiConversation(id: string): Promise<AiConversationDetail> {
  const { data } = await api.get<AiConversationDetail>(`/ai/conversations/${id}`)
  return data
}

export async function apiDeleteAiConversation(id: string): Promise<void> {
  await api.delete(`/ai/conversations/${id}`)
}

export async function apiAiKnowledgeStatus(): Promise<AiKnowledgeStatus> {
  const { data } = await api.get<AiKnowledgeStatus>('/ai/knowledge/status')
  return data
}

export async function apiAiReindex(full = false): Promise<AiReindexResult> {
  const { data } = await api.post<AiReindexResult>(`/ai/knowledge/reindex?full=${full}`)
  return data
}

export interface AiStreamHandlers {
  onMeta?: (meta: { conversationId: string; sources: AiSource[] }) => void
  onDelta?: (text: string) => void
  onDone?: (done: { messageId: string; conversationId: string; mode: string; model: string }) => void
  onError?: (message: string) => void
}

/**
 * Server-Sent Events over POST: the answer streams token by token from the
 * backend (which calls OpenAI server-side — the key never reaches the browser).
 */
async function streamSse(path: string, body: unknown, handlers: AiStreamHandlers): Promise<void> {
  const base = import.meta.env.VITE_API_URL || '/api'
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    Accept: 'text/event-stream',
  }
  const token = getStoredToken()
  if (token) headers.Authorization = `Bearer ${token}`

  const response = await fetch(`${base}${path}`, {
    method: 'POST',
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })

  if (!response.ok) {
    let message = `The AI request failed (HTTP ${response.status})`
    try {
      const data = (await response.json()) as { message?: string; error?: string }
      message = data.message || data.error || message
    } catch {
      // Non-JSON error body; keep the generic message.
    }
    throw new Error(message)
  }
  if (!response.body) throw new Error('Streaming is not supported in this browser')

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })

    let separator = buffer.indexOf('\n\n')
    while (separator !== -1) {
      const frame = buffer.slice(0, separator)
      buffer = buffer.slice(separator + 2)

      let event = 'message'
      const dataLines: string[] = []
      for (const line of frame.split('\n')) {
        if (line.startsWith('event:')) event = line.slice(6).trim()
        else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
      }
      if (dataLines.length > 0) {
        const raw = dataLines.join('\n')
        let payload: Record<string, unknown> = {}
        try {
          payload = JSON.parse(raw) as Record<string, unknown>
        } catch {
          payload = { text: raw }
        }
        if (event === 'meta') {
          handlers.onMeta?.(payload as unknown as { conversationId: string; sources: AiSource[] })
        } else if (event === 'delta') {
          handlers.onDelta?.(typeof payload.text === 'string' ? payload.text : '')
        } else if (event === 'done') {
          handlers.onDone?.(
            payload as unknown as { messageId: string; conversationId: string; mode: string; model: string },
          )
        } else if (event === 'error') {
          handlers.onError?.(
            typeof payload.message === 'string' ? payload.message : 'The AI service failed. Try again.',
          )
        }
      }
      separator = buffer.indexOf('\n\n')
    }
  }
}

export function streamAiAsk(
  payload: { question: string; conversationId?: string | null; projectId?: number | null },
  handlers: AiStreamHandlers,
): Promise<void> {
  return streamSse('/ai/ask', payload, handlers)
}

export function streamAiRegenerate(conversationId: string, handlers: AiStreamHandlers): Promise<void> {
  return streamSse(`/ai/conversations/${conversationId}/regenerate`, null, handlers)
}

/** The most current auth token: the live Supabase session when enabled, else the stored Nexus JWT. */
export async function getCurrentToken(): Promise<string | null> {
  if (supabaseEnabled && supabase) {
    const { data } = await supabase.auth.getSession()
    if (data.session?.access_token) return data.session.access_token
  }
  return getStoredToken()
}

/** WebSocket endpoint for live whiteboard events, authenticated with the session token. */
export function whiteboardSocketUrl(): string {
  const explicit = import.meta.env.VITE_WS_URL as string | undefined
  if (explicit) return explicit
  const base = (import.meta.env.VITE_API_URL as string | undefined) || 'http://localhost:8080'
  const wsBase = base.replace(/^http/, 'ws').replace(/\/api\/?$/, '')
  return `${wsBase}/ws/whiteboard`
}

/** Extract a human-readable message from an axios/network error. */
export function apiErrorMessage(err: unknown, fallback = 'Something went wrong'): string {
  if (axios.isAxiosError(err)) {
    const data = err.response?.data
    if (data && typeof data === 'object') {
      const body = data as Record<string, unknown>
      const detail = body.message ?? body.error
      if (typeof detail === 'string' && detail) return detail
      // Bean-validation failures come back as a flat { field: message } map with
      // no message/error key. Without this branch every rejected form showed only
      // axios's "Request failed with status code 400".
      const fieldMessages = Object.entries(body).filter(
        ([key, value]) => key !== 'status' && key !== 'timestamp' && typeof value === 'string'
      )
      if (fieldMessages.length > 0) {
        return fieldMessages.map(([, message]) => message).join('; ')
      }
    }
    return fallback
  }
  if (err instanceof Error) return err.message
  return fallback
}
