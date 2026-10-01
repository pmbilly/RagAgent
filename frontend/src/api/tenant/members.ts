import { get, post, put, del } from '@/utils/request'

// TenantRole mirrors internal/types/tenant_member.go's four-role enum.
// Keep the string values aligned with the Go constants.
export type TenantRole = 'owner' | 'admin' | 'contributor' | 'viewer'

export type TenantMemberStatus = 'active' | 'invited' | 'suspended'

// TenantMember is the API projection of a (user, tenant) membership row,
// already joined with the user's email/username/avatar by the backend.
export interface TenantMember {
  userId: string
  email: string
  username: string
  avatar?: string
  role: TenantRole
  status: TenantMemberStatus
  invitedBy?: string | null
  joinedAt: string
}

export interface ListMembersResponse {
  success: boolean
  data?: {
    members: TenantMember[]
    total: number
    page?: number
    pageSize?: number
  }
  message?: string
}

export interface ListMembersParams {
  page?: number
  pageSize?: number
  /** 按邮箱/用户名筛选（服务端模糊匹配） */
  q?: string
}

function buildMembersQuery(params: ListMembersParams | undefined): string {
  if (!params) return ''
  const u = new URLSearchParams()
  if (params.page != null && params.page > 0) u.set('page', String(params.page))
  if (params.pageSize != null && params.pageSize > 0) u.set('pageSize', String(params.pageSize))
  const q = params.q?.trim()
  if (q) u.set('q', q)
  const qs = u.toString()
  return qs ? `?${qs}` : ''
}

export interface AddMemberRequest {
  email: string
  role: TenantRole
}

export interface AddMemberResponse {
  success: boolean
  data?: TenantMember
  message?: string
}

export interface SimpleResponse {
  success: boolean
  message?: string
}

/**
 * 分页列出空间成员。
 * Backend: GET /api/v1/tenants/:id/members (Viewer+)。查询参数：`q`、`page`、`pageSize`。
 */
export async function listMembers(
  tenantId: number,
  params: ListMembersParams = {},
): Promise<ListMembersResponse> {
  const qs = buildMembersQuery(params)
  const resp = (await get(
    `/api/v1/tenants/${tenantId}/members${qs}`,
  )) as unknown as { members: TenantMember[]; page: number; pageSize: number; total: number }
  // 后端 200 {members,page,pageSize,total}（§2.1 无信封）；适配成既有的 success/data 契约
  return {
    success: true,
    data: {
      members: resp?.members ?? [],
      total: resp?.total ?? 0,
      page: resp?.page,
      pageSize: resp?.pageSize,
    },
  }
}

/**
 * 遍历分页拉取空间的全部成员（每页最大 100，最多 500 页兜底）。
 * 用于「退出空间」等对全量成员的轻量校验；普通表格请直接使用 {@link listMembers} 分页接口。
 */
export async function fetchAllTenantMembers(tenantId: number): Promise<TenantMember[]> {
  const pageSize = 100
  let page = 1
  const out: TenantMember[] = []
  let total = Number.POSITIVE_INFINITY
  for (let guard = 0; guard < 500 && out.length < total; guard++) {
    const resp = await listMembers(tenantId, { page, pageSize: pageSize })
    if (!resp.success || !resp.data) break
    total = resp.data.total
    const batch = resp.data.members || []
    if (batch.length === 0 && page >= 2) break
    out.push(...batch)
    if (batch.length < pageSize) break
    page++
  }
  return out
}

/**
 * Invite an existing user (by email) to the tenant with the given role.
 * Backend: POST /api/v1/tenants/:id/members (Owner+).
 *
 * Returns 404 when the email does not match any registered user — the
 * caller should ask the invitee to register first. PR 3 does not yet
 * support email-based invites for users who don't have an account.
 */
export async function addMember(
  tenantId: number,
  body: AddMemberRequest,
): Promise<AddMemberResponse> {
  // 后端 201 裸 TenantMemberResponse（§2.1）
  const resp = (await post(`/api/v1/tenants/${tenantId}/members`, body)) as unknown as TenantMember
  return { success: true, data: resp }
}

/**
 * Change an existing member's role.
 * Backend: PUT /api/v1/tenants/:id/members/:userId (Owner+).
 *
 * Returns 409 when this would demote the last active Owner of the tenant.
 */
export async function updateMemberRole(
  tenantId: number,
  userId: string,
  role: TenantRole,
): Promise<SimpleResponse> {
  await put(`/api/v1/tenants/${tenantId}/members/${userId}`, { role })
  return { success: true }
}

/**
 * Remove a member from the tenant.
 * Backend: DELETE /api/v1/tenants/:id/members/:userId (Owner+).
 *
 * Returns 409 when this would remove the last active Owner.
 */
export async function removeMember(
  tenantId: number,
  userId: string,
): Promise<SimpleResponse> {
  await del(`/api/v1/tenants/${tenantId}/members/${userId}`)
  return { success: true }
}

/**
 * Quit the tenant on your own. Same last-Owner invariant as
 * removeMember, but does NOT require Owner+ — any active member can
 * call it.
 * Backend: POST /api/v1/tenants/:id/leave (Viewer+).
 */
export async function leaveTenant(tenantId: number): Promise<SimpleResponse> {
  await post(`/api/v1/tenants/${tenantId}/leave`)
  return { success: true }
}
