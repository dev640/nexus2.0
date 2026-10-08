import type { ApiUserRole } from './api'

/**
 * Workspace administration — creating projects and handing work to other
 * people — is restricted to ADMIN and MANAGER by the API. Mirroring the rule
 * here keeps the UI from offering buttons that would come back 403.
 *
 * Keep in sync with WorkspaceManage / RolePolicy on the backend.
 */
export function canManageWorkspace(role: ApiUserRole | undefined | null): boolean {
  return role === 'ADMIN' || role === 'MANAGER'
}
