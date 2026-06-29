/** SLA due column — color plus an explicit text label per state, never color alone (UI-SPEC.md §2.3). */
export function SlaBadge({ slaDueAt }: { slaDueAt: string | null }) {
  if (!slaDueAt) {
    return <span className="text-xs text-gray-400">No SLA</span>
  }
  const hoursRemaining = (new Date(slaDueAt).getTime() - Date.now()) / 3_600_000
  let colors = 'bg-green-100 text-green-800'
  let label = 'On track'
  if (hoursRemaining < 0) {
    colors = 'bg-red-100 text-red-800'
    label = 'Overdue'
  } else if (hoursRemaining < 4) {
    colors = 'bg-amber-100 text-amber-800'
    label = 'Due soon'
  }
  return (
    <span className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ${colors}`}>
      {label} · {new Date(slaDueAt).toLocaleString()}
    </span>
  )
}
