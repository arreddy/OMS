import { useQuery } from '@tanstack/react-query'
import { getStatusTaxonomy } from '../api/orderTypes'
import type { BadgeCategory } from '../types/domain'

export function useStatusTaxonomy() {
  return useQuery({
    queryKey: ['status-taxonomy'],
    queryFn: getStatusTaxonomy,
    staleTime: 60_000,
  })
}

export function useBadgeCategory(code: string | undefined): BadgeCategory | undefined {
  const { data } = useStatusTaxonomy()
  if (!code || !data) return undefined
  return data.find((entry) => entry.code === code)?.badgeCategory
}
