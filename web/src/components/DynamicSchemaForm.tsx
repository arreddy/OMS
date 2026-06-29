import type { JsonSchema } from '../types/domain'

/**
 * Renders one input per JSON Schema property — the schema-driven counterpart
 * to the read-only attribute rendering on Order/Task Detail. Used for both
 * order-level and line-level attributes so new order types never need a
 * hand-written form (SPEC.md §3.3's "no DDL, no deploy" promise extends to
 * the create-order screen, not just the schema editor).
 */
export function DynamicSchemaForm({
  schema,
  values,
  onChange,
}: {
  schema: JsonSchema | undefined
  values: Record<string, unknown>
  onChange: (values: Record<string, unknown>) => void
}) {
  const properties = schema?.properties ?? {}
  const keys = Object.keys(properties)

  if (keys.length === 0) {
    return <p className="text-sm text-gray-400">No custom attributes defined for this order type.</p>
  }

  function set(key: string, value: unknown) {
    onChange({ ...values, [key]: value })
  }

  return (
    <div className="grid grid-cols-2 gap-4">
      {keys.map((key) => {
        const prop = properties[key]
        const label = prop.title ?? key
        const required = schema?.required?.includes(key)
        const value = values[key]

        return (
          <label key={key} className="block text-sm">
            <span className="mb-1 block text-gray-600">
              {label}
              {required && <span className="text-red-500"> *</span>}
            </span>
            {prop.enum ? (
              <select
                value={(value as string) ?? ''}
                onChange={(e) => set(key, e.target.value || undefined)}
                className="w-full rounded border border-gray-300 px-2 py-1.5"
              >
                <option value="">—</option>
                {prop.enum.map((option) => (
                  <option key={option} value={option}>
                    {option}
                  </option>
                ))}
              </select>
            ) : prop.type === 'boolean' ? (
              <input type="checkbox" checked={Boolean(value)} onChange={(e) => set(key, e.target.checked)} />
            ) : prop.type === 'number' || prop.type === 'integer' ? (
              <input
                type="number"
                value={value === undefined ? '' : String(value)}
                onChange={(e) => set(key, e.target.value === '' ? undefined : Number(e.target.value))}
                className="w-full rounded border border-gray-300 px-2 py-1.5"
              />
            ) : prop.format === 'date' ? (
              <input
                type="date"
                value={(value as string) ?? ''}
                onChange={(e) => set(key, e.target.value || undefined)}
                className="w-full rounded border border-gray-300 px-2 py-1.5"
              />
            ) : (
              <input
                value={(value as string) ?? ''}
                maxLength={prop.maxLength}
                onChange={(e) => set(key, e.target.value || undefined)}
                className="w-full rounded border border-gray-300 px-2 py-1.5"
              />
            )}
          </label>
        )
      })}
    </div>
  )
}
