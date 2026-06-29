import type { Edge, Node } from '@xyflow/react'
import type { PublishWorkflowInput } from '../api/orderTypes'
import type { TerminalOutcome, TriggerType, WorkflowDefinition, StateType } from '../types/domain'

export interface StateNodeData extends Record<string, unknown> {
  code: string
  stateType: StateType
  initial: boolean
  terminal: boolean
  defaultAssigneeGroup: string
  customerVisible: boolean
  customerFacingLabel: string
  terminalOutcome: TerminalOutcome | ''
}

export interface TransitionEdgeData extends Record<string, unknown> {
  sequence: number
  triggerType: TriggerType
  triggerCode: string
  guardExpression: string
  sideEffect: string
}

export type StateNode = Node<StateNodeData, 'state'>
export type TransitionEdge = Edge<TransitionEdgeData>

export function definitionToGraph(definition: WorkflowDefinition): { nodes: StateNode[]; edges: TransitionEdge[] } {
  const nodes: StateNode[] = definition.states.map((s, i) => ({
    id: s.code,
    type: 'state',
    position: {
      x: s.canvasX ? Number(s.canvasX) : (i % 4) * 220 + 40,
      y: s.canvasY ? Number(s.canvasY) : Math.floor(i / 4) * 140 + 40,
    },
    data: {
      code: s.code,
      stateType: s.stateType,
      initial: s.initial,
      terminal: s.terminal,
      defaultAssigneeGroup: s.defaultAssigneeGroup ?? '',
      customerVisible: s.customerVisible,
      customerFacingLabel: s.customerFacingLabel ?? '',
      terminalOutcome: s.terminalOutcome ?? '',
    },
  }))
  const edges: TransitionEdge[] = definition.transitions.map((t) => ({
    id: t.transitionId,
    source: t.fromStateCode,
    target: t.toStateCode,
    label: t.triggerCode ?? t.triggerType,
    data: {
      sequence: t.sequence,
      triggerType: t.triggerType,
      triggerCode: t.triggerCode ?? '',
      guardExpression: t.guardExpression ?? '',
      sideEffect: t.sideEffect ?? '',
    },
  }))
  return { nodes, edges }
}

export function graphToCommand(nodes: StateNode[], edges: TransitionEdge[], name: string): PublishWorkflowInput {
  return {
    name,
    states: nodes.map((n) => ({
      code: n.data.code,
      stateType: n.data.stateType,
      initial: n.data.initial,
      terminal: n.data.terminal,
      defaultAssigneeGroup: n.data.defaultAssigneeGroup || null,
      customerVisible: n.data.customerVisible,
      customerFacingLabel: n.data.customerFacingLabel || null,
      terminalOutcome: n.data.terminalOutcome || null,
      canvasX: n.position.x,
      canvasY: n.position.y,
    })),
    transitions: edges.map((e) => ({
      fromStateCode: e.source,
      toStateCode: e.target,
      sequence: e.data?.sequence ?? 0,
      triggerType: e.data?.triggerType ?? 'EVENT',
      triggerCode: e.data?.triggerCode || null,
      guardExpression: e.data?.guardExpression || null,
      sideEffect: e.data?.sideEffect || null,
    })),
  }
}

/** Client-side mirror of OrderTypeService#validateGraph — for UX only; the server is authoritative. */
export function validateGraph(nodes: StateNode[], edges: TransitionEdge[]): string[] {
  const errors: string[] = []
  const initials = nodes.filter((n) => n.data.initial)
  if (initials.length !== 1) {
    errors.push(`Exactly one initial state is required (found ${initials.length}).`)
  }

  const outboundByCode = new Map<string, TransitionEdge[]>()
  for (const e of edges) {
    outboundByCode.set(e.source, [...(outboundByCode.get(e.source) ?? []), e])
  }

  if (initials.length === 1) {
    const seen = new Set<string>([initials[0].data.code])
    const queue = [initials[0].data.code]
    while (queue.length > 0) {
      const current = queue.shift()!
      for (const e of outboundByCode.get(current) ?? []) {
        if (!seen.has(e.target)) {
          seen.add(e.target)
          queue.push(e.target)
        }
      }
    }
    const unreachable = nodes.filter((n) => !seen.has(n.data.code))
    if (unreachable.length > 0) {
      errors.push(`Unreachable from initial state: ${unreachable.map((n) => n.data.code).join(', ')}`)
    }
  }

  const deadEnds = nodes.filter((n) => !n.data.terminal && (outboundByCode.get(n.data.code) ?? []).length === 0)
  if (deadEnds.length > 0) {
    errors.push(`Non-terminal states with no outbound transition: ${deadEnds.map((n) => n.data.code).join(', ')}`)
  }

  const incompleteManual = nodes.filter((n) => {
    if (n.data.stateType !== 'MANUAL') return false
    const outbound = outboundByCode.get(n.data.code) ?? []
    const hasApprove = outbound.some((e) => e.data?.triggerType === 'TASK_APPROVED')
    const hasReject = outbound.some((e) => e.data?.triggerType === 'TASK_REJECTED')
    return !(hasApprove && hasReject)
  })
  if (incompleteManual.length > 0) {
    errors.push(`MANUAL states missing an approve and/or reject path: ${incompleteManual.map((n) => n.data.code).join(', ')}`)
  }

  const terminalOutcomeIssues = nodes.filter((n) => n.data.terminal === (n.data.terminalOutcome === ''))
  if (terminalOutcomeIssues.length > 0) {
    errors.push(`terminal_outcome must be set if and only if terminal: ${terminalOutcomeIssues.map((n) => n.data.code).join(', ')}`)
  }

  return errors
}
