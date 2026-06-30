import { apiFetch } from '../lib/api'
import type { Tenant } from '../types/domain'

export function listTenants(): Promise<Tenant[]> {
  return apiFetch('/tenants')
}

export interface CreateTenantInput {
  tenantId: string
  name: string
}

export function createTenant(input: CreateTenantInput): Promise<Tenant> {
  return apiFetch('/tenants', { method: 'POST', body: input })
}
