/** System copy, not personified — UI-SPEC.md §5: "Couldn't load tasks. Retry", never "Oops!". */
export function EmptyState({
  message,
  actionLabel,
  onAction,
}: {
  message: string
  actionLabel?: string
  onAction?: () => void
}) {
  return (
    <div className="flex flex-col items-center gap-2 py-16 text-center text-gray-500">
      <p>{message}</p>
      {actionLabel && onAction && (
        <button type="button" onClick={onAction} className="text-sm font-medium text-blue-600 hover:underline">
          {actionLabel}
        </button>
      )}
    </div>
  )
}
