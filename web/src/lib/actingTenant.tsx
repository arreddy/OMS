import { createContext, useContext, useState, type ReactNode } from 'react'
import { getActingTenant, setActingTenant } from './api'

interface ActingTenantState {
  tenant: string
  setTenant: (tenant: string) => void
}

const ActingTenantContext = createContext<ActingTenantState | null>(null)

export function ActingTenantProvider({ children }: { children: ReactNode }) {
  const [tenant, setTenantState] = useState(getActingTenant())

  function setTenant(next: string) {
    const trimmed = next.trim() || 'default'
    setActingTenant(trimmed)
    setTenantState(trimmed)
  }

  return <ActingTenantContext.Provider value={{ tenant, setTenant }}>{children}</ActingTenantContext.Provider>
}

export function useActingTenant(): ActingTenantState {
  const ctx = useContext(ActingTenantContext)
  if (!ctx) throw new Error('useActingTenant must be used within ActingTenantProvider')
  return ctx
}
