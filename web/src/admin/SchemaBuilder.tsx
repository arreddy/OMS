import type { JsonSchema } from '../types/domain'

export interface FieldRow {
  id: string
  key: string
  label: string
  type: 'string' | 'number' | 'boolean' | 'array' | 'object'
  format: string
  useEnum: boolean
  enumValues: string
  required: boolean
  showInTask: boolean
  customerVisible: boolean
}

export function emptyRow(): FieldRow {
  return {
    id: crypto.randomUUID(),
    key: '',
    label: '',
    type: 'string',
    format: '',
    useEnum: false,
    enumValues: '',
    required: false,
    showInTask: false,
    customerVisible: false,
  }
}

/**
 * Repeatable-row form over raw JSON Schema (UI-SPEC.md §4.2). "Type" is a real
 * JSON Schema type; date-ness is a separate Format control on string fields
 * (date isn't its own type), and enum is a separate restriction, not a type —
 * see UI-SPEC.md §4.2 for why this was split out from a single "Type" dropdown.
 */
export function SchemaBuilder({ rows, onChange }: { rows: FieldRow[]; onChange: (rows: FieldRow[]) => void }) {
  function update(id: string, patch: Partial<FieldRow>) {
    onChange(rows.map((r) => (r.id === id ? { ...r, ...patch } : r)))
  }
  function remove(id: string) {
    onChange(rows.filter((r) => r.id !== id))
  }

  return (
    <div className="space-y-2">
      {rows.map((row) => (
        <div key={row.id} className="grid grid-cols-12 items-center gap-2 rounded border border-gray-200 p-2 text-sm">
          <input
            value={row.key}
            onChange={(e) => update(row.id, { key: e.target.value })}
            placeholder="field_key"
            className="col-span-2 rounded border border-gray-300 px-2 py-1 font-mono text-xs"
          />
          <input
            value={row.label}
            onChange={(e) => update(row.id, { label: e.target.value })}
            placeholder="Label"
            className="col-span-2 rounded border border-gray-300 px-2 py-1"
          />
          <select
            value={row.type}
            onChange={(e) => update(row.id, { type: e.target.value as FieldRow['type'], format: '' })}
            className="col-span-1 rounded border border-gray-300 px-1 py-1"
          >
            <option value="string">string</option>
            <option value="number">number</option>
            <option value="boolean">boolean</option>
            <option value="array">array</option>
            <option value="object">object</option>
          </select>
          {row.type === 'string' ? (
            <select
              value={row.format}
              onChange={(e) => update(row.id, { format: e.target.value })}
              className="col-span-1 rounded border border-gray-300 px-1 py-1"
            >
              <option value="">(none)</option>
              <option value="date">date</option>
              <option value="date-time">date-time</option>
              <option value="email">email</option>
            </select>
          ) : (
            <span className="col-span-1" />
          )}
          <label className="col-span-1 flex items-center gap-1 text-xs">
            <input type="checkbox" checked={row.required} onChange={(e) => update(row.id, { required: e.target.checked })} />
            Required
          </label>
          <label className="col-span-1 flex items-center gap-1 text-xs">
            <input type="checkbox" checked={row.showInTask} onChange={(e) => update(row.id, { showInTask: e.target.checked })} />
            Show in task
          </label>
          <label className="col-span-1 flex items-center gap-1 text-xs">
            <input
              type="checkbox"
              checked={row.customerVisible}
              onChange={(e) => update(row.id, { customerVisible: e.target.checked })}
            />
            Cust. visible
          </label>
          <label className="col-span-1 flex items-center gap-1 text-xs">
            <input type="checkbox" checked={row.useEnum} onChange={(e) => update(row.id, { useEnum: e.target.checked })} />
            Enum
          </label>
          {row.useEnum ? (
            <input
              value={row.enumValues}
              onChange={(e) => update(row.id, { enumValues: e.target.value })}
              placeholder="a,b,c"
              className="col-span-1 rounded border border-gray-300 px-1 py-1 text-xs"
            />
          ) : (
            <span className="col-span-1" />
          )}
          <button type="button" onClick={() => remove(row.id)} className="col-span-1 text-xs text-red-600 hover:underline">
            Remove
          </button>
        </div>
      ))}
      <button
        type="button"
        onClick={() => onChange([...rows, emptyRow()])}
        className="text-sm font-medium text-blue-600 hover:underline"
      >
        + Add field
      </button>
    </div>
  )
}

export function rowsToJsonSchema(rows: FieldRow[]): JsonSchema {
  const properties: JsonSchema['properties'] = {}
  const required: string[] = []
  for (const row of rows) {
    if (!row.key.trim()) continue
    const prop: NonNullable<JsonSchema['properties']>[string] = {
      type: row.type,
      title: row.label || row.key,
    }
    if (row.type === 'string' && row.format) prop.format = row.format
    if (row.useEnum && row.enumValues.trim()) {
      prop.enum = row.enumValues
        .split(',')
        .map((v) => v.trim())
        .filter(Boolean)
    }
    if (row.showInTask) prop['x-show-in-task'] = true
    if (row.customerVisible) prop['x-customer-visible'] = true
    properties[row.key] = prop
    if (row.required) required.push(row.key)
  }
  return { type: 'object', properties, required, additionalProperties: true }
}
