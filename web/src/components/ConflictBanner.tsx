/** UI-SPEC.md §5: "This {record} changed since you loaded it" — message must match what the user was acting on. */
export function ConflictBanner({ recordLabel, onRefresh }: { recordLabel: string; onRefresh: () => void }) {
  return (
    <div className="mb-4 flex items-center justify-between rounded border border-amber-300 bg-amber-50 px-4 py-2 text-sm text-amber-900">
      <span>This {recordLabel} changed since you loaded it.</span>
      <button type="button" onClick={onRefresh} className="font-medium underline">
        Refresh
      </button>
    </div>
  )
}
