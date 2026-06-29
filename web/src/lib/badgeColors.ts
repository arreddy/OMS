import type { BadgeCategory } from '../types/domain'

/** Mirrors UI-SPEC.md §6 — color is always paired with the text/dot, never alone. */
export const BADGE_COLORS: Record<BadgeCategory, { bg: string; text: string; dot: string }> = {
  AUTOMATIC: { bg: 'bg-blue-100', text: 'text-blue-800', dot: 'bg-blue-500' },
  MANUAL: { bg: 'bg-amber-100', text: 'text-amber-800', dot: 'bg-amber-500' },
  WAIT: { bg: 'bg-gray-200', text: 'text-gray-700', dot: 'bg-gray-500' },
  TERMINAL_SUCCESS: { bg: 'bg-green-100', text: 'text-green-800', dot: 'bg-green-500' },
  TERMINAL_FAILURE: { bg: 'bg-red-100', text: 'text-red-800', dot: 'bg-red-500' },
}

export const UNKNOWN_BADGE_COLOR = { bg: 'bg-gray-100', text: 'text-gray-600', dot: 'bg-gray-400' }
