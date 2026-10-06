// ---------------------------------------------------------------------------
// ID mapping — the backend uses numeric IDs, the frontend historically used
// string IDs. Prefixed strings keep them visually distinct and traceable.
// ---------------------------------------------------------------------------

export const toProjectId = (n: number) => `p-${n}`
export const toSprintId = (n: number) => `s-${n}`
export const toTaskId = (n: number) => `t-${n}`
// Exported so pages can match a signed-in user against a task's assigneeId
// without re-deriving the id format.
export const toUserId = (n: number) => `u-${n}`

/**
 * The numeric user id behind a store member id like "u-7". Needed wherever the
 * API takes a numeric id but the UI only holds the member form (avatars,
 * employee codes). Returns null rather than NaN so callers can fall back.
 */
export function numericUserId(memberId: string): number | null {
  const n = Number(String(memberId).replace(/^u-/, ''))
  return Number.isFinite(n) && n > 0 ? n : null
}

export function parseId(value: string): number {
  const n = Number(String(value).replace(/^[a-z]+-/, ''))
  if (!Number.isFinite(n)) {
    throw new Error(`Invalid entity id: ${value}`)
  }
  return n
}
