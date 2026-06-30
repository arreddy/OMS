const ACTING_USER_KEY = 'oms.actingUser'
const ACTING_TENANT_KEY = 'oms.actingTenant'

export function getActingUser(): string {
  return localStorage.getItem(ACTING_USER_KEY) || 'ops-user'
}

export function setActingUser(user: string): void {
  localStorage.setItem(ACTING_USER_KEY, user)
}

export function getActingTenant(): string {
  return localStorage.getItem(ACTING_TENANT_KEY) || 'default'
}

export function setActingTenant(tenant: string): void {
  localStorage.setItem(ACTING_TENANT_KEY, tenant)
}

export class ApiError extends Error {
  status: number
  violations?: string[]

  constructor(status: number, message: string, violations?: string[]) {
    super(message)
    this.status = status
    this.violations = violations
  }

  get isConflict(): boolean {
    return this.status === 409
  }
}

type QueryValue = string | number | boolean | undefined | null | string[]

interface RequestOptions {
  method?: string
  body?: unknown
  /** Sent as the If-Match header — every optimistically-locked mutation needs this. */
  ifMatch?: number
  query?: Record<string, QueryValue>
}

function buildQuery(query?: RequestOptions['query']): string {
  if (!query) return ''
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue
    if (Array.isArray(value)) {
      value.forEach((v) => params.append(key, v))
    } else {
      params.append(key, String(value))
    }
  }
  const qs = params.toString()
  return qs ? `?${qs}` : ''
}

export async function apiFetch<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    'X-User-Id': getActingUser(),
    'X-Tenant-Id': getActingTenant(),
  }
  if (options.ifMatch !== undefined) {
    headers['If-Match'] = String(options.ifMatch)
  }

  const response = await fetch(path + buildQuery(options.query), {
    method: options.method ?? 'GET',
    headers,
    body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
  })

  const text = await response.text()
  const data = text ? JSON.parse(text) : undefined

  if (!response.ok) {
    throw new ApiError(response.status, data?.message ?? response.statusText, data?.violations)
  }
  return data as T
}
