import { useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { createOrder, type LineInput } from '../api/orders'
import { listOrderTypes, getOrderTypeSchema } from '../api/orderTypes'
import { DynamicSchemaForm } from '../components/DynamicSchemaForm'
import { ErrorBanner } from '../components/ErrorBanner'

interface LineDraft {
  id: string
  itemRef: string
  quantity: string
  unitPrice: string
  attributes: Record<string, unknown>
}

export function OrderCreatePage() {
  const navigate = useNavigate()
  const [orderTypeCode, setOrderTypeCode] = useState('')
  const [customerRef, setCustomerRef] = useState('')
  const [currency, setCurrency] = useState('USD')
  const [totalAmount, setTotalAmount] = useState('')
  const [attributes, setAttributes] = useState<Record<string, unknown>>({})
  const [lines, setLines] = useState<LineDraft[]>([])

  const orderTypesQuery = useQuery({ queryKey: ['order-types'], queryFn: listOrderTypes })
  const schemaQuery = useQuery({
    queryKey: ['order-type-schema', orderTypeCode],
    queryFn: () => getOrderTypeSchema(orderTypeCode),
    enabled: !!orderTypeCode,
  })

  const mutation = useMutation({
    mutationFn: () =>
      createOrder({
        orderTypeCode,
        customerRef: customerRef || undefined,
        currency,
        totalAmount: totalAmount || undefined,
        attributes,
        lines: lines.length === 0 ? undefined : lines.map((l): LineInput => ({
          itemRef: l.itemRef,
          quantity: l.quantity,
          unitPrice: l.unitPrice,
          attributes: l.attributes,
        })),
      }),
    onSuccess: (order) => navigate(`/ops/orders/${order.orderId}`),
  })

  function addLine() {
    setLines((prev) => [...prev, { id: crypto.randomUUID(), itemRef: '', quantity: '', unitPrice: '', attributes: {} }])
  }
  function updateLine(id: string, patch: Partial<LineDraft>) {
    setLines((prev) => prev.map((l) => (l.id === id ? { ...l, ...patch } : l)))
  }
  function removeLine(id: string) {
    setLines((prev) => prev.filter((l) => l.id !== id))
  }

  const canSubmit = !!orderTypeCode && !!currency.trim() && !mutation.isPending
    && lines.every((l) => l.itemRef.trim() && l.quantity.trim() && l.unitPrice.trim())

  return (
    <div className="mx-auto max-w-3xl space-y-6">
      <h1 className="text-lg font-semibold text-gray-900">New order</h1>
      <ErrorBanner error={mutation.error} />

      <div className="rounded border border-gray-200 bg-white p-4">
        <h2 className="mb-3 text-sm font-semibold text-gray-900">Core</h2>
        <div className="grid grid-cols-2 gap-4">
          <label className="block text-sm">
            <span className="mb-1 block text-gray-600">Order type</span>
            <select
              value={orderTypeCode}
              onChange={(e) => {
                setOrderTypeCode(e.target.value)
                setAttributes({})
              }}
              className="w-full rounded border border-gray-300 px-2 py-1.5"
            >
              <option value="">Select…</option>
              {(orderTypesQuery.data ?? []).map((ot) => (
                <option key={ot.code} value={ot.code}>
                  {ot.name}
                </option>
              ))}
            </select>
          </label>
          <label className="block text-sm">
            <span className="mb-1 block text-gray-600">Customer ref</span>
            <input
              value={customerRef}
              onChange={(e) => setCustomerRef(e.target.value)}
              className="w-full rounded border border-gray-300 px-2 py-1.5"
            />
          </label>
          <label className="block text-sm">
            <span className="mb-1 block text-gray-600">Currency</span>
            <input
              value={currency}
              onChange={(e) => setCurrency(e.target.value.toUpperCase())}
              maxLength={3}
              className="w-full rounded border border-gray-300 px-2 py-1.5 font-mono"
            />
          </label>
          <label className="block text-sm">
            <span className="mb-1 block text-gray-600">Total amount</span>
            <input
              value={totalAmount}
              onChange={(e) => setTotalAmount(e.target.value)}
              placeholder="0.00"
              className="w-full rounded border border-gray-300 px-2 py-1.5"
            />
          </label>
        </div>
      </div>

      {orderTypeCode && (
        <div className="rounded border border-gray-200 bg-white p-4">
          <h2 className="mb-3 text-sm font-semibold text-gray-900">Attributes</h2>
          <DynamicSchemaForm schema={schemaQuery.data?.attributeSchema} values={attributes} onChange={setAttributes} />
        </div>
      )}

      {orderTypeCode && (
        <div className="rounded border border-gray-200 bg-white p-4">
          <div className="mb-3 flex items-center justify-between">
            <h2 className="text-sm font-semibold text-gray-900">Lines</h2>
            <button type="button" onClick={addLine} className="text-sm font-medium text-blue-600 hover:underline">
              + Add line
            </button>
          </div>
          {lines.length === 0 && <p className="text-sm text-gray-400">No lines — orders can be created without any.</p>}
          <div className="space-y-4">
            {lines.map((line) => (
              <div key={line.id} className="rounded border border-gray-200 p-3">
                <div className="grid grid-cols-4 gap-3">
                  <label className="block text-xs">
                    <span className="mb-1 block text-gray-600">Item ref</span>
                    <input
                      value={line.itemRef}
                      onChange={(e) => updateLine(line.id, { itemRef: e.target.value })}
                      className="w-full rounded border border-gray-300 px-2 py-1"
                    />
                  </label>
                  <label className="block text-xs">
                    <span className="mb-1 block text-gray-600">Quantity</span>
                    <input
                      value={line.quantity}
                      onChange={(e) => updateLine(line.id, { quantity: e.target.value })}
                      className="w-full rounded border border-gray-300 px-2 py-1"
                    />
                  </label>
                  <label className="block text-xs">
                    <span className="mb-1 block text-gray-600">Unit price</span>
                    <input
                      value={line.unitPrice}
                      onChange={(e) => updateLine(line.id, { unitPrice: e.target.value })}
                      className="w-full rounded border border-gray-300 px-2 py-1"
                    />
                  </label>
                  <div className="flex items-end justify-end">
                    <button type="button" onClick={() => removeLine(line.id)} className="text-xs text-red-600 hover:underline">
                      Remove
                    </button>
                  </div>
                </div>
                {(schemaQuery.data?.lineAttributeSchema.properties ?? null) && (
                  <div className="mt-3">
                    <DynamicSchemaForm
                      schema={schemaQuery.data?.lineAttributeSchema}
                      values={line.attributes}
                      onChange={(v) => updateLine(line.id, { attributes: v })}
                    />
                  </div>
                )}
              </div>
            ))}
          </div>
        </div>
      )}

      <button
        type="button"
        disabled={!canSubmit}
        onClick={() => mutation.mutate()}
        className="rounded bg-gray-900 px-4 py-2 text-sm font-medium text-white disabled:opacity-40"
      >
        Create order
      </button>
    </div>
  )
}
