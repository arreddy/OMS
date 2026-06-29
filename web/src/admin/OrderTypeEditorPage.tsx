import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { createOrderType } from '../api/orderTypes'
import { ErrorBanner } from '../components/ErrorBanner'
import { SchemaBuilder, rowsToJsonSchema, type FieldRow } from './SchemaBuilder'

export function OrderTypeEditorPage() {
  const navigate = useNavigate()
  const [code, setCode] = useState('')
  const [name, setName] = useState('')
  const [attributeRows, setAttributeRows] = useState<FieldRow[]>([])
  const [lineRows, setLineRows] = useState<FieldRow[]>([])

  const mutation = useMutation({
    mutationFn: () =>
      createOrderType({
        code,
        name,
        attributeSchema: rowsToJsonSchema(attributeRows),
        lineAttributeSchema: rowsToJsonSchema(lineRows),
      }),
    onSuccess: () => navigate(`/admin/order-types/${code}/workflow`),
  })

  return (
    <div className="mx-auto max-w-4xl space-y-6">
      <h1 className="text-lg font-semibold text-gray-900">New order type</h1>
      <ErrorBanner error={mutation.error} />

      <div className="rounded border border-gray-200 bg-white p-4">
        <h2 className="mb-3 text-sm font-semibold text-gray-900">Core</h2>
        <div className="grid grid-cols-2 gap-4">
          <label className="block text-sm">
            <span className="mb-1 block text-gray-600">Code</span>
            <input
              value={code}
              onChange={(e) => setCode(e.target.value.toUpperCase())}
              className="w-full rounded border border-gray-300 px-2 py-1.5 font-mono"
              placeholder="B2B_BULK"
            />
          </label>
          <label className="block text-sm">
            <span className="mb-1 block text-gray-600">Name</span>
            <input
              value={name}
              onChange={(e) => setName(e.target.value)}
              className="w-full rounded border border-gray-300 px-2 py-1.5"
              placeholder="B2B Bulk Order"
            />
          </label>
        </div>
        <p className="mt-2 text-xs text-gray-400">
          New order types are active by default — there's currently no API to deactivate one after creation.
        </p>
      </div>

      <div className="rounded border border-gray-200 bg-white p-4">
        <h2 className="mb-3 text-sm font-semibold text-gray-900">Order attributes</h2>
        <SchemaBuilder rows={attributeRows} onChange={setAttributeRows} />
      </div>

      <div className="rounded border border-gray-200 bg-white p-4">
        <h2 className="mb-3 text-sm font-semibold text-gray-900">Line attributes</h2>
        <SchemaBuilder rows={lineRows} onChange={setLineRows} />
      </div>

      <button
        type="button"
        disabled={!code.trim() || !name.trim() || mutation.isPending}
        onClick={() => mutation.mutate()}
        className="rounded bg-gray-900 px-4 py-2 text-sm font-medium text-white disabled:opacity-40"
      >
        Create &amp; configure workflow
      </button>
    </div>
  )
}
