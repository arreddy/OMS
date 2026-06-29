import { useQuery } from '@tanstack/react-query'
import { useParams } from 'react-router-dom'
import { getOrder } from '../api/orders'
import { getWorkflow, getWorkflowDefinition } from '../api/workflow'
import type { WorkflowState } from '../types/domain'

/**
 * Customer-safe projection only: plain-language label (not workflow_state.code),
 * a filtered timeline (only is_customer_visible states), no history table, no
 * attributes, no task info (UI-SPEC.md §3). There's no dedicated customer API
 * yet (a still-open gap from the spec review) — this page fetches the same
 * endpoints Ops uses but only ever renders the customer-appropriate subset.
 */
export function OrderTrackingPage() {
  const { orderId } = useParams<{ orderId: string }>()

  const orderQuery = useQuery({ queryKey: ['order', orderId], queryFn: () => getOrder(orderId!), enabled: !!orderId })
  const workflowQuery = useQuery({ queryKey: ['workflow', orderId], queryFn: () => getWorkflow(orderId!), enabled: !!orderId })
  const definitionQuery = useQuery({
    queryKey: ['workflow-definition', workflowQuery.data?.workflowDefinitionId],
    queryFn: () => getWorkflowDefinition(workflowQuery.data!.workflowDefinitionId),
    enabled: !!workflowQuery.data,
  })

  if (orderQuery.isLoading || workflowQuery.isLoading || definitionQuery.isLoading) {
    return <p className="text-center text-sm text-gray-500">Loading…</p>
  }
  if (orderQuery.error || !orderQuery.data) {
    return <p className="text-center text-sm text-gray-500">Couldn't load this order. Retry.</p>
  }

  const order = orderQuery.data
  const workflow = workflowQuery.data
  const definition = definitionQuery.data

  const timeline = definition && workflow ? buildCustomerTimeline(definition.states, definition.transitions, workflow.history.map((h) => h.toStateCode)) : []
  const lastVisitedIndex = timeline.reduce((acc, item, i) => (item.visited ? i : acc), -1)
  const currentVisible = lastVisitedIndex >= 0 ? timeline[lastVisitedIndex] : undefined
  const isFailed = currentVisible?.state.terminalOutcome === 'FAILURE'

  return (
    <div className="space-y-6">
      <div className="text-center">
        <p className="font-mono text-sm text-gray-400">Order {order.orderNumber}</p>
        <h1 className="mt-1 text-2xl font-semibold text-gray-900">
          {currentVisible?.state.customerFacingLabel ?? 'Processing'}
        </h1>
      </div>

      <ol className="space-y-0">
        {timeline.map((item, i) => {
          const done = i <= lastVisitedIndex
          const isCurrent = i === lastVisitedIndex
          return (
            <li key={item.state.stateId} className="flex gap-3">
              <div className="flex flex-col items-center">
                <span
                  className={
                    'flex h-6 w-6 items-center justify-center rounded-full text-xs font-bold ' +
                    (isCurrent
                      ? 'bg-gray-900 text-white'
                      : done
                        ? 'bg-gray-300 text-gray-700'
                        : 'border border-dashed border-gray-300 text-gray-300')
                  }
                >
                  {done ? '✓' : ''}
                </span>
                {i < timeline.length - 1 && <span className={`h-10 w-px ${done ? 'bg-gray-300' : 'bg-gray-200'}`} />}
              </div>
              <div className="pb-6">
                <p className={isCurrent ? 'font-semibold text-gray-900' : done ? 'text-gray-700' : 'text-gray-400'}>
                  {item.state.customerFacingLabel ?? item.state.code}
                </p>
              </div>
            </li>
          )
        })}
      </ol>

      {workflow?.terminal && isFailed && (
        <div className="rounded border border-gray-200 bg-white p-4 text-center text-sm text-gray-600">
          <p>We're sorry — this order didn't go through.</p>
          <p className="mt-2">
            <a href="mailto:support@example.com" className="font-medium text-blue-600 hover:underline">
              Contact support
            </a>{' '}
            for details.
          </p>
        </div>
      )}
    </div>
  )
}

interface TimelineItem {
  state: WorkflowState
  visited: boolean
}

function buildCustomerTimeline(
  states: WorkflowState[],
  transitions: { fromStateCode: string; toStateCode: string }[],
  visitedToCodes: string[],
): TimelineItem[] {
  const adjacency = new Map<string, string[]>()
  for (const t of transitions) {
    adjacency.set(t.fromStateCode, [...(adjacency.get(t.fromStateCode) ?? []), t.toStateCode])
  }
  const initial = states.find((s) => s.initial)
  const order: string[] = []
  const seen = new Set<string>()
  const queue = initial ? [initial.code] : []
  while (queue.length > 0) {
    const code = queue.shift()!
    if (seen.has(code)) continue
    seen.add(code)
    order.push(code)
    for (const next of adjacency.get(code) ?? []) {
      if (!seen.has(next)) queue.push(next)
    }
  }
  const visited = new Set([...visitedToCodes, initial?.code].filter(Boolean) as string[])
  const byCode = new Map(states.map((s) => [s.code, s]))
  return order
    .map((code) => byCode.get(code)!)
    .filter((s) => s.customerVisible)
    .map((state) => ({ state, visited: visited.has(state.code) }))
}
