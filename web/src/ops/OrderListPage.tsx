import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { listOrders } from '../api/orders'
import { listOrderTypes } from '../api/orderTypes'
import { useStatusTaxonomy } from '../hooks/useStatusTaxonomy'
import { StatusBadge } from '../components/StatusBadge'
import { Pagination } from '../components/Pagination'
import { EmptyState } from '../components/EmptyState'
import { ErrorBanner } from '../components/ErrorBanner'
import { BADGE_COLORS } from '../lib/badgeColors'
import type { BadgeCategory } from '../types/domain'

const CATEGORY_ORDER: BadgeCategory[] = ['WAIT', 'AUTOMATIC', 'MANUAL', 'TERMINAL_SUCCESS', 'TERMINAL_FAILURE']
const CATEGORY_LABELS: Record<BadgeCategory, string> = {
  WAIT: 'Waiting',
  AUTOMATIC: 'In progress',
  MANUAL: 'Awaiting action',
  TERMINAL_SUCCESS: 'Completed',
  TERMINAL_FAILURE: 'Cancelled / failed',
}

export function OrderListPage() {
  const navigate = useNavigate()
  const [status, setStatus] = useState<string[]>([])
  const [orderType, setOrderType] = useState<string[]>([])
  const [customerRef, setCustomerRef] = useState('')
  const [createdFrom, setCreatedFrom] = useState('')
  const [createdTo, setCreatedTo] = useState('')
  const [hasOpenTask, setHasOpenTask] = useState(false)
  const [page, setPage] = useState(0)

  const orderTypesQuery = useQuery({ queryKey: ['order-types'], queryFn: listOrderTypes })
  const taxonomyQuery = useStatusTaxonomy()

  const ordersQuery = useQuery({
    queryKey: ['orders', { status, orderType, customerRef, createdFrom, createdTo, hasOpenTask, page }],
    queryFn: () =>
      listOrders({
        status,
        orderType,
        customerRef: customerRef || undefined,
        createdFrom: createdFrom ? `${createdFrom}T00:00:00Z` : undefined,
        createdTo: createdTo ? `${createdTo}T23:59:59Z` : undefined,
        hasOpenTask: hasOpenTask || undefined,
        page,
        size: 20,
        sort: 'createdAt,desc',
      }),
  })

  function toggle(list: string[], value: string, setList: (v: string[]) => void) {
    setPage(0)
    setList(list.includes(value) ? list.filter((v) => v !== value) : [...list, value])
  }

  function clearFilters() {
    setStatus([])
    setOrderType([])
    setCustomerRef('')
    setCreatedFrom('')
    setCreatedTo('')
    setHasOpenTask(false)
    setPage(0)
  }

  const groupedStatuses = CATEGORY_ORDER.map((category) => ({
    category,
    entries: (taxonomyQuery.data ?? []).filter((e) => e.badgeCategory === category),
  })).filter((g) => g.entries.length > 0)

  return (
    <div className="flex gap-6">
      <aside className="w-55 flex-shrink-0 space-y-6">
        <div>
          <h3 className="mb-2 text-sm font-semibold text-gray-900">Order type</h3>
          <div className="space-y-1">
            {(orderTypesQuery.data ?? []).map((ot) => (
              <label key={ot.code} className="flex items-center gap-2 text-sm text-gray-700">
                <input
                  type="checkbox"
                  checked={orderType.includes(ot.code)}
                  onChange={() => toggle(orderType, ot.code, setOrderType)}
                />
                {ot.name}
              </label>
            ))}
          </div>
        </div>

        <div>
          <h3 className="mb-2 text-sm font-semibold text-gray-900">Status</h3>
          <div className="space-y-3">
            {groupedStatuses.map(({ category, entries }) => (
              <div key={category}>
                <div className="mb-1 flex items-center gap-1.5 text-xs font-medium text-gray-500">
                  <span className={`h-1.5 w-1.5 rounded-full ${BADGE_COLORS[category].dot}`} aria-hidden="true" />
                  {CATEGORY_LABELS[category]}
                </div>
                {entries.map((entry) => (
                  <label key={entry.code} className="flex items-center gap-2 pl-3 text-sm text-gray-700">
                    <input
                      type="checkbox"
                      checked={status.includes(entry.code)}
                      onChange={() => toggle(status, entry.code, setStatus)}
                    />
                    {entry.code}
                  </label>
                ))}
              </div>
            ))}
          </div>
        </div>

        <div>
          <h3 className="mb-2 text-sm font-semibold text-gray-900">Created</h3>
          <div className="space-y-1">
            <input
              type="date"
              value={createdFrom}
              onChange={(e) => {
                setCreatedFrom(e.target.value)
                setPage(0)
              }}
              className="w-full rounded border border-gray-300 px-2 py-1 text-sm"
            />
            <input
              type="date"
              value={createdTo}
              onChange={(e) => {
                setCreatedTo(e.target.value)
                setPage(0)
              }}
              className="w-full rounded border border-gray-300 px-2 py-1 text-sm"
            />
          </div>
        </div>

        <div>
          <h3 className="mb-2 text-sm font-semibold text-gray-900">Customer ref</h3>
          <input
            value={customerRef}
            onChange={(e) => {
              setCustomerRef(e.target.value)
              setPage(0)
            }}
            className="w-full rounded border border-gray-300 px-2 py-1 text-sm"
          />
        </div>

        <label className="flex items-center gap-2 text-sm text-gray-700">
          <input
            type="checkbox"
            checked={hasOpenTask}
            onChange={(e) => {
              setHasOpenTask(e.target.checked)
              setPage(0)
            }}
          />
          Has open task
        </label>

        <button type="button" onClick={clearFilters} className="text-sm font-medium text-blue-600 hover:underline">
          Clear filters
        </button>
      </aside>

      <div className="min-w-0 flex-1">
        <div className="mb-4 flex items-center justify-between">
          <h1 className="text-lg font-semibold text-gray-900">Orders</h1>
          <button
            type="button"
            onClick={() => navigate('/ops/orders/new')}
            className="rounded bg-gray-900 px-3 py-1.5 text-sm font-medium text-white"
          >
            New order
          </button>
        </div>
        <ErrorBanner error={ordersQuery.error} />
        {ordersQuery.data && ordersQuery.data.content.length === 0 ? (
          <EmptyState message="No orders match these filters." actionLabel="Clear filters" onAction={clearFilters} />
        ) : (
          <div className="overflow-hidden rounded border border-gray-200 bg-white">
            <table className="w-full text-sm">
              <thead className="bg-gray-50 text-left text-xs font-medium uppercase text-gray-500">
                <tr>
                  <th className="px-4 py-2">Order #</th>
                  <th className="px-4 py-2">Type</th>
                  <th className="px-4 py-2">Status</th>
                  <th className="px-4 py-2">Customer ref</th>
                  <th className="px-4 py-2 text-right">Total</th>
                  <th className="px-4 py-2">Created</th>
                  <th className="px-4 py-2">Updated</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {(ordersQuery.data?.content ?? []).map((order) => (
                  <tr
                    key={order.orderId}
                    onClick={() => navigate(`/ops/orders/${order.orderId}`)}
                    className="cursor-pointer hover:bg-gray-50"
                  >
                    <td className="px-4 py-2 font-mono">{order.orderNumber}</td>
                    <td className="px-4 py-2">{order.orderTypeCode}</td>
                    <td className="px-4 py-2">
                      <StatusBadge code={order.status} />
                    </td>
                    <td className="px-4 py-2">{order.customerRef ?? '—'}</td>
                    <td className="px-4 py-2 text-right">
                      {order.currency} {order.totalAmount}
                    </td>
                    <td className="px-4 py-2 text-gray-500">{new Date(order.createdAt).toLocaleString()}</td>
                    <td className="px-4 py-2 text-gray-500">{new Date(order.updatedAt).toLocaleString()}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <div className="px-4">
              <Pagination page={page} totalPages={ordersQuery.data?.totalPages ?? 0} onChange={setPage} />
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
