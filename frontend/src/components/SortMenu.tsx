import { useEffect, useRef } from 'react'
import type { KeyboardEvent as ReactKeyboardEvent } from 'react'
import { SORT_FIELDS, SORT_LABELS } from '../sorting'
import type { Sort } from '../sorting'

/**
 * The sort control, as a popover that takes focus when it opens. It has to: a list cell that kept
 * focus behind it would still receive the arrows and move the cursor instead of the choice.
 *
 * Every key applies at once, so there is no pending state to keep or discard - Enter and Esc differ
 * in nothing but which key the person reached for. A sort that previewed and then reverted on Esc
 * would be the only control on these screens that did.
 */
export function SortMenu({ sort, onChange, onClose }: {
  sort: Sort
  onChange: (sort: Sort) => void
  onClose: () => void
}) {
  const box = useRef<HTMLDivElement>(null)
  const chosen = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    box.current?.focus()
  }, [])

  useEffect(() => {
    chosen.current?.scrollIntoView({ block: 'nearest' })
  }, [sort.field])

  const move = (step: number) => {
    const at = SORT_FIELDS.indexOf(sort.field)
    onChange({ ...sort, field: SORT_FIELDS[Math.max(0, Math.min(SORT_FIELDS.length - 1, at + step))] })
  }

  const onKeyDown = (event: ReactKeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      move(event.key === 'ArrowDown' ? 1 : -1)
    } else if (event.key === 'ArrowRight' || event.key === 'ArrowLeft') {
      event.preventDefault()
      onChange({ ...sort, dir: event.key === 'ArrowRight' ? 'desc' : 'asc' })
    } else if (event.key === 'Home' || event.key === 'End') {
      event.preventDefault()
      onChange({ ...sort, field: event.key === 'Home' ? SORT_FIELDS[0] : SORT_FIELDS[SORT_FIELDS.length - 1] })
    } else if (event.key === 'Enter' || event.key === 'Escape') {
      event.preventDefault()
      // Esc here only closes the popover: it must not count toward Esc Esc back to billing.
      event.stopPropagation()
      event.nativeEvent.stopImmediatePropagation()
      onClose()
    }
  }

  return (
    <>
      {/* Clicking anywhere else closes it, the way the dialogs do. */}
      <div className="fixed inset-0 z-40" onMouseDown={onClose} />
      <div
        ref={box}
        tabIndex={-1}
        role="listbox"
        aria-label="Sort by"
        aria-activedescendant={`sort-${sort.field}`}
        onKeyDown={onKeyDown}
        className="absolute right-0 top-full z-50 mt-1 w-64 rounded-lg border border-slate-200 bg-white p-2 text-left shadow-xl outline-none"
      >
        <p className="px-2 pb-1 text-xs font-semibold uppercase tracking-wide text-slate-400">Sort by</p>
        <div className="max-h-60 overflow-y-auto">
          {SORT_FIELDS.map((field) => {
            const isChosen = field === sort.field
            return (
              <button
                key={field}
                id={`sort-${field}`}
                ref={isChosen ? chosen : undefined}
                type="button"
                tabIndex={-1}
                role="option"
                aria-selected={isChosen}
                onClick={() => onChange({ ...sort, field })}
                className={`block w-full rounded-md px-2 py-1.5 text-left text-sm transition ${
                  isChosen ? 'bg-sky-600 font-medium text-white' : 'text-slate-700 hover:bg-slate-100'}`}
              >
                {SORT_LABELS[field]}
              </button>
            )
          })}
        </div>

        <div className="mt-2 border-t border-slate-100 pt-2">
          <div className="flex gap-1">
            {([['asc', 'Ascending', '←'], ['desc', 'Descending', '→']] as const).map(([dir, label, key]) => (
              <button
                key={dir}
                type="button"
                tabIndex={-1}
                aria-pressed={sort.dir === dir}
                onClick={() => onChange({ ...sort, dir })}
                className={`flex-1 rounded-md px-2 py-1.5 text-xs font-medium transition ${
                  sort.dir === dir ? 'bg-slate-800 text-white' : 'text-slate-600 hover:bg-slate-100'}`}
              >
                <kbd className="mr-1">{key}</kbd>{label}
              </button>
            ))}
          </div>
          <p className="mt-2 px-1 text-xs text-slate-400">
            <kbd>↑</kbd> <kbd>↓</kbd> field · <kbd>Enter</kbd> or <kbd>Esc</kbd> back to the list
          </p>
        </div>
      </div>
    </>
  )
}
