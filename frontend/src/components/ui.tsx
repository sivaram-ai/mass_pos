import { useEffect, useRef } from 'react'
import type { ReactNode } from 'react'

export function Panel({ title, actions, children, className = '' }: {
  title?: ReactNode
  actions?: ReactNode
  children: ReactNode
  className?: string
}) {
  return (
    <section className={`rounded-lg border border-slate-200 bg-white shadow-sm ${className}`}>
      {(title || actions) && (
        <header className="flex items-center justify-between gap-3 border-b border-slate-200 px-4 py-2">
          <h2 className="text-sm font-semibold text-slate-700">{title}</h2>
          <div className="flex items-center gap-2">{actions}</div>
        </header>
      )}
      <div className="p-4">{children}</div>
    </section>
  )
}

type ButtonProps = {
  children: ReactNode
  onClick?: () => void
  type?: 'button' | 'submit'
  tone?: 'primary' | 'default' | 'danger' | 'ghost'
  hint?: string
  disabled?: boolean
  className?: string
}

export function Button({ children, onClick, type = 'button', tone = 'default', hint, disabled, className = '' }: ButtonProps) {
  const tones = {
    primary: 'bg-sky-600 text-white hover:bg-sky-500 disabled:bg-slate-300',
    default: 'bg-white text-slate-700 border border-slate-300 hover:bg-slate-50 disabled:text-slate-400',
    danger: 'bg-rose-600 text-white hover:bg-rose-500 disabled:bg-slate-300',
    ghost: 'text-slate-600 hover:bg-slate-100',
  }
  return (
    <button
      type={type}
      onClick={onClick}
      disabled={disabled}
      className={`inline-flex items-center gap-2 rounded-md px-3 py-2 text-sm font-medium transition disabled:cursor-not-allowed ${tones[tone]} ${className}`}
    >
      {children}
      {hint && <kbd>{hint}</kbd>}
    </button>
  )
}

/** A labelled input. `error` replaces the hint and marks the input red until the next save. */
export function Field({ label, hint, error, optional, children }: {
  label: string
  hint?: string
  error?: string
  optional?: boolean
  children: ReactNode
}) {
  return (
    <label className={`block ${error ? '[&_input]:border-rose-400 [&_textarea]:border-rose-400 [&_select]:border-rose-400' : ''}`}>
      <span className="mb-1 block text-xs font-medium text-slate-500">
        {label}
        {optional && <span className="ml-1 font-normal text-slate-400">(optional)</span>}
      </span>
      {children}
      {error
        ? <span className="mt-1 block text-xs font-medium text-rose-600">{label} {error}</span>
        : hint && <span className="mt-1 block text-xs text-slate-400">{hint}</span>}
    </label>
  )
}

/** Field messages whose key starts with `prefix` (`address` matches `address[1]`), joined for one input. */
export function errorFor(errors: Record<string, string>, prefix: string): string | undefined {
  const found = Object.entries(errors)
    .filter(([field]) => field === prefix || field.startsWith(`${prefix}[`) || field.startsWith(`${prefix}.`))
    .map(([field, message]) => {
      const line = /\[(\d+)]/.exec(field)
      return line ? `line ${Number(line[1]) + 1} ${message}` : message
    })
  return found.length ? found.join('; ') : undefined
}

export const inputClass =
  'w-full rounded-md border border-slate-300 px-3 py-2 text-sm outline-none focus:border-sky-500 focus:ring-2 focus:ring-sky-100'

export function Modal({ title, children, onClose, wide }: {
  title: string
  children: ReactNode
  onClose: () => void
  wide?: boolean
}) {
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.stopPropagation()
        onClose()
      }
    }
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  }, [onClose])

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/50 p-4" onMouseDown={onClose}>
      <div
        className={`w-full ${wide ? 'max-w-3xl' : 'max-w-md'} rounded-lg bg-white shadow-xl`}
        onMouseDown={(event) => event.stopPropagation()}
      >
        <header className="flex items-center justify-between border-b border-slate-200 px-4 py-3">
          <h2 className="font-semibold text-slate-800">{title}</h2>
          <button className="text-slate-400 hover:text-slate-600" onClick={onClose} aria-label="Close">✕</button>
        </header>
        <div className="p-4">{children}</div>
      </div>
    </div>
  )
}

/** One-field prompt: quantity, discount, a label for a held bill. */
export function AskModal({ title, label, initial, suffix, onSubmit, onClose }: {
  title: string
  label: string
  initial?: string
  suffix?: string
  onSubmit: (value: string) => void
  onClose: () => void
}) {
  const input = useRef<HTMLInputElement>(null)
  useEffect(() => {
    input.current?.focus()
    input.current?.select()
  }, [])

  return (
    <Modal title={title} onClose={onClose}>
      <form
        onSubmit={(event) => {
          event.preventDefault()
          onSubmit(input.current?.value ?? '')
        }}
      >
        <Field label={label}>
          <div className="flex items-center gap-2">
            <input
              ref={input}
              defaultValue={initial}
              className={`${inputClass} num text-lg`}
              autoComplete="off"
              // Handled here rather than left to the browser's implicit form submission: at a
              // counter, Enter has to apply the number every time, on every keyboard.
              onKeyDown={(event) => {
                if (event.key === 'Enter') {
                  event.preventDefault()
                  onSubmit(input.current?.value ?? '')
                }
              }}
            />
            {suffix && <span className="text-sm text-slate-500">{suffix}</span>}
          </div>
        </Field>
        <div className="mt-4 flex justify-end gap-2">
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" tone="primary" hint="Enter">Apply</Button>
        </div>
      </form>
    </Modal>
  )
}

export function Banner({ tone, children, onDismiss }: {
  tone: 'good' | 'bad' | 'warn'
  children: ReactNode
  onDismiss?: () => void
}) {
  const tones = {
    good: 'bg-emerald-50 text-emerald-900 border-emerald-200',
    bad: 'bg-rose-50 text-rose-900 border-rose-200',
    warn: 'bg-amber-50 text-amber-900 border-amber-200',
  }
  return (
    <div className={`flex items-start justify-between gap-3 rounded-md border px-3 py-2 text-sm ${tones[tone]}`}>
      <div>{children}</div>
      {onDismiss && (
        <button className="text-current opacity-60 hover:opacity-100" onClick={onDismiss} aria-label="Dismiss">✕</button>
      )}
    </div>
  )
}

export function Empty({ children }: { children: ReactNode }) {
  return <p className="py-8 text-center text-sm text-slate-400">{children}</p>
}
