import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { listTenants } from '../api/tenants'
import { ErrorBanner } from '../components/ErrorBanner'

export function TenantListPage() {
  const query = useQuery({ queryKey: ['tenants'], queryFn: listTenants })

  return (
    <div>
      <div className="mb-4 flex items-center justify-between">
        <h1 className="text-lg font-semibold text-gray-900">Tenants</h1>
        <Link to="/admin/tenants/new" className="rounded bg-gray-900 px-3 py-1.5 text-sm font-medium text-white">
          New tenant
        </Link>
      </div>
      <ErrorBanner error={query.error} />
      <div className="overflow-hidden rounded border border-gray-200 bg-white">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 text-left text-xs font-medium uppercase text-gray-500">
            <tr>
              <th className="px-4 py-2">Tenant ID</th>
              <th className="px-4 py-2">Name</th>
              <th className="px-4 py-2">Active</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {(query.data ?? []).map((t) => (
              <tr key={t.tenantId}>
                <td className="px-4 py-2 font-mono">{t.tenantId}</td>
                <td className="px-4 py-2">{t.name}</td>
                <td className="px-4 py-2">{t.active ? 'Yes' : 'No'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
