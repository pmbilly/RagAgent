import { get, post, del } from '@/utils/request'
import type { TenantMember, TenantRole } from '@/api/tenant/members'

// TenantInvitationStatus mirrors internal/types/tenant_invitation.go's
// five-state machine. pending is the only non-terminal state; the rest
// are recorded for the audit trail.
export type TenantInvitationStatus =
  | 'pending'
  | 'accepted'
  | 'declined'
  | 'revoked'
  | 'expired'

// TenantInvitation is the API projection of a tenant_invitations row,
// hydrated with the inviter / invitee user fields and the tenant name
// when the backend has them. Missing optional fields render as the
// raw id in the UI rather than dropping the row.
export interface TenantInvitation {
  id: number
  tenantId: number
  tenantName?: string
  inviteeUserId: string
  invitee_email?: string
  invitee_name?: string
  invitedBy?: string | null
  inviter_email?: string
  inviter_name?: string
  role: TenantRole
  status: TenantInvitationStatus
  message?: string
  expiresAt: string
  responded_at?: string | null
  created_at: string
  // inviteUrl is set on share-link rows that are still pending. The
  // backend re-emits it on every list/get so Owners can copy the
  // link on demand without "copy now or revoke" pressure.
  inviteUrl?: string
  // isShareLink distinguishes share-link rows (no specific invitee,
  // multi-use, copyable URL) from per-user invitations.
  isShareLink?: boolean
  // acceptedCount counts how many users have completed registration
  // through this invitation. Surfaced in the management UI for
  // share-link rows ("已加入 N 人").
  acceptedCount?: number
}

export interface ListInvitationsResponse {
  success: boolean
  data?: {
    invitations: TenantInvitation[]
    total: number
    page?: number
    pageSize?: number
  }
  message?: string
}

export interface ListTenantInvitationsParams {
  includeTerminal?: boolean
  page?: number
  pageSize?: number
}

function buildTenantInvitationsQuery(options: ListTenantInvitationsParams): string {
  const u = new URLSearchParams()
  if (options.includeTerminal) u.set('include_terminal', 'true')
  if (options.page != null && options.page > 0) u.set('page', String(options.page))
  if (options.pageSize != null && options.pageSize > 0) u.set('pageSize', String(options.pageSize))
  const qs = u.toString()
  return qs ? `?${qs}` : ''
}

export interface CreateInvitationRequest {
  email: string
  role: TenantRole
  message?: string
}

export interface CreateInvitationResponse {
  success: boolean
  // With tenant.auto_accept_invitation enabled the backend returns a
  // TenantMember (userId set) instead of a pending TenantInvitation.
  data?: TenantInvitation | TenantMember
  message?: string
}

export interface SimpleResponse {
  success: boolean
  message?: string
}

export interface AcceptInvitationResponse {
  success: boolean
  data?: {
    membership: {
      tenantId: number
      role: TenantRole
      status: string
      joinedAt: string
    }
  }
  message?: string
}

// AcceptInvitationByTokenResponse：已登录用户用 token 加入空间的响应（tenantName 供前端展示）。
export interface AcceptInvitationByTokenResponse {
  success: boolean
  data?: {
    membership: {
      tenantId: number
      role: TenantRole
      status: string
      joinedAt: string
    }
    tenantName?: string
  }
  message?: string
}

export interface PendingCountResponse {
  success: boolean
  data?: { pendingCount: number }
  message?: string
}

/**
 * List invitations for a tenant. Defaults to pending only; pass
 * includeTerminal=true to also surface accepted/declined/revoked/
 * expired rows for the history view. Supports `page` / `pageSize`.
 * Backend: GET /api/v1/tenants/:id/invitations (Viewer+).
 */
export async function listTenantInvitations(
  tenantId: number,
  options: ListTenantInvitationsParams = {},
): Promise<ListInvitationsResponse> {
  const qs = buildTenantInvitationsQuery(options)
  const resp = (await get(
    `/api/v1/tenants/${tenantId}/invitations${qs}`,
  )) as unknown as {
    invitations: TenantInvitation[]
    page?: number
    pageSize?: number
    total: number
  }
  // 后端 200 裸 {invitations,page,pageSize,total}（§2.1）；适配成既有的 success/data 契约
  return {
    success: true,
    data: {
      invitations: resp?.invitations ?? [],
      total: resp?.total ?? 0,
      page: resp?.page,
      pageSize: resp?.pageSize,
    },
  }
}

/**
 * Send a new invitation. The invitee will see it in /me/invitations
 * and must accept before they actually become a member.
 * Backend: POST /api/v1/tenants/:id/invitations (Owner+).
 *
 * 404 when the email is not a registered user (ask them to register).
 * 409 when an existing pending invitation already covers this pair, or
 * when the invitee is already an active member.
 */
export async function createInvitation(
  tenantId: number,
  body: CreateInvitationRequest,
): Promise<CreateInvitationResponse> {
  // 后端 201 裸邀请投影（auto-accept 开启时改回成员投影）；适配成 success/data 契约
  const resp = (await post(
    `/api/v1/tenants/${tenantId}/invitations`,
    body,
  )) as unknown as TenantInvitation | TenantMember
  return { success: true, data: resp }
}

/**
 * Revoke a still-pending invitation. Already-finalised rows return
 * 409; rows from another tenant render as 404 to avoid existence
 * leaks across tenants.
 * Backend: DELETE /api/v1/tenants/:id/invitations/:inv_id (Owner+).
 */
export async function revokeInvitation(
  tenantId: number,
  invId: number,
): Promise<SimpleResponse> {
  // 后端 204 无体（§2.1）；适配成既有的 success 契约
  await del(`/api/v1/tenants/${tenantId}/invitations/${invId}`)
  return { success: true }
}

/**
 * List MY invitations. Defaults to pending only — the inbox page
 * filters terminal rows by default; pass includeTerminal=true for a
 * history view if/when the UI grows one.
 * Backend: GET /api/v1/me/invitations (authenticated).
 */
export async function listMyInvitations(
  options: { includeTerminal?: boolean } = {},
): Promise<ListInvitationsResponse> {
  const qs = options.includeTerminal ? '?include_terminal=true' : ''
  const resp = (await get(`/api/v1/me/invitations${qs}`)) as unknown as {
    invitations: TenantInvitation[]
    total: number
  }
  // 后端 200 裸 {invitations,total}（§2.1）；适配成既有的 success/data 契约
  return {
    success: true,
    data: { invitations: resp?.invitations ?? [], total: resp?.total ?? 0 },
  }
}

/**
 * Lightweight pending-count endpoint used by the avatar-row badge
 * poller. Separate from the list endpoint so polling doesn't transfer
 * the full payload every cycle.
 * Backend: GET /api/v1/me/invitations/pending-count (authenticated).
 */
export async function getMyPendingInvitationCount(): Promise<PendingCountResponse> {
  const resp = (await get(
    `/api/v1/me/invitations/pending-count`,
  )) as unknown as { pendingCount: number }
  // 后端 200 裸 {pendingCount}（§2.1）；适配成既有的 success/data 契约
  return { success: true, data: { pendingCount: resp?.pendingCount ?? 0 } }
}

/**
 * Accept one of MY pending invitations. On success the backend also
 * creates the tenant_members row in the same flow; the caller should
 * then refresh memberships in the auth store.
 * Backend: POST /api/v1/me/invitations/:inv_id/accept (authenticated).
 */
export async function acceptInvitation(invId: number): Promise<AcceptInvitationResponse> {
  // 后端 200 裸 {membership,tenantName}（§2.1）；适配成既有的 success/data 契约
  const resp = (await post(
    `/api/v1/me/invitations/${invId}/accept`,
  )) as unknown as AcceptInvitationResponse['data']
  return { success: true, data: resp }
}

/**
 * 已登录用户用共享链接 token 加入空间（需鉴权，不创建新账号）。
 * 用于 invite_only 模式下的邀请链接流程：链接导向登录而非注册，登录后再兑换 token。
 * Backend: POST /api/v1/me/invitations/accept-by-token (authenticated).
 */
export async function acceptInvitationByToken(
  token: string,
): Promise<AcceptInvitationByTokenResponse> {
  // 后端 200 裸 {membership,tenantName}（§2.1）；适配成既有的 success/data 契约
  const resp = (await post(
    `/api/v1/me/invitations/accept-by-token`,
    { token },
  )) as unknown as AcceptInvitationByTokenResponse['data']
  return { success: true, data: resp }
}

/**
 * Decline one of MY pending invitations.
 * Backend: POST /api/v1/me/invitations/:inv_id/decline (authenticated).
 */
export async function declineInvitation(invId: number): Promise<SimpleResponse> {
  // 后端 204 无体（§2.1）；适配成既有的 success 契约
  await post(`/api/v1/me/invitations/${invId}/decline`)
  return { success: true }
}

// ---- share-link API ----------------------------------------------------

export interface CreateInviteLinkRequest {
  role: TenantRole
  message?: string
}

export interface CreateInviteLinkResponse {
  success: boolean
  data?: TenantInvitation
  message?: string
}

/**
 * Generate a multi-use share-link invitation for the tenant. The
 * returned row carries `inviteUrl` (composed from the persisted
 * plaintext token) which the SPA copies into clipboards. The link
 * stays valid until expiry or revocation; revoking is the same DELETE
 * as a per-user invitation.
 *
 * Backend: POST /api/v1/tenants/:id/invite-links (Owner+).
 */
export async function createInviteLink(
  tenantId: number,
  body: CreateInviteLinkRequest,
): Promise<CreateInviteLinkResponse> {
  // 后端 201 裸邀请投影（§2.1）；适配成既有的 success/data 契约
  const resp = (await post(
    `/api/v1/tenants/${tenantId}/invite-links`,
    body,
  )) as unknown as TenantInvitation
  return { success: true, data: resp }
}
