import { apiFetch } from '../lib/api'
import type { TriggerType, WorkflowDefinition, WorkflowInstance } from '../types/domain'

export function getWorkflow(orderId: string): Promise<WorkflowInstance> {
  return apiFetch(`/orders/${orderId}/workflow`)
}

export function fireTransition(
  orderId: string,
  triggerType: TriggerType,
  triggerCode: string | null,
  comment?: string,
): Promise<WorkflowInstance> {
  return apiFetch(`/orders/${orderId}/workflow/transitions`, {
    method: 'POST',
    body: { triggerType, triggerCode, comment: comment ?? null },
  })
}

export function getWorkflowDefinition(id: string): Promise<WorkflowDefinition> {
  return apiFetch(`/workflow-definitions/${id}`)
}
