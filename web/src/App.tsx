import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { ActingUserProvider } from './lib/actingUser'
import { OpsAdminLayout } from './layout/OpsAdminLayout'
import { CustomerLayout } from './layout/CustomerLayout'
import { OrderListPage } from './ops/OrderListPage'
import { OrderCreatePage } from './ops/OrderCreatePage'
import { OrderDetailPage } from './ops/OrderDetailPage'
import { TaskQueuePage } from './ops/TaskQueuePage'
import { TaskDetailPage } from './ops/TaskDetailPage'
import { OrderTrackingPage } from './customer/OrderTrackingPage'
import { OrderTypeListPage } from './admin/OrderTypeListPage'
import { OrderTypeEditorPage } from './admin/OrderTypeEditorPage'
import { WorkflowDesignerPage } from './admin/WorkflowDesignerPage'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: 1, refetchOnWindowFocus: true },
  },
})

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <ActingUserProvider>
        <BrowserRouter>
          <Routes>
            {/* Ops + Admin share one chrome — internal users, full nav. */}
            <Route element={<OpsAdminLayout />}>
              <Route path="/" element={<Navigate to="/ops/orders" replace />} />
              <Route path="/ops/orders" element={<OrderListPage />} />
              <Route path="/ops/orders/new" element={<OrderCreatePage />} />
              <Route path="/ops/orders/:orderId" element={<OrderDetailPage />} />
              <Route path="/ops/tasks" element={<TaskQueuePage />} />
              <Route path="/ops/tasks/:taskId" element={<TaskDetailPage />} />
              <Route path="/admin/order-types" element={<OrderTypeListPage />} />
              <Route path="/admin/order-types/new" element={<OrderTypeEditorPage />} />
              <Route path="/admin/order-types/:code/workflow" element={<WorkflowDesignerPage />} />
            </Route>
            {/* Customer Portal is a deliberately separate, narrower surface — no ops/admin chrome (UI spec §3, §5). */}
            <Route element={<CustomerLayout />}>
              <Route path="/track/:orderId" element={<OrderTrackingPage />} />
            </Route>
          </Routes>
        </BrowserRouter>
      </ActingUserProvider>
    </QueryClientProvider>
  )
}
