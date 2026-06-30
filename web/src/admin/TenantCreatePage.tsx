import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { createTenant } from '../api/tenants'
import { ErrorBanner } from '../components/ErrorBanner'

export function TenantCreatePage() {
  const navigate = useNavigate()
  const [tenantId, setTenantId] = useState('')
  const [name, setName] = useState('')

  const mutation = useMutation({
    mutationFn: () => createTenant({ tenantId, name }),
    onSuccess: () => navigate('/admin/tenants'),
  })

  return (
    <div className="mx-auto max-w-2xl space-y-6">
      <h1 className="text-lg font-semibold text-gray-900">New tenant</h1>
      <ErrorBanner error={mutation.error} />

      <div className="rounded border border-gray-200 bg-white p-4">
        <div className="grid grid-cols-2 gap-4">
          <label className="block text-sm">
            <span className="mb-1 block text-gray-600">Tenant ID</span>
            <input
              value={tenantId}
              onChange={(e) => setTenantId(e.target.value)}
              className="w-full rounded border border-gray-300 px-2 py-1.5 font-mono"
              placeholder="acme"
            />
          </label>
          <label className="block text-sm">
            <span className="mb-1 block text-gray-600">Name</span>
            <input
              value={name}
              onChange={(e) => setName(e.target.value)}
              className="w-full rounded border border-gray-300 px-2 py-1.5"
              placeholder="Acme Corp"
            />
          </label>
        </div>
        <p className="mt-2 text-xs text-gray-400">
          New tenants are active by default. Use the "Acting tenant" field in the header to switch into this tenant
          once it's created.
        </p>
      </div>

      <button
        type="button"
        disabled={!tenantId.trim() || !name.trim() || mutation.isPending}
        onClick={() => mutation.mutate()}
        className="rounded bg-gray-900 px-4 py-2 text-sm font-medium text-white disabled:opacity-40"
      >
        Create tenant
      </button>
    </div>
  )
}
