import { apiFetch } from '../lib/api'
import type { Page, Task, TaskComment, TaskStatus } from '../types/domain'

export interface ListTasksParams {
  status?: TaskStatus
  assigneeGroup?: string
  orderType?: string
  assigneeId?: string
  priority?: number
  orderId?: string
  page?: number
  size?: number
  sort?: string
}

export function listTasks(params: ListTasksParams): Promise<Page<Task>> {
  return apiFetch('/tasks', { query: { ...params } })
}

export function getTask(taskId: string): Promise<Task> {
  return apiFetch(`/tasks/${taskId}`)
}

export function getComments(taskId: string): Promise<TaskComment[]> {
  return apiFetch(`/tasks/${taskId}/comments`)
}

export function addComment(taskId: string, body: string): Promise<TaskComment> {
  return apiFetch(`/tasks/${taskId}/comments`, { method: 'POST', body: { body } })
}

export function claimTask(taskId: string, version: number): Promise<Task> {
  return apiFetch(`/tasks/${taskId}/claim`, { method: 'POST', ifMatch: version })
}

export function assignTask(taskId: string, assigneeId: string, version: number): Promise<Task> {
  return apiFetch(`/tasks/${taskId}/assign`, { method: 'POST', body: { assigneeId }, ifMatch: version })
}

export function approveTask(taskId: string, comment: string | null, version: number): Promise<Task> {
  return apiFetch(`/tasks/${taskId}/approve`, { method: 'POST', body: { comment }, ifMatch: version })
}

export function rejectTask(taskId: string, reason: string, version: number): Promise<Task> {
  return apiFetch(`/tasks/${taskId}/reject`, { method: 'POST', body: { reason }, ifMatch: version })
}

export function escalateTask(taskId: string, reason: string, version: number): Promise<Task> {
  return apiFetch(`/tasks/${taskId}/escalate`, { method: 'POST', body: { reason }, ifMatch: version })
}
