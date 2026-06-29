import { Fragment, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useParams } from 'react-router-dom'
import {
  addComment,
  approveTask,
  assignTask,
  claimTask,
  escalateTask,
  getComments,
  getTask,
  rejectTask,
} from '../api/tasks'
import { getOrder } from '../api/orders'
import { getOrderTypeSchema } from '../api/orderTypes'
import { useActingUser } from '../lib/actingUser'
import { SlaBadge } from '../components/SlaBadge'
import { ErrorBanner } from '../components/ErrorBanner'
import { ConflictBanner } from '../components/ConflictBanner'
import { ApiError } from '../lib/api'

export function TaskDetailPage() {
  const { taskId } = useParams<{ taskId: string }>()
  const { user } = useActingUser()
  const queryClient = useQueryClient()
  const [conflict, setConflict] = useState(false)
  const [assignee, setAssignee] = useState('')
  const [approveComment, setApproveComment] = useState('')
  const [rejectReason, setRejectReason] = useState('')
  const [escalateReason, setEscalateReason] = useState('')
  const [showEscalate, setShowEscalate] = useState(false)
  const [commentBody, setCommentBody] = useState('')

  const taskQuery = useQuery({ queryKey: ['task', taskId], queryFn: () => getTask(taskId!), enabled: !!taskId })
  const orderQuery = useQuery({
    queryKey: ['order', taskQuery.data?.orderId],
    queryFn: () => getOrder(taskQuery.data!.orderId),
    enabled: !!taskQuery.data,
  })
  const schemaQuery = useQuery({
    queryKey: ['order-type-schema', orderQuery.data?.orderTypeCode],
    queryFn: () => getOrderTypeSchema(orderQuery.data!.orderTypeCode),
    enabled: !!orderQuery.data,
  })
  const commentsQuery = useQuery({
    queryKey: ['task-comments', taskId],
    queryFn: () => getComments(taskId!),
    enabled: !!taskId,
  })

  function invalidate() {
    queryClient.invalidateQueries({ queryKey: ['task', taskId] })
  }
  function handleError(error: unknown) {
    if (error instanceof ApiError && error.isConflict) {
      setConflict(true)
      invalidate()
    }
  }

  const task = taskQuery.data

  const claimMutation = useMutation({
    mutationFn: () => claimTask(taskId!, task!.version),
    onSuccess: invalidate,
    onError: handleError,
  })
  const assignMutation = useMutation({
    mutationFn: () => assignTask(taskId!, assignee, task!.version),
    onSuccess: () => {
      setAssignee('')
      invalidate()
    },
    onError: handleError,
  })
  const approveMutation = useMutation({
    mutationFn: () => approveTask(taskId!, approveComment || null, task!.version),
    onSuccess: invalidate,
    onError: handleError,
  })
  const rejectMutation = useMutation({
    mutationFn: () => rejectTask(taskId!, rejectReason, task!.version),
    onSuccess: invalidate,
    onError: handleError,
  })
  const escalateMutation = useMutation({
    mutationFn: () => escalateTask(taskId!, escalateReason, task!.version),
    onSuccess: () => {
      setShowEscalate(false)
      setEscalateReason('')
      invalidate()
    },
    onError: handleError,
  })
  const commentMutation = useMutation({
    mutationFn: () => addComment(taskId!, commentBody),
    onSuccess: () => {
      setCommentBody('')
      queryClient.invalidateQueries({ queryKey: ['task-comments', taskId] })
    },
  })

  if (taskQuery.isLoading || !task) {
    return <p className="text-sm text-gray-500">Loading task…</p>
  }

  const canDecide = task.status === 'UNASSIGNED' || task.status === 'ASSIGNED' || task.status === 'IN_PROGRESS'
  const canEscalate = canDecide

  const attributes = (orderQuery.data?.attributes as Record<string, unknown>) ?? {}
  const schemaProps = schemaQuery.data?.attributeSchema.properties ?? {}
  const flaggedKeys = Object.entries(schemaProps)
    .filter(([, prop]) => prop['x-show-in-task'])
    .map(([key]) => key)
  const contextKeys = flaggedKeys.length > 0 ? flaggedKeys : Object.keys(attributes)

  return (
    <div className="mx-auto max-w-2xl space-y-4">
      {conflict && <ConflictBanner recordLabel="task" onRefresh={() => setConflict(false)} />}
      <ErrorBanner error={taskQuery.error} />

      <div className="rounded border border-gray-200 bg-white p-4">
        <div className="flex items-start justify-between">
          <div>
            <h1 className="text-lg font-semibold text-gray-900">{task.taskType}</h1>
            <Link to={`/ops/orders/${task.orderId}`} className="text-sm text-blue-600 hover:underline">
              Order {orderQuery.data?.orderNumber ?? task.orderId.slice(0, 8)}
            </Link>
            <p className="mt-1 text-sm text-gray-500">Status: {task.status}</p>
          </div>
          <div className="text-right">
            <SlaBadge slaDueAt={task.slaDueAt} />
            <div>
              <button
                type="button"
                onClick={() => setShowEscalate(!showEscalate)}
                disabled={!canEscalate}
                className="mt-2 text-xs text-gray-400 underline hover:text-gray-600 disabled:opacity-40"
              >
                Escalate
              </button>
            </div>
          </div>
        </div>

        {showEscalate && (
          <div className="mt-3 flex items-center gap-2 rounded border border-gray-200 bg-gray-50 p-2">
            <input
              value={escalateReason}
              onChange={(e) => setEscalateReason(e.target.value)}
              placeholder="Escalation reason (required)"
              className="flex-1 rounded border border-gray-300 px-2 py-1 text-sm"
            />
            <button
              type="button"
              disabled={!escalateReason.trim() || escalateMutation.isPending}
              onClick={() => escalateMutation.mutate()}
              className="rounded bg-gray-900 px-3 py-1 text-sm font-medium text-white disabled:opacity-40"
            >
              Confirm
            </button>
          </div>
        )}
      </div>

      {contextKeys.length > 0 && (
        <div className="rounded border border-gray-200 bg-white p-4">
          <h2 className="mb-2 text-sm font-semibold text-gray-900">Context</h2>
          <dl className="grid grid-cols-[max-content_1fr] gap-x-4 gap-y-1 text-sm">
            {contextKeys.map((key) => (
              <Fragment key={key}>
                <dt className="font-medium text-gray-500">{schemaProps[key]?.title ?? key}</dt>
                <dd className="text-gray-800">{String(attributes[key])}</dd>
              </Fragment>
            ))}
          </dl>
        </div>
      )}

      <div className="rounded border border-gray-200 bg-white p-4">
        <h2 className="mb-3 text-sm font-semibold text-gray-900">Actions</h2>
        <div className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            disabled={task.status !== 'UNASSIGNED' || claimMutation.isPending}
            onClick={() => claimMutation.mutate()}
            className="rounded border border-gray-300 px-3 py-1.5 text-sm hover:bg-gray-50 disabled:opacity-40"
          >
            Claim ({user})
          </button>
          <input
            value={assignee}
            onChange={(e) => setAssignee(e.target.value)}
            placeholder="Assign to user id…"
            className="rounded border border-gray-300 px-2 py-1.5 text-sm"
          />
          <button
            type="button"
            disabled={!assignee.trim() || !canDecide || assignMutation.isPending}
            onClick={() => assignMutation.mutate()}
            className="rounded border border-gray-300 px-3 py-1.5 text-sm hover:bg-gray-50 disabled:opacity-40"
          >
            Assign
          </button>
        </div>

        <div className="mt-4 grid grid-cols-2 gap-4">
          <div>
            <input
              value={approveComment}
              onChange={(e) => setApproveComment(e.target.value)}
              placeholder="Comment (optional)"
              className="mb-2 w-full rounded border border-gray-300 px-2 py-1.5 text-sm"
            />
            <button
              type="button"
              disabled={!canDecide || approveMutation.isPending}
              onClick={() => approveMutation.mutate()}
              className="w-full rounded bg-green-600 px-3 py-1.5 text-sm font-medium text-white disabled:opacity-40"
            >
              Approve order
            </button>
          </div>
          <div>
            <input
              value={rejectReason}
              onChange={(e) => setRejectReason(e.target.value)}
              placeholder="Reason (required)"
              className="mb-2 w-full rounded border border-gray-300 px-2 py-1.5 text-sm"
            />
            <button
              type="button"
              disabled={!canDecide || !rejectReason.trim() || rejectMutation.isPending}
              onClick={() => rejectMutation.mutate()}
              className="w-full rounded border border-red-300 bg-white px-3 py-1.5 text-sm font-medium text-red-700 disabled:opacity-40"
            >
              Reject order
            </button>
          </div>
        </div>
        {task.decisionReason && (
          <p className="mt-3 text-sm text-gray-500">
            Decision: {task.decision} by {task.decisionBy} — “{task.decisionReason}”
          </p>
        )}
        {task.escalationReason && <p className="mt-1 text-sm text-amber-700">Escalated: {task.escalationReason}</p>}
      </div>

      <div className="rounded border border-gray-200 bg-white p-4">
        <h2 className="mb-3 text-sm font-semibold text-gray-900">Comments</h2>
        <div className="space-y-2">
          {(commentsQuery.data ?? []).map((c) => (
            <div key={c.commentId} className="border-b border-gray-100 pb-2 text-sm last:border-0">
              <p className="text-gray-800">{c.body}</p>
              <p className="text-xs text-gray-400">
                {c.authorId} · {new Date(c.createdAt).toLocaleString()}
              </p>
            </div>
          ))}
        </div>
        <div className="mt-3 flex gap-2">
          <input
            value={commentBody}
            onChange={(e) => setCommentBody(e.target.value)}
            placeholder="Add a comment…"
            className="flex-1 rounded border border-gray-300 px-2 py-1.5 text-sm"
          />
          <button
            type="button"
            disabled={!commentBody.trim() || commentMutation.isPending}
            onClick={() => commentMutation.mutate()}
            className="rounded border border-gray-300 px-3 py-1.5 text-sm hover:bg-gray-50 disabled:opacity-40"
          >
            Post
          </button>
        </div>
      </div>
    </div>
  )
}
