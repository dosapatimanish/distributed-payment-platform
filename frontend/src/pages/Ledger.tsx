import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useAppSelector } from '../app/hooks'
import { NoteFrame } from '../components/note/NoteFrame'
import { Empty, ErrorNote, Select } from '../components/ui/controls'
import { formatDateTime, formatMoney } from '../lib/format'
import { platform } from '../api/client'
import { usePlatformQuery } from '../api/hooks'

export default function Ledger() {
  const cif = useAppSelector((s) => s.session.cif)
  const wallets = usePlatformQuery(() => platform.listWallets(cif ?? ''), [cif])
  const [accountNo, setAccountNo] = useState('')

  const list = wallets.data ?? []
  useEffect(() => {
    if (!accountNo && list.length) setAccountNo(list[0].accountNo)
  }, [list, accountNo])

  const statement = usePlatformQuery(
    () =>
      accountNo
        ? platform.getStatement(accountNo)
        : Promise.resolve({ accountNo: '', entries: [] }),
    [accountNo],
  )

  if (!cif) {
    return (
      <NoteFrame denomination="—">
        <h1 className="text-2xl">No session</h1>
        <Link to="/" className="mt-4 inline-block text-sm text-accent">
          ← Home
        </Link>
      </NoteFrame>
    )
  }

  const entries = statement.data?.entries ?? []
  const wallet = list.find((w) => w.accountNo === accountNo)
  const debits = entries
    .filter((e) => e.entryType === 'DEBIT')
    .reduce((s, e) => s + Number(e.amount), 0)
  const credits = entries
    .filter((e) => e.entryType === 'CREDIT')
    .reduce((s, e) => s + Number(e.amount), 0)

  return (
    <div className="flex flex-col gap-8">
      <header>
        <h1 className="text-2xl">Ledger</h1>
        <p className="mono mt-1 text-xs uppercase tracking-[0.2em] text-muted">
          immutable double-entry statement · CIF {cif}
        </p>
      </header>

      <NoteFrame denomination="acct">
        <div className="grid gap-3 sm:max-w-md">
          <Select
            label="Account"
            value={accountNo}
            onChange={(e) => setAccountNo(e.target.value)}
            options={list.map((w) => ({
              value: w.accountNo,
              label: `${w.currency} · ${w.accountNo}`,
            }))}
          />
        </div>
        {wallet && (
          <div className="mt-4 flex flex-wrap gap-x-8 gap-y-2 text-sm">
            <span className="text-muted">
              current balance{' '}
              <span className="mono text-text">
                {formatMoney(wallet.balance, wallet.currency)}
              </span>
            </span>
            <span className="text-muted">
              debits{' '}
              <span className="mono text-text">
                {formatMoney(debits, wallet.currency)}
              </span>
            </span>
            <span className="text-muted">
              credits{' '}
              <span className="mono text-text">
                {formatMoney(credits, wallet.currency)}
              </span>
            </span>
          </div>
        )}
      </NoteFrame>

      {statement.error && <ErrorNote>{statement.error}</ErrorNote>}

      <NoteFrame denomination="stmt">
        {entries.length === 0 ? (
          <Empty>No ledger entries for this account.</Empty>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[560px] text-sm">
              <thead>
                <tr className="mono text-[0.65rem] uppercase tracking-widest text-muted">
                  <th className="py-2 text-left font-normal">Txn / entry</th>
                  <th className="py-2 text-left font-normal">Type</th>
                  <th className="py-2 text-right font-normal">Amount</th>
                  <th className="py-2 text-right font-normal">Balance after</th>
                  <th className="py-2 text-right font-normal">Posted</th>
                </tr>
              </thead>
              <tbody>
                {entries.map((e) => (
                  <tr
                    key={`${e.transactionId}-${e.entryNo}`}
                    className="border-t border-line"
                  >
                    <td className="py-2 mono text-xs">
                      {e.transactionId}
                      <span className="text-muted"> · {e.entryNo}</span>
                    </td>
                    <td
                      className={
                        'py-2 mono text-xs ' +
                        (e.entryType === 'CREDIT' ? 'text-accent' : 'text-gold')
                      }
                    >
                      {e.entryType}
                    </td>
                    <td className="py-2 text-right mono tabular-nums">
                      {formatMoney(e.amount, e.currency)}
                    </td>
                    <td className="py-2 text-right mono tabular-nums text-muted">
                      {formatMoney(e.balanceAfter, e.currency)}
                    </td>
                    <td className="py-2 text-right mono text-xs text-muted">
                      {formatDateTime(e.createdAt)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </NoteFrame>
    </div>
  )
}
