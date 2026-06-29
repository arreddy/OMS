import { ApiError } from '../lib/api'

export function ErrorBanner({ error }: { error: unknown }) {
  if (!error) return null
  const message = error instanceof ApiError ? error.message : 'Something went wrong.'
  const violations = error instanceof ApiError ? error.violations : undefined
  return (
    <div className="mb-4 rounded border border-red-300 bg-red-50 px-4 py-2 text-sm text-red-900">
      <p>{message}</p>
      {violations && violations.length > 0 && (
        <ul className="mt-1 list-inside list-disc">
          {violations.map((v) => (
            <li key={v}>{v}</li>
          ))}
        </ul>
      )}
    </div>
  )
}
