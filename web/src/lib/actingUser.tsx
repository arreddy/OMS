import { createContext, useContext, useState, type ReactNode } from 'react'
import { getActingUser, setActingUser } from './api'

interface ActingUserState {
  user: string
  setUser: (user: string) => void
}

const ActingUserContext = createContext<ActingUserState | null>(null)

export function ActingUserProvider({ children }: { children: ReactNode }) {
  const [user, setUserState] = useState(getActingUser())

  function setUser(next: string) {
    const trimmed = next.trim() || 'ops-user'
    setActingUser(trimmed)
    setUserState(trimmed)
  }

  return <ActingUserContext.Provider value={{ user, setUser }}>{children}</ActingUserContext.Provider>
}

export function useActingUser(): ActingUserState {
  const ctx = useContext(ActingUserContext)
  if (!ctx) throw new Error('useActingUser must be used within ActingUserProvider')
  return ctx
}
