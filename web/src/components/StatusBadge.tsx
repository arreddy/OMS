import { useBadgeCategory } from '../hooks/useStatusTaxonomy'
import { BADGE_COLORS, UNKNOWN_BADGE_COLOR } from '../lib/badgeColors'

/** Status badge — color is always reinforced by the status code text (UI-SPEC.md §5 accessibility rule). */
export function StatusBadge({ code }: { code: string }) {
  const category = useBadgeCategory(code)
  const colors = category ? BADGE_COLORS[category] : UNKNOWN_BADGE_COLOR
  return (
    <span className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs font-medium ${colors.bg} ${colors.text}`}>
      <span className={`h-1.5 w-1.5 rounded-full ${colors.dot}`} aria-hidden="true" />
      {code}
    </span>
  )
}
