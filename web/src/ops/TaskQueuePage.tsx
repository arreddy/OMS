import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { approveTask, claimTask, listTasks, rejectTask } from '../api/tasks'
import { useActingUser } from '../lib/actingUser'
import { SlaBadge } from '../components/SlaBadge'
import { Pagination } from '../components/Pagination'
import { EmptyState } from '../components/EmptyState'
import { ErrorBanner } from '../components/ErrorBanner'
import { ConflictBanner } from '../components/ConflictBanner'
import { ApiError } from '../lib/api'
import type { Task, TaskStatus } from '../types/domain'

const STATUS_OPTIONS: TaskStatus[] = ['UNASSIGNED', 'ASSIGNED', 'IN_PROGRESS', 'ESCALATED', 'APPROVED', 'REJECTED']

export function TaskQueuePage() {
  const { user } = useActingUser()
  const [assigneeGroup, setAssigneeGroup] = useState('')
  const [myTasksOnly, setMyTasksOnly] = useState(false)
  const [status, setStatus] = useState<TaskStatus | ''>('')
  const [priority, setPriority] = useState('')
  const [page, setPage] = useState(0)
  const [conflict, setConflict] = useState(false)

  const queryClient = useQueryClient()

  const tasksQuery = useQuery({
    queryKey: ['tasks', { assigneeGroup, myTasksOnly, status, priority, page, user }],
    queryFn: () =>
      listTasks({
        assigneeGroup: assigneeGroup || undefined,
        assigneeId: myTasksOnly ? user : undefined,
        status: status || undefined,
        priority: priority ? Number(priority) : undefined,
        page,
        size: 20,
        sort: 'slaDueAt,asc',
      }),
  })

  function clearFilters() {
    setAssigneeGroup('')
    setMyTasksOnly(false)
    setStatus('')
    setPriority('')
    setPage(0)
  }

  function refreshAfterConflict() {
    setConflict(false)
    queryClient.invalidateQueries({ queryKey: ['tasks'] })
  }

  return (
    <div className="flex gap-6">
      <aside className="w-55 flex-shrink-0 space-y-6">
        <div>
          <h3 className="mb-2 text-sm font-semibold text-gray-900">Assignee group</h3>
          <input
            value={assigneeGroup}
            onChange={(e) => {
              setAssigneeGroup(e.target.value)
              setPage(0)
            }}
            placeholder="e.g. credit-team"
            className="w-full rounded border border-gray-300 px-2 py-1 text-sm"
          />
        </div>
        <label className="flex items-center gap-2 text-sm text-gray-700">
          <input
            type="checkbox"
            checked={myTasksOnly}
            onChange={(e) => {
              setMyTasksOnly(e.target.checked)
              setPage(0)
            }}
          />
          My tasks ({user})
        </label>
        <div>
          <h3 className="mb-2 text-sm font-semibold text-gray-900">Status</h3>
          <select
            value={status}
            onChange={(e) => {
              setStatus(e.target.value as TaskStatus | '')
              setPage(0)
            }}
            className="w-full rounded border border-gray-300 px-2 py-1 text-sm"
          >
            <option value="">Any</option>
            {STATUS_OPTIONS.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </div>
        <div>
          <h3 className="mb-2 text-sm font-semibold text-gray-900">Priority</h3>
          <input
            type="number"
            value={priority}
            onChange={(e) => {
              setPriority(e.target.value)
              setPage(0)
            }}
            className="w-full rounded border border-gray-300 px-2 py-1 text-sm"
          />
        </div>
        <button type="button" onClick={clearFilters} className="text-sm font-medium text-blue-600 hover:underline">
          Clear filters
        </button>
      </aside>

      <div className="min-w-0 flex-1">
        <h1 className="mb-4 text-lg font-semibold text-gray-900">Task queue</h1>
        {conflict && <ConflictBanner recordLabel="task" onRefresh={refreshAfterConflict} />}
        <ErrorBanner error={tasksQuery.error} />
        {tasksQuery.data && tasksQuery.data.content.length === 0 ? (
          <EmptyState message="No tasks match these filters." actionLabel="Clear filters" onAction={clearFilters} />
        ) : (
          <div className="overflow-hidden rounded border border-gray-200 bg-white">
            <table className="w-full text-sm">
              <thead className="bg-gray-50 text-left text-xs font-medium uppercase text-gray-500">
                <tr>
                  <th className="px-3 py-2">Order</th>
                  <th className="px-3 py-2">Task type</th>
                  <th className="px-3 py-2">Priority</th>
                  <th className="px-3 py-2">SLA due</th>
                  <th className="px-3 py-2">Status</th>
                  <th className="px-3 py-2">Assignee</th>
                  <th className="px-3 py-2" />
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {(tasksQuery.data?.content ?? []).map((task) => (
                  <TaskRow key={task.taskId} task={task} onConflict={() => setConflict(true)} />
                ))}
              </tbody>
            </table>
            <div className="px-4">
              <Pagination page={page} totalPages={tasksQuery.data?.totalPages ?? 0} onChange={setPage} />
            </div>
          </div>
        )}
      </div>
    </div>
  )
}

function TaskRow({ task, onConflict }: { task: Task; onConflict: () => void }) {
  const { user } = useActingUser()
  const queryClient = useQueryClient()
  const [action, setAction] = useState<'approve' | 'reject' | null>(null)
  const [reason, setReason] = useState('')

  function invalidate() {
    queryClient.invalidateQueries({ queryKey: ['tasks'] })
  }

  function handleError(error: unknown) {
    if (error instanceof ApiError && error.isConflict) {
      onConflict()
      invalidate()
    }
  }

  const claimMutation = useMutation({
    mutationFn: () => claimTask(task.taskId, task.version),
    onSuccess: invalidate,
    onError: handleError,
  })
  const approveMutation = useMutation({
    mutationFn: () => approveTask(task.taskId, reason || null, task.version),
    onSuccess: () => {
      setAction(null)
      invalidate()
    },
    onError: handleError,
  })
  const rejectMutation = useMutation({
    mutationFn: () => rejectTask(task.taskId, reason, task.version),
    onSuccess: () => {
      setAction(null)
      invalidate()
    },
    onError: handleError,
  })

  const canClaim = task.status === 'UNASSIGNED'
  const canDecide = task.status === 'UNASSIGNED' || task.status === 'ASSIGNED' || task.status === 'IN_PROGRESS'

  return (
    <>
      <tr>
        <td className="px-3 py-2">
          <Link to={`/ops/orders/${task.orderId}`} className="font-mono text-blue-600 hover:underline">
            {task.orderId.slice(0, 8)}
          </Link>
        </td>
        <td className="px-3 py-2">
          <Link to={`/ops/tasks/${task.taskId}`} className="hover:underline">
            {task.taskType}
          </Link>
        </td>
        <td className="px-3 py-2">{task.priority}</td>
        <td className="px-3 py-2">
          <SlaBadge slaDueAt={task.slaDueAt} />
        </td>
        <td className="px-3 py-2">{task.status}</td>
        <td className="px-3 py-2">{task.assigneeId ?? task.assigneeGroup ?? '—'}</td>
        <td className="px-3 py-2 text-right">
          <div className="flex justify-end gap-2">
            {canClaim && (
              <button
                type="button"
                onClick={() => claimMutation.mutate()}
                disabled={claimMutation.isPending}
                className="rounded border border-gray-300 px-2 py-1 text-xs hover:bg-gray-50"
              >
                Claim ({user})
              </button>
            )}
            {canDecide && (
              <>
                <button
                  type="button"
                  onClick={() => setAction(action === 'approve' ? null : 'approve')}
                  className="rounded border border-green-300 px-2 py-1 text-xs text-green-700 hover:bg-green-50"
                >
                  Approve
                </button>
                <button
                  type="button"
                  onClick={() => setAction(action === 'reject' ? null : 'reject')}
                  className="rounded border border-red-300 px-2 py-1 text-xs text-red-700 hover:bg-red-50"
                >
                  Reject
                </button>
              </>
            )}
          </div>
        </td>
      </tr>
      {action && (
        <tr>
          <td colSpan={7} className="bg-gray-50 px-3 py-2">
            <div className="flex items-center gap-2">
              <input
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                placeholder={action === 'reject' ? 'Reason (required)' : 'Comment (optional)'}
                className="flex-1 rounded border border-gray-300 px-2 py-1 text-sm"
              />
              <button
                type="button"
                disabled={(action === 'reject' && !reason.trim()) || approveMutation.isPending || rejectMutation.isPending}
                onClick={() => (action === 'approve' ? approveMutation.mutate() : rejectMutation.mutate())}
                className="rounded bg-gray-900 px-3 py-1 text-sm font-medium text-white disabled:opacity-40"
              >
                Confirm {action}
              </button>
              <button type="button" onClick={() => setAction(null)} className="text-sm text-gray-500 hover:underline">
                Cancel
              </button>
            </div>
          </td>
        </tr>
      )}
    </>
  )
}
