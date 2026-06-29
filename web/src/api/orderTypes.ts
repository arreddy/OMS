import { apiFetch } from '../lib/api'
import type {
  JsonSchema,
  OrderType,
  OrderTypeSchema,
  StatusTaxonomyEntry,
  WorkflowVersionSummary,
} from '../types/domain'

export function listOrderTypes(): Promise<OrderType[]> {
  return apiFetch('/order-types')
}

export function getOrderTypeSchema(code: string): Promise<OrderTypeSchema> {
  return apiFetch(`/order-types/${code}/schema`)
}

export function getStatusTaxonomy(): Promise<StatusTaxonomyEntry[]> {
  return apiFetch('/order-types/status-taxonomy')
}

export function getWorkflowVersions(code: string): Promise<WorkflowVersionSummary[]> {
  return apiFetch(`/order-types/${code}/workflow-versions`)
}

export interface CreateOrderTypeInput {
  code: string
  name: string
  attributeSchema: JsonSchema
  lineAttributeSchema: JsonSchema
}

export function createOrderType(input: CreateOrderTypeInput): Promise<OrderType> {
  return apiFetch('/order-types', { method: 'POST', body: input })
}

export interface StateSpecInput {
  code: string
  stateType: string
  initial: boolean
  terminal: boolean
  defaultAssigneeGroup: string | null
  customerVisible: boolean
  customerFacingLabel: string | null
  terminalOutcome: string | null
  canvasX: number | null
  canvasY: number | null
}

export interface TransitionSpecInput {
  fromStateCode: string
  toStateCode: string
  sequence: number
  triggerType: string
  triggerCode: string | null
  guardExpression: string | null
  sideEffect: string | null
}

export interface PublishWorkflowInput {
  name: string
  states: StateSpecInput[]
  transitions: TransitionSpecInput[]
}

export function publishWorkflow(code: string, input: PublishWorkflowInput): Promise<void> {
  return apiFetch(`/order-types/${code}/workflow`, { method: 'PUT', body: input })
}
