import { apiFetch } from '../lib/api'
import type { Order, OrderLine, Page } from '../types/domain'

export interface ListOrdersParams {
  status?: string[]
  orderType?: string[]
  customerRef?: string
  createdFrom?: string
  createdTo?: string
  hasOpenTask?: boolean
  page?: number
  size?: number
  sort?: string
}

export function listOrders(params: ListOrdersParams): Promise<Page<Order>> {
  return apiFetch('/orders', { query: { ...params } })
}

export function getOrder(orderId: string): Promise<Order> {
  return apiFetch(`/orders/${orderId}`)
}

export interface LineInput {
  itemRef: string
  quantity: string
  unitPrice: string
  attributes?: unknown
}

export interface CreateOrderInput {
  orderTypeCode: string
  customerRef?: string
  currency: string
  totalAmount?: string
  attributes?: unknown
  lines?: LineInput[]
}

export function createOrder(input: CreateOrderInput): Promise<Order> {
  return apiFetch('/orders', { method: 'POST', body: input })
}

export interface UpdateOrderInput {
  customerRef?: string | null
  currency?: string | null
  totalAmount?: string | null
  attributes?: unknown
}

export function updateOrder(orderId: string, input: UpdateOrderInput, version: number): Promise<Order> {
  return apiFetch(`/orders/${orderId}`, { method: 'PATCH', body: input, ifMatch: version })
}

export interface UpdateLineInput {
  quantity?: string | null
  unitPrice?: string | null
  status?: string | null
  attributes?: unknown
}

export function updateLine(orderId: string, lineId: string, input: UpdateLineInput, version: number): Promise<OrderLine> {
  return apiFetch(`/orders/${orderId}/lines/${lineId}`, { method: 'PATCH', body: input, ifMatch: version })
}

export function addLine(orderId: string, input: LineInput): Promise<OrderLine> {
  return apiFetch(`/orders/${orderId}/lines`, { method: 'POST', body: input })
}
