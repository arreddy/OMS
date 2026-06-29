// Mirrors com.oms.domain.* enums and com.oms.web.dto.* records.

export type StateType = 'AUTOMATIC' | 'MANUAL' | 'WAIT'
export type TriggerType = 'EVENT' | 'API_ACTION' | 'TASK_APPROVED' | 'TASK_REJECTED' | 'TIMER'
export type TerminalOutcome = 'SUCCESS' | 'FAILURE'
export type BadgeCategory = 'AUTOMATIC' | 'MANUAL' | 'WAIT' | 'TERMINAL_SUCCESS' | 'TERMINAL_FAILURE'
export type TaskStatus = 'UNASSIGNED' | 'ASSIGNED' | 'IN_PROGRESS' | 'APPROVED' | 'REJECTED' | 'ESCALATED' | 'CANCELLED'
export type TaskDecision = 'APPROVE' | 'REJECT'

export interface Page<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number // 0-based current page
  size: number
}

export interface OrderLine {
  lineId: string
  lineNumber: number
  itemRef: string
  quantity: string
  unitPrice: string
  lineTotal: string
  status: string
  attributes: unknown
  version: number
}

export interface Order {
  orderId: string
  orderNumber: string
  orderTypeCode: string
  status: string
  customerRef: string | null
  currency: string
  totalAmount: string
  attributes: unknown
  version: number
  createdAt: string
  updatedAt: string
  lines: OrderLine[]
}

export interface TransitionOption {
  triggerType: TriggerType
  triggerCode: string | null
  toStateCode: string
}

export interface TransitionLogEntry {
  fromStateCode: string | null
  toStateCode: string
  triggerType: TriggerType | null
  triggerCode: string | null
  triggeredBy: string
  comment: string | null
  occurredAt: string
}

export interface WorkflowInstance {
  instanceId: string
  orderId: string
  workflowDefinitionId: string
  currentState: string
  terminal: boolean
  validNextTransitions: TransitionOption[]
  history: TransitionLogEntry[]
}

export interface WorkflowState {
  stateId: string
  code: string
  stateType: StateType
  initial: boolean
  terminal: boolean
  defaultAssigneeGroup: string | null
  customerVisible: boolean
  customerFacingLabel: string | null
  terminalOutcome: TerminalOutcome | null
  canvasX: string | null
  canvasY: string | null
}

export interface WorkflowTransition {
  transitionId: string
  fromStateCode: string
  toStateCode: string
  sequence: number
  triggerType: TriggerType
  triggerCode: string | null
  guardExpression: string | null
  sideEffect: string | null
}

export interface WorkflowDefinition {
  workflowDefinitionId: string
  orderTypeCode: string
  version: number
  name: string
  publishedAt: string
  states: WorkflowState[]
  transitions: WorkflowTransition[]
}

export interface WorkflowVersionSummary {
  workflowDefinitionId: string
  version: number
  name: string
  publishedAt: string
}

export interface OrderType {
  orderTypeId: string
  code: string
  name: string
  attributeSchema: JsonSchema
  lineAttributeSchema: JsonSchema
  workflowDefinitionId: string | null
  active: boolean
}

export interface ActiveWorkflowSummary {
  workflowDefinitionId: string
  version: number
  name: string
}

export interface OrderTypeSchema {
  code: string
  attributeSchema: JsonSchema
  lineAttributeSchema: JsonSchema
  activeWorkflow: ActiveWorkflowSummary | null
}

export interface StatusTaxonomyEntry {
  code: string
  badgeCategory: BadgeCategory
}

export interface Task {
  taskId: string
  orderId: string
  workflowInstanceId: string
  taskType: string
  status: TaskStatus
  assigneeId: string | null
  assigneeGroup: string | null
  priority: number
  slaDueAt: string | null
  decision: TaskDecision | null
  decisionReason: string | null
  decisionBy: string | null
  escalationReason: string | null
  createdAt: string
  claimedAt: string | null
  completedAt: string | null
  version: number
}

export interface TaskComment {
  commentId: string
  authorId: string
  body: string
  createdAt: string
}

// JSON Schema is recursive/open-ended; this models just the subset the
// schema builder and attribute renderer actually touch.
export interface JsonSchemaProperty {
  type?: string
  title?: string
  format?: string
  enum?: string[]
  maxLength?: number
  'x-show-in-task'?: boolean
  /** Spec'd in UI-SPEC.md §4.2 but currently unused — nothing reads it, since
   *  the Customer Portal never renders order.attributes at all (§3). Kept for
   *  fidelity to the spec text and as a hook for a future customer detail view. */
  'x-customer-visible'?: boolean
}

export interface JsonSchema {
  type?: string
  properties?: Record<string, JsonSchemaProperty>
  required?: string[]
  additionalProperties?: boolean
}
