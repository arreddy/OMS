import { Fragment, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useParams } from 'react-router-dom'
import { getOrder, updateLine } from '../api/orders'
import { getOrderTypeSchema } from '../api/orderTypes'
import { fireTransition, getWorkflow, getWorkflowDefinition } from '../api/workflow'
import { listTasks } from '../api/tasks'
import { StatusBadge } from '../components/StatusBadge'
import { ErrorBanner } from '../components/ErrorBanner'
import { ConflictBanner } from '../components/ConflictBanner'
import { ApiError } from '../lib/api'
import type { OrderLine } from '../types/domain'

const NON_TASK_TRIGGER_TYPES = new Set(['EVENT', 'API_ACTION', 'TIMER'])
const OPEN_TASK_STATUSES = ['UNASSIGNED', 'ASSIGNED', 'IN_PROGRESS', 'ESCALATED']

export function OrderDetailPage() {
  const { orderId } = useParams<{ orderId: string }>()
  const queryClient = useQueryClient()
  const [conflict, setConflict] = useState(false)

  const orderQuery = useQuery({ queryKey: ['order', orderId], queryFn: () => getOrder(orderId!), enabled: !!orderId })
  const workflowQuery = useQuery({ queryKey: ['workflow', orderId], queryFn: () => getWorkflow(orderId!), enabled: !!orderId })
  const definitionQuery = useQuery({
    queryKey: ['workflow-definition', workflowQuery.data?.workflowDefinitionId],
    queryFn: () => getWorkflowDefinition(workflowQuery.data!.workflowDefinitionId),
    enabled: !!workflowQuery.data,
  })
  const tasksQuery = useQuery({
    queryKey: ['tasks', { orderId }],
    queryFn: () => listTasks({ orderId, size: 5 }),
    enabled: !!orderId,
  })
  const schemaQuery = useQuery({
    queryKey: ['order-type-schema', orderQuery.data?.orderTypeCode],
    queryFn: () => getOrderTypeSchema(orderQuery.data!.orderTypeCode),
    enabled: !!orderQuery.data,
  })

  const transitionMutation = useMutation({
    mutationFn: ({ triggerType, triggerCode }: { triggerType: string; triggerCode: string | null }) =>
      fireTransition(orderId!, triggerType as never, triggerCode),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['workflow', orderId] })
      queryClient.invalidateQueries({ queryKey: ['order', orderId] })
      queryClient.invalidateQueries({ queryKey: ['tasks', { orderId }] })
    },
  })

  function refreshAfterConflict() {
    setConflict(false)
    queryClient.invalidateQueries({ queryKey: ['order', orderId] })
    queryClient.invalidateQueries({ queryKey: ['workflow', orderId] })
  }

  if (orderQuery.isLoading || !orderQuery.data) {
    return <p className="text-sm text-gray-500">Loading order…</p>
  }
  const order = orderQuery.data
  const workflow = workflowQuery.data
  const definition = definitionQuery.data
  const openTask = tasksQuery.data?.content.find((t) => OPEN_TASK_STATUSES.includes(t.status))

  return (
    <div className="space-y-6">
      {conflict && <ConflictBanner recordLabel="order" onRefresh={refreshAfterConflict} />}
      <ErrorBanner error={orderQuery.error ?? workflowQuery.error} />

      {/* Zone A — header band */}
      <div className="rounded border border-gray-200 bg-white p-4">
        <div className="flex items-start justify-between">
          <div>
            <p className="font-mono text-lg font-semibold text-gray-900">{order.orderNumber}</p>
            <p className="text-sm text-gray-500">
              {order.orderTypeCode} · Customer: {order.customerRef ?? '—'}
            </p>
          </div>
          <div className="text-right">
            <StatusBadge code={order.status} />
            <p className="mt-1 text-sm text-gray-700">
              {order.currency} {order.totalAmount}
            </p>
          </div>
        </div>
        <div className="mt-3 flex items-center justify-between text-xs text-gray-400">
          <span>
            Created {new Date(order.createdAt).toLocaleString()} · Updated {new Date(order.updatedAt).toLocaleString()}
          </span>
          <a href="#workflow-panel" className="font-medium text-blue-600 hover:underline">
            View workflow history
          </a>
        </div>
      </div>

      {/* Zone B — lines */}
      <LinesTable orderId={order.orderId} lines={order.lines} terminal={workflow?.terminal ?? false} onConflict={() => setConflict(true)} />

      {/* Zone C — workflow panel */}
      <div id="workflow-panel" className="rounded border border-gray-200 bg-white p-4">
        <h2 className="mb-3 text-sm font-semibold text-gray-900">Workflow</h2>

        {definition && workflow && (
          <div className="mb-4 flex flex-wrap gap-2">
            {definition.states.map((s) => {
              const visited = workflow.history.some((h) => h.toStateCode === s.code) || s.initial
              const isCurrent = s.code === workflow.currentState
              return (
                <span
                  key={s.stateId}
                  className={
                    'rounded border px-2 py-1 text-xs font-medium ' +
                    (isCurrent
                      ? 'border-gray-900 bg-gray-900 text-white'
                      : visited
                        ? 'border-gray-300 bg-gray-100 text-gray-700'
                        : 'border-dashed border-gray-300 text-gray-400')
                  }
                  title={isCurrent ? 'Current state' : visited ? 'Visited' : 'Not visited'}
                >
                  {s.code}
                </span>
              )
            })}
            <p className="mt-1 w-full text-xs text-gray-400">
              States are shown in definition order, not a strict timeline — this workflow can branch, so "not visited" may
              mean either "not reached yet" or "reached via a different path."
            </p>
          </div>
        )}

        {workflow && !workflow.terminal && (
          <div className="mb-4">
            <h3 className="mb-1 text-xs font-semibold uppercase text-gray-500">Available actions</h3>
            <div className="flex flex-wrap gap-2">
              {workflow.validNextTransitions
                .filter((t) => NON_TASK_TRIGGER_TYPES.has(t.triggerType) && t.triggerCode)
                .map((t) => (
                  <button
                    key={`${t.triggerType}-${t.triggerCode}`}
                    type="button"
                    disabled={transitionMutation.isPending}
                    onClick={() => transitionMutation.mutate({ triggerType: t.triggerType, triggerCode: t.triggerCode })}
                    className="rounded border border-gray-300 px-3 py-1.5 text-sm hover:bg-gray-50 disabled:opacity-50"
                  >
                    Fire {t.triggerCode} → {t.toStateCode}
                  </button>
                ))}
              {workflow.validNextTransitions.every((t) => !NON_TASK_TRIGGER_TYPES.has(t.triggerType)) && (
                <p className="text-sm text-gray-400">No directly-fireable actions from this state.</p>
              )}
            </div>
          </div>
        )}

        {openTask && (
          <div className="mb-4 flex items-center justify-between rounded border border-amber-300 bg-amber-50 px-3 py-2 text-sm">
            <span>
              Awaiting: <strong>{openTask.taskType}</strong>, assigned to {openTask.assigneeId ?? openTask.assigneeGroup ?? '—'}
            </span>
            <Link to={`/ops/tasks/${openTask.taskId}`} className="font-medium text-blue-700 hover:underline">
              Go to task
            </Link>
          </div>
        )}

        {workflow && workflow.validNextTransitions.some((t) => t.triggerType === 'TIMER') && (
          <p className="mb-4 text-sm text-gray-500">Waiting for an external signal — no action available here.</p>
        )}

        <h3 className="mb-1 text-xs font-semibold uppercase text-gray-500">Transition history</h3>
        <div className="max-h-64 overflow-y-auto rounded border border-gray-100">
          <table className="w-full text-sm">
            <thead className="sticky top-0 bg-gray-50 text-left text-xs font-medium uppercase text-gray-500">
              <tr>
                <th className="px-3 py-1.5">From → To</th>
                <th className="px-3 py-1.5">Trigger</th>
                <th className="px-3 py-1.5">By</th>
                <th className="px-3 py-1.5">When</th>
                <th className="px-3 py-1.5">Comment</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {(workflow?.history ?? []).map((h, i) => (
                <tr key={i}>
                  <td className="px-3 py-1.5">
                    {h.fromStateCode ?? '∅'} → {h.toStateCode}
                  </td>
                  <td className="px-3 py-1.5 text-gray-500">{h.triggerCode ?? h.triggerType ?? '—'}</td>
                  <td className="px-3 py-1.5 text-gray-500">{h.triggeredBy}</td>
                  <td className="px-3 py-1.5 text-gray-500">{new Date(h.occurredAt).toLocaleString()}</td>
                  <td className="px-3 py-1.5 text-gray-500">{h.comment ?? '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {/* Zone D — extension attributes */}
      <details className="rounded border border-gray-200 bg-white p-4">
        <summary className="cursor-pointer text-sm font-semibold text-gray-900">Extension attributes</summary>
        <dl className="mt-3 grid grid-cols-[max-content_1fr] gap-x-4 gap-y-1 text-sm">
          {Object.entries((order.attributes as Record<string, unknown>) ?? {}).map(([key, value]) => {
            const label = schemaQuery.data?.attributeSchema.properties?.[key]?.title ?? key
            return (
              <Fragment key={key}>
                <dt className="font-medium text-gray-500">{label}</dt>
                <dd className="text-gray-800">{String(value)}</dd>
              </Fragment>
            )
          })}
        </dl>
      </details>
    </div>
  )
}

function LinesTable({
  orderId,
  lines,
  terminal,
  onConflict,
}: {
  orderId: string
  lines: OrderLine[]
  terminal: boolean
  onConflict: () => void
}) {
  const queryClient = useQueryClient()
  const [editingLineId, setEditingLineId] = useState<string | null>(null)
  const [form, setForm] = useState<{ quantity: string; unitPrice: string; status: string }>({
    quantity: '',
    unitPrice: '',
    status: '',
  })

  const mutation = useMutation({
    mutationFn: ({ line }: { line: OrderLine }) =>
      updateLine(orderId, line.lineId, { quantity: form.quantity, unitPrice: form.unitPrice, status: form.status }, line.version),
    onSuccess: () => {
      setEditingLineId(null)
      queryClient.invalidateQueries({ queryKey: ['order', orderId] })
    },
    onError: (error) => {
      if (error instanceof ApiError && error.isConflict) {
        onConflict()
        queryClient.invalidateQueries({ queryKey: ['order', orderId] })
      }
    },
  })

  function startEdit(line: OrderLine) {
    setEditingLineId(line.lineId)
    setForm({ quantity: line.quantity, unitPrice: line.unitPrice, status: line.status })
  }

  return (
    <div className="overflow-hidden rounded border border-gray-200 bg-white">
      <table className="w-full text-sm">
        <thead className="bg-gray-50 text-left text-xs font-medium uppercase text-gray-500">
          <tr>
            <th className="px-3 py-2">Line #</th>
            <th className="px-3 py-2">Item ref</th>
            <th className="px-3 py-2">Qty</th>
            <th className="px-3 py-2">Unit price</th>
            <th className="px-3 py-2">Line total</th>
            <th className="px-3 py-2">Status</th>
            {!terminal && <th className="px-3 py-2" />}
          </tr>
        </thead>
        <tbody className="divide-y divide-gray-100">
          {lines.map((line) => {
            const editing = editingLineId === line.lineId
            return (
              <tr key={line.lineId}>
                <td className="px-3 py-2">{line.lineNumber}</td>
                <td className="px-3 py-2">{line.itemRef}</td>
                <td className="px-3 py-2">
                  {editing ? (
                    <input
                      value={form.quantity}
                      onChange={(e) => setForm({ ...form, quantity: e.target.value })}
                      className="w-20 rounded border border-gray-300 px-1"
                    />
                  ) : (
                    line.quantity
                  )}
                </td>
                <td className="px-3 py-2">
                  {editing ? (
                    <input
                      value={form.unitPrice}
                      onChange={(e) => setForm({ ...form, unitPrice: e.target.value })}
                      className="w-24 rounded border border-gray-300 px-1"
                    />
                  ) : (
                    line.unitPrice
                  )}
                </td>
                <td className="px-3 py-2">{line.lineTotal}</td>
                <td className="px-3 py-2">
                  {editing ? (
                    <input
                      value={form.status}
                      onChange={(e) => setForm({ ...form, status: e.target.value })}
                      className="w-24 rounded border border-gray-300 px-1"
                    />
                  ) : (
                    line.status
                  )}
                </td>
                {!terminal && (
                  <td className="px-3 py-2 text-right">
                    {editing ? (
                      <div className="flex justify-end gap-2">
                        <button
                          type="button"
                          onClick={() => mutation.mutate({ line })}
                          className="font-medium text-blue-600 hover:underline"
                        >
                          Save
                        </button>
                        <button type="button" onClick={() => setEditingLineId(null)} className="text-gray-500 hover:underline">
                          Cancel
                        </button>
                      </div>
                    ) : (
                      <button type="button" onClick={() => startEdit(line)} className="text-gray-500 hover:underline">
                        Edit
                      </button>
                    )}
                  </td>
                )}
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}
