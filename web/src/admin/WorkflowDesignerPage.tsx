import { useCallback, useEffect, useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useParams } from 'react-router-dom'
import {
  ReactFlow,
  Background,
  Controls,
  Handle,
  Position,
  addEdge,
  useNodesState,
  useEdgesState,
  type Connection,
  type NodeProps,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { getOrderTypeSchema, getWorkflowVersions, publishWorkflow } from '../api/orderTypes'
import { getWorkflowDefinition } from '../api/workflow'
import { ErrorBanner } from '../components/ErrorBanner'
import { BADGE_COLORS } from '../lib/badgeColors'
import {
  definitionToGraph,
  graphToCommand,
  validateGraph,
  type StateNode,
  type StateNodeData,
  type TransitionEdge,
  type TransitionEdgeData,
} from './workflowGraph'
import type { TerminalOutcome, TriggerType } from '../types/domain'

function StateNodeView({ data, selected }: NodeProps<StateNode>) {
  const colors = BADGE_COLORS[data.stateType]
  return (
    <div
      className={`rounded border-2 bg-white px-3 py-2 text-xs shadow-sm ${selected ? 'border-gray-900' : 'border-transparent'}`}
    >
      <Handle type="target" position={Position.Left} />
      <div className={`flex items-center gap-1.5 rounded px-1.5 py-0.5 font-medium ${colors.bg} ${colors.text}`}>
        <span className={`h-1.5 w-1.5 rounded-full ${colors.dot}`} aria-hidden="true" />
        {data.code}
      </div>
      <div className="mt-1 flex gap-1 text-[10px] text-gray-400">
        {data.initial && <span>initial</span>}
        {data.terminal && <span>terminal ({data.terminalOutcome || '?'})</span>}
      </div>
      <Handle type="source" position={Position.Right} />
    </div>
  )
}

const NODE_TYPES = { state: StateNodeView }

export function WorkflowDesignerPage() {
  const { code } = useParams<{ code: string }>()
  const queryClient = useQueryClient()

  const [nodes, setNodes, onNodesChange] = useNodesState<StateNode>([])
  const [edges, setEdges, onEdgesChange] = useEdgesState<TransitionEdge>([])
  const [loadedFromCode, setLoadedFromCode] = useState<string | null>(null)
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null)
  const [selectedEdgeId, setSelectedEdgeId] = useState<string | null>(null)
  const [codeDraft, setCodeDraft] = useState('')
  const [workflowName, setWorkflowName] = useState('')
  const [viewingVersionId, setViewingVersionId] = useState<string>('')

  const schemaQuery = useQuery({ queryKey: ['order-type-schema', code], queryFn: () => getOrderTypeSchema(code!), enabled: !!code })
  const versionsQuery = useQuery({ queryKey: ['workflow-versions', code], queryFn: () => getWorkflowVersions(code!), enabled: !!code })
  const activeDefinitionId = schemaQuery.data?.activeWorkflow?.workflowDefinitionId
  const activeDefinitionQuery = useQuery({
    queryKey: ['workflow-definition', activeDefinitionId],
    queryFn: () => getWorkflowDefinition(activeDefinitionId!),
    enabled: !!activeDefinitionId,
  })
  const viewedDefinitionQuery = useQuery({
    queryKey: ['workflow-definition', viewingVersionId],
    queryFn: () => getWorkflowDefinition(viewingVersionId),
    enabled: !!viewingVersionId,
  })

  // Load the active definition into the editable canvas once, the first time it arrives.
  useEffect(() => {
    if (activeDefinitionQuery.data && loadedFromCode !== code) {
      const { nodes: n, edges: e } = definitionToGraph(activeDefinitionQuery.data)
      setNodes(n)
      setEdges(e)
      setWorkflowName(activeDefinitionQuery.data.name)
      setLoadedFromCode(code ?? null)
    } else if (!activeDefinitionId && schemaQuery.data && loadedFromCode !== code) {
      setWorkflowName(`${schemaQuery.data.code} workflow`)
      setLoadedFromCode(code ?? null)
    }
  }, [activeDefinitionQuery.data, activeDefinitionId, schemaQuery.data, loadedFromCode, code, setNodes, setEdges])

  const selectedNode = nodes.find((n) => n.id === selectedNodeId)
  const selectedEdge = edges.find((e) => e.id === selectedEdgeId)

  useEffect(() => {
    setCodeDraft(selectedNode?.data.code ?? '')
  }, [selectedNode?.id])

  const onConnect = useCallback(
    (connection: Connection) => {
      setEdges((eds) =>
        addEdge(
          {
            ...connection,
            id: crypto.randomUUID(),
            label: 'new',
            data: { sequence: 0, triggerType: 'EVENT' as TriggerType, triggerCode: '', guardExpression: '', sideEffect: '' },
          },
          eds,
        ),
      )
    },
    [setEdges],
  )

  function addState() {
    const newCode = `STATE_${nodes.length + 1}`
    const newNode: StateNode = {
      id: newCode,
      type: 'state',
      position: { x: 80 + nodes.length * 20, y: 80 + nodes.length * 60 },
      data: {
        code: newCode,
        stateType: 'AUTOMATIC',
        initial: nodes.length === 0,
        terminal: false,
        defaultAssigneeGroup: '',
        customerVisible: false,
        customerFacingLabel: '',
        terminalOutcome: '',
      },
    }
    setNodes((nds) => [...nds, newNode])
    setSelectedNodeId(newCode)
    setSelectedEdgeId(null)
  }

  function updateSelectedNode(patch: Partial<StateNodeData>) {
    if (!selectedNodeId) return
    setNodes((nds) => nds.map((n) => (n.id === selectedNodeId ? { ...n, data: { ...n.data, ...patch } } : n)))
  }

  function commitCodeRename() {
    if (!selectedNodeId || !codeDraft.trim() || codeDraft === selectedNodeId) return
    const newCode = codeDraft.trim()
    setNodes((nds) => nds.map((n) => (n.id === selectedNodeId ? { ...n, id: newCode, data: { ...n.data, code: newCode } } : n)))
    setEdges((eds) =>
      eds.map((e) => ({
        ...e,
        source: e.source === selectedNodeId ? newCode : e.source,
        target: e.target === selectedNodeId ? newCode : e.target,
      })),
    )
    setSelectedNodeId(newCode)
  }

  function setInitial(targetId: string) {
    setNodes((nds) => nds.map((n) => ({ ...n, data: { ...n.data, initial: n.id === targetId } })))
  }

  function deleteSelectedNode() {
    if (!selectedNodeId) return
    setNodes((nds) => nds.filter((n) => n.id !== selectedNodeId))
    setEdges((eds) => eds.filter((e) => e.source !== selectedNodeId && e.target !== selectedNodeId))
    setSelectedNodeId(null)
  }

  function updateSelectedEdge(patch: Partial<TransitionEdgeData>) {
    if (!selectedEdgeId) return
    setEdges((eds) =>
      eds.map((e) =>
        e.id === selectedEdgeId
          ? { ...e, data: { ...e.data!, ...patch }, label: patch.triggerCode ?? e.data?.triggerCode ?? e.label }
          : e,
      ),
    )
  }

  function deleteSelectedEdge() {
    if (!selectedEdgeId) return
    setEdges((eds) => eds.filter((e) => e.id !== selectedEdgeId))
    setSelectedEdgeId(null)
  }

  const validationErrors = useMemo(() => validateGraph(nodes, edges), [nodes, edges])

  const publishMutation = useMutation({
    mutationFn: () => publishWorkflow(code!, graphToCommand(nodes, edges, workflowName || `${code} workflow`)),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['order-type-schema', code] })
      queryClient.invalidateQueries({ queryKey: ['workflow-versions', code] })
      queryClient.invalidateQueries({ queryKey: ['order-types'] })
    },
  })

  return (
    <div>
      <div className="mb-3 flex items-center justify-between">
        <div>
          <h1 className="text-lg font-semibold text-gray-900">Workflow designer — {code}</h1>
          <input
            value={workflowName}
            onChange={(e) => setWorkflowName(e.target.value)}
            placeholder="Workflow name"
            className="mt-1 rounded border border-gray-300 px-2 py-1 text-sm"
          />
        </div>
        <div className="flex items-center gap-3">
          <select
            value={viewingVersionId}
            onChange={(e) => setViewingVersionId(e.target.value)}
            className="rounded border border-gray-300 px-2 py-1 text-sm"
          >
            <option value="">View past version…</option>
            {(versionsQuery.data ?? []).map((v) => (
              <option key={v.workflowDefinitionId} value={v.workflowDefinitionId}>
                v{v.version} — {new Date(v.publishedAt).toLocaleDateString()}
              </option>
            ))}
          </select>
          <button
            type="button"
            onClick={addState}
            className="rounded border border-gray-300 px-3 py-1.5 text-sm hover:bg-gray-50"
          >
            + Add state
          </button>
          <button
            type="button"
            disabled={validationErrors.length > 0 || publishMutation.isPending}
            onClick={() => publishMutation.mutate()}
            className="rounded bg-gray-900 px-3 py-1.5 text-sm font-medium text-white disabled:opacity-40"
            title={validationErrors[0]}
          >
            Publish
          </button>
        </div>
      </div>
      <p className="mb-3 text-xs text-gray-400">
        Publishing creates a new workflow version. Existing in-flight orders stay on the current version.
      </p>

      <ErrorBanner error={publishMutation.error} />
      {validationErrors.length > 0 && (
        <div className="mb-3 rounded border border-amber-300 bg-amber-50 px-3 py-2 text-xs text-amber-900">
          <ul className="list-inside list-disc">
            {validationErrors.map((err) => (
              <li key={err}>{err}</li>
            ))}
          </ul>
        </div>
      )}

      {viewingVersionId && viewedDefinitionQuery.data && (
        <div className="mb-3 rounded border border-gray-300 bg-gray-50 p-3 text-sm">
          <div className="mb-2 flex items-center justify-between">
            <h3 className="font-semibold">
              v{viewedDefinitionQuery.data.version} (read-only) — {viewedDefinitionQuery.data.name}
            </h3>
            <button type="button" onClick={() => setViewingVersionId('')} className="text-xs text-gray-500 underline">
              Close
            </button>
          </div>
          <p className="mb-1 font-medium text-gray-600">States</p>
          <p className="mb-2 text-gray-700">{viewedDefinitionQuery.data.states.map((s) => s.code).join(', ')}</p>
          <p className="mb-1 font-medium text-gray-600">Transitions</p>
          <ul className="list-inside list-disc text-gray-700">
            {viewedDefinitionQuery.data.transitions.map((t) => (
              <li key={t.transitionId}>
                {t.fromStateCode} → {t.toStateCode} ({t.triggerType}
                {t.triggerCode ? `/${t.triggerCode}` : ''})
              </li>
            ))}
          </ul>
        </div>
      )}

      <div className="flex gap-4">
        <div className="h-[560px] flex-[3] rounded border border-gray-200 bg-white">
          <ReactFlow
            nodes={nodes}
            edges={edges}
            nodeTypes={NODE_TYPES}
            onNodesChange={onNodesChange}
            onEdgesChange={onEdgesChange}
            onConnect={onConnect}
            onNodeClick={(_, n) => {
              setSelectedNodeId(n.id)
              setSelectedEdgeId(null)
            }}
            onEdgeClick={(_, e) => {
              setSelectedEdgeId(e.id)
              setSelectedNodeId(null)
            }}
            onPaneClick={() => {
              setSelectedNodeId(null)
              setSelectedEdgeId(null)
            }}
            fitView
          >
            <Background />
            <Controls />
          </ReactFlow>
        </div>

        <div className="flex-[1] rounded border border-gray-200 bg-white p-3 text-sm">
          {selectedNode && (
            <div className="space-y-2">
              <h3 className="font-semibold text-gray-900">State</h3>
              <label className="block">
                <span className="text-xs text-gray-500">Code</span>
                <input
                  value={codeDraft}
                  onChange={(e) => setCodeDraft(e.target.value)}
                  onBlur={commitCodeRename}
                  className="w-full rounded border border-gray-300 px-2 py-1 font-mono text-xs"
                />
              </label>
              <label className="block">
                <span className="text-xs text-gray-500">State type</span>
                <select
                  value={selectedNode.data.stateType}
                  onChange={(e) => updateSelectedNode({ stateType: e.target.value as StateNodeData['stateType'] })}
                  className="w-full rounded border border-gray-300 px-2 py-1"
                >
                  <option value="AUTOMATIC">AUTOMATIC</option>
                  <option value="MANUAL">MANUAL</option>
                  <option value="WAIT">WAIT</option>
                </select>
              </label>
              <label className="flex items-center gap-2">
                <input type="radio" checked={selectedNode.data.initial} onChange={() => setInitial(selectedNode.id)} />
                Initial state
              </label>
              <label className="flex items-center gap-2">
                <input
                  type="checkbox"
                  checked={selectedNode.data.terminal}
                  onChange={(e) =>
                    updateSelectedNode({ terminal: e.target.checked, terminalOutcome: e.target.checked ? selectedNode.data.terminalOutcome : '' })
                  }
                />
                Terminal
              </label>
              {selectedNode.data.terminal && (
                <label className="block">
                  <span className="text-xs text-gray-500">Outcome</span>
                  <select
                    value={selectedNode.data.terminalOutcome}
                    onChange={(e) => updateSelectedNode({ terminalOutcome: e.target.value as TerminalOutcome | '' })}
                    className="w-full rounded border border-gray-300 px-2 py-1"
                  >
                    <option value="">(required)</option>
                    <option value="SUCCESS">SUCCESS</option>
                    <option value="FAILURE">FAILURE</option>
                  </select>
                </label>
              )}
              {selectedNode.data.stateType === 'MANUAL' && (
                <label className="block">
                  <span className="text-xs text-gray-500">Default assignee group</span>
                  <input
                    value={selectedNode.data.defaultAssigneeGroup}
                    onChange={(e) => updateSelectedNode({ defaultAssigneeGroup: e.target.value })}
                    className="w-full rounded border border-gray-300 px-2 py-1"
                  />
                </label>
              )}
              <label className="flex items-center gap-2">
                <input
                  type="checkbox"
                  checked={selectedNode.data.customerVisible}
                  onChange={(e) => updateSelectedNode({ customerVisible: e.target.checked })}
                />
                Customer visible
              </label>
              {selectedNode.data.customerVisible && (
                <label className="block">
                  <span className="text-xs text-gray-500">Customer-facing label</span>
                  <input
                    value={selectedNode.data.customerFacingLabel}
                    onChange={(e) => updateSelectedNode({ customerFacingLabel: e.target.value })}
                    className="w-full rounded border border-gray-300 px-2 py-1"
                  />
                </label>
              )}
              <button type="button" onClick={deleteSelectedNode} className="text-xs text-red-600 hover:underline">
                Delete state
              </button>
            </div>
          )}

          {selectedEdge && (
            <div className="space-y-2">
              <h3 className="font-semibold text-gray-900">Transition</h3>
              <p className="text-xs text-gray-500">
                {selectedEdge.source} → {selectedEdge.target}
              </p>
              <label className="block">
                <span className="text-xs text-gray-500">Sequence</span>
                <input
                  type="number"
                  value={selectedEdge.data?.sequence ?? 0}
                  onChange={(e) => updateSelectedEdge({ sequence: Number(e.target.value) })}
                  className="w-full rounded border border-gray-300 px-2 py-1"
                />
              </label>
              <label className="block">
                <span className="text-xs text-gray-500">Trigger type</span>
                <select
                  value={selectedEdge.data?.triggerType}
                  onChange={(e) => updateSelectedEdge({ triggerType: e.target.value as TriggerType })}
                  className="w-full rounded border border-gray-300 px-2 py-1"
                >
                  <option value="EVENT">EVENT</option>
                  <option value="API_ACTION">API_ACTION</option>
                  <option value="TASK_APPROVED">TASK_APPROVED</option>
                  <option value="TASK_REJECTED">TASK_REJECTED</option>
                  <option value="TIMER">TIMER</option>
                </select>
              </label>
              <label className="block">
                <span className="text-xs text-gray-500">Trigger code</span>
                <input
                  value={selectedEdge.data?.triggerCode ?? ''}
                  onChange={(e) => updateSelectedEdge({ triggerCode: e.target.value })}
                  className="w-full rounded border border-gray-300 px-2 py-1 font-mono text-xs"
                  placeholder="e.g. order.submitted (leave blank for guard-only, evaluated on entry)"
                />
              </label>
              <label className="block">
                <span className="text-xs text-gray-500">Guard expression (JSON Logic)</span>
                <textarea
                  value={selectedEdge.data?.guardExpression ?? ''}
                  onChange={(e) => updateSelectedEdge({ guardExpression: e.target.value })}
                  className="w-full rounded border border-gray-300 px-2 py-1 font-mono text-xs"
                  rows={3}
                />
              </label>
              <label className="block">
                <span className="text-xs text-gray-500">Side effect</span>
                <input
                  value={selectedEdge.data?.sideEffect ?? ''}
                  onChange={(e) => updateSelectedEdge({ sideEffect: e.target.value })}
                  className="w-full rounded border border-gray-300 px-2 py-1"
                />
              </label>
              <button type="button" onClick={deleteSelectedEdge} className="text-xs text-red-600 hover:underline">
                Delete transition
              </button>
            </div>
          )}

          {!selectedNode && !selectedEdge && (
            <p className="text-gray-400">Click a state or transition to edit it, or drag between states to connect them.</p>
          )}
        </div>
      </div>
    </div>
  )
}
