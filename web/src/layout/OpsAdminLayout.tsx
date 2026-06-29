import { NavLink, Outlet } from 'react-router-dom'
import { useActingUser } from '../lib/actingUser'
import clsx from 'clsx'

const navLinkClass = ({ isActive }: { isActive: boolean }) =>
  clsx(
    'rounded px-3 py-1.5 text-sm font-medium',
    isActive ? 'bg-gray-900 text-white' : 'text-gray-700 hover:bg-gray-100',
  )

export function OpsAdminLayout() {
  const { user, setUser } = useActingUser()

  return (
    <div className="min-h-screen bg-gray-50">
      <header className="border-b border-gray-200 bg-white">
        <div className="mx-auto flex max-w-6xl items-center justify-between px-4 py-3">
          <nav className="flex items-center gap-1">
            <span className="mr-4 text-sm font-semibold text-gray-900">OMS</span>
            <NavLink to="/ops/orders" className={navLinkClass}>
              Orders
            </NavLink>
            <NavLink to="/ops/tasks" className={navLinkClass}>
              Tasks
            </NavLink>
            <NavLink to="/admin/order-types" className={navLinkClass}>
              Order Types
            </NavLink>
          </nav>
          <label className="flex items-center gap-2 text-sm text-gray-600">
            Acting as
            <input
              value={user}
              onChange={(e) => setUser(e.target.value)}
              className="w-32 rounded border border-gray-300 px-2 py-1 text-sm"
            />
          </label>
        </div>
      </header>
      <main className="mx-auto max-w-6xl px-4 py-6">
        <Outlet />
      </main>
    </div>
  )
}
