import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { listOrderTypes } from '../api/orderTypes'
import { ErrorBanner } from '../components/ErrorBanner'

export function OrderTypeListPage() {
  const query = useQuery({ queryKey: ['order-types'], queryFn: listOrderTypes })

  return (
    <div>
      <div className="mb-4 flex items-center justify-between">
        <h1 className="text-lg font-semibold text-gray-900">Order types</h1>
        <Link to="/admin/order-types/new" className="rounded bg-gray-900 px-3 py-1.5 text-sm font-medium text-white">
          New order type
        </Link>
      </div>
      <ErrorBanner error={query.error} />
      <div className="overflow-hidden rounded border border-gray-200 bg-white">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 text-left text-xs font-medium uppercase text-gray-500">
            <tr>
              <th className="px-4 py-2">Code</th>
              <th className="px-4 py-2">Name</th>
              <th className="px-4 py-2">Active</th>
              <th className="px-4 py-2">Workflow</th>
              <th className="px-4 py-2" />
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {(query.data ?? []).map((ot) => (
              <tr key={ot.code}>
                <td className="px-4 py-2 font-mono">{ot.code}</td>
                <td className="px-4 py-2">{ot.name}</td>
                <td className="px-4 py-2">{ot.active ? 'Yes' : 'No'}</td>
                <td className="px-4 py-2">{ot.workflowDefinitionId ? 'Published' : 'Not configured'}</td>
                <td className="px-4 py-2 text-right">
                  <Link to={`/admin/order-types/${ot.code}/workflow`} className="font-medium text-blue-600 hover:underline">
                    {ot.workflowDefinitionId ? 'Edit workflow' : 'Configure workflow'}
                  </Link>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
