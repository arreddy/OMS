import { Outlet } from 'react-router-dom'

/** No ops/admin chrome here on purpose — see UI spec §3, §5 ("hard boundary"). */
export function CustomerLayout() {
  return (
    <div className="min-h-screen bg-gray-50">
      <main className="mx-auto max-w-[640px] px-4 py-6">
        <Outlet />
      </main>
    </div>
  )
}
