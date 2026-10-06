import axios from 'axios'

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

api.interceptors.request.use((config) => {
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
}

export async function apiCreateSprint(input: ApiSprintInput): Promise<ApiSprint> {
  const { data } = await api.post<ApiSprint>('/sprints', input)
  return data
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
}

export async function apiCreateTask(input: ApiTaskInput): Promise<ApiTask> {
  const { data } = await api.post<ApiTask>('/tasks', input)
  return data
}

export async function apiUpdateTaskStatus(id: number, status: ApiTaskStatus): Promise<ApiTask> {
  const { data } = await api.patch<ApiTask>(`/tasks/${id}/status`, { status })
  return data
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

// ---------- Copilot ----------

export interface ApiCopilotAnswer {
  answer: string
  mode: 'llm' | 'grounded'
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
    const data = err.response?.data as { message?: string; error?: string } | undefined
    return data?.message || data?.error || err.message || fallback
  }
  if (err instanceof Error) return err.message
  return fallback
}
