import { useEffect, useState } from 'react'
import { api, messageOfFailure } from '../api'
import type { DatabaseView } from '../types'
import { Banner, Button, Empty, Modal, Panel } from './ui'
import type { Toast } from '../App'

const sizeOf = (bytes: number) =>
  bytes >= 1024 * 1024 ? `${(bytes / 1024 / 1024).toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`

const whenOf = (iso: string) => new Date(iso).toLocaleString('en-IN', {
  day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit',
})

/**
 * Copies of everything the shop has sold. A backup is taken without stopping the till; putting one
 * back waits for the next start, because the database cannot be swapped while it is open.
 */
export default function DatabasePanel({ canEdit, onToast }: { canEdit: boolean; onToast: Toast }) {
  const [view, setView] = useState<DatabaseView | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [confirming, setConfirming] = useState<string | null>(null)

  const load = () => {
    api.get<DatabaseView>('/api/settings/database')
      .then((next) => { setView(next); setLoadError(null) })
      .catch((failure) => setLoadError(messageOfFailure(failure, 'Could not read the backups')))
  }

  useEffect(load, [])

  const run = async (work: Promise<DatabaseView>, done: string) => {
    setBusy(true)
    try {
      setView(await work)
      onToast({ tone: 'good', text: done })
    } catch (failure) {
      onToast({ tone: 'bad', text: messageOfFailure(failure, 'That did not work') })
    } finally {
      setBusy(false)
      setConfirming(null)
    }
  }

  if (loadError) {
    return <Panel title="Database"><Banner tone="bad">{loadError}</Banner></Panel>
  }
  if (!view) {
    return <Panel title="Database"><Empty>Looking for backups…</Empty></Panel>
  }

  return (
    <Panel
      title="Database"
      className="xl:col-span-2"
      actions={canEdit
        ? (
          <Button tone="primary" disabled={busy}
                  onClick={() => void run(api.post<DatabaseView>('/api/settings/database/backup'), 'Backup taken')}>
            {busy ? 'Working…' : 'Back up now'}
          </Button>
        )
        : <span className="text-xs text-slate-500">Only an admin can do this</span>}
    >
      {view.restoreFrom && (
        <div className="mb-3">
          <Banner tone="warn">
            <p className="font-medium">
              {view.restoreFrom} will replace today's database when this till next starts.
            </p>
            <p>Close and reopen GO RockIt to finish, or cancel to carry on as you are.</p>
            {canEdit && (
              <Button className="mt-2" disabled={busy}
                      onClick={() => void run(api.delete<DatabaseView>('/api/settings/database/restore'),
                        'Restore cancelled')}>
                Cancel the restore
              </Button>
            )}
          </Banner>
        </div>
      )}

      <p className="mb-3 text-sm text-slate-600">
        A backup holds every bill, product and setting as it stands now. Copy the files somewhere
        else - a pen drive is enough - and the shop survives losing this machine.
      </p>

      {view.backups.length === 0 ? (
        <Empty>No backups yet. Take one now, and again at the end of each day.</Empty>
      ) : (
        <table className="w-full table-fixed text-sm">
          <colgroup>
            <col />
            <col className="w-48" />
            <col className="w-24" />
            <col className="w-36" />
          </colgroup>
          <thead className="text-xs uppercase tracking-wide text-slate-400">
            <tr>
              <th className="py-1 pl-2 text-left">File</th>
              <th className="py-1 text-left">Taken</th>
              <th className="py-1 text-right">Size</th>
              <th className="py-1"></th>
            </tr>
          </thead>
          <tbody>
            {view.backups.map((backup) => (
              <tr key={backup.name} className="border-t border-slate-100">
                <td className="num truncate py-2 pl-2 pr-3" title={backup.name}>{backup.name}</td>
                <td className="py-2 text-slate-600">{whenOf(backup.takenAt)}</td>
                <td className="num py-2 text-right text-slate-500">{sizeOf(backup.sizeBytes)}</td>
                <td className="py-2 text-right">
                  {canEdit && !backup.restorePending && (
                    <Button tone="ghost" disabled={busy} onClick={() => setConfirming(backup.name)}>
                      Put this back
                    </Button>
                  )}
                  {backup.restorePending && <span className="text-xs text-amber-700">waiting for a restart</span>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <p className="mt-3 text-xs text-slate-400">
        The files are in {view.folder}. Anything you copy into that folder shows up here.
      </p>

      {confirming && (
        <Modal title="Put this backup back?" onClose={() => setConfirming(null)}>
          <p className="text-sm text-slate-600">
            <span className="num font-medium text-slate-800">{confirming}</span> will replace everything
            on this till - bills, products, staff and settings - with what it held when it was taken.
          </p>
          <p className="mt-2 text-sm text-slate-600">
            Today's database is copied first, so you can put it back the same way if this was a
            mistake. Nothing changes until the till is restarted.
          </p>
          <div className="mt-4 flex justify-end gap-2">
            <Button onClick={() => setConfirming(null)}>Cancel</Button>
            <Button tone="danger" disabled={busy}
                    onClick={() => void run(
                      api.post<DatabaseView>('/api/settings/database/restore', { file: confirming }),
                      'Restart the till to finish putting that backup back')}>
              Yes, use this one
            </Button>
          </div>
        </Modal>
      )}
    </Panel>
  )
}
