// DTOs from the 5 backend services (wallet / fx / orchestrator / merchant / ledger).

export type WalletStatus = 'ACTIVE' | 'FROZEN' | 'CLOSED'

export interface Wallet {
  accountNo: string // 12 digits
  cif: string // 10 digits
  currency: string // ISO 4217 alpha-3
  balance: string // decimal string, up to 4 dp
  status: WalletStatus
  highContention: boolean
  createdAt: string
  updatedAt: string
}

/** GET /wallets/{accountNo}/balance */
export interface BalanceResponse {
  accountNo: string
  currency: string
  balance: string
  status: WalletStatus
}

export type ReservationStatus = 'HELD' | 'CAPTURED' | 'RELEASED'

export interface Reservation {
  reservationId: string
  accountNo: string
  transactionId: string
  amount: string
  status: ReservationStatus
  createdAt: string
  expiresAt: string
}

export interface Currency {
  code: string
  numericCode: string
  shortCode: string
  name: string
  minorUnits: number
  active: boolean
}

export interface RateResponse {
  baseCurrency: string
  quoteCurrency: string
  rate: number
  source: string
  effectiveAt: string
}

export type RateLockStatus = 'ACTIVE' | 'CONSUMED' | 'RELEASED' | 'EXPIRED'

export interface RateLock {
  lockId: string
  transactionId: string
  baseCurrency: string
  quoteCurrency: string
  lockedRate: number
  amount: string
  status: RateLockStatus
  createdAt: string
  expiresAt: string
}

export type SagaState =
  | 'STARTED'
  | 'RATE_LOCKED'
  | 'SOURCE_DEBITED'
  | 'DEST_CREDITED'
  | 'PAYMENT_COMPLETED'
  | 'COMPLETED'
  | 'FAILED'
  | 'DEBIT_FAILED'
  | 'CREDIT_FAILED'
  | 'PAYMENT_FAILED'
  | 'COMPENSATING'
  | 'DEST_DEBITED_BACK'
  | 'SOURCE_CREDITED_BACK'
  | 'LOCK_RELEASED'
  | 'COMPENSATED'

export const TERMINAL_SAGA: SagaState[] = ['COMPLETED', 'FAILED', 'COMPENSATED']

export const FAILED_SAGA: SagaState[] = [
  'FAILED',
  'DEBIT_FAILED',
  'CREDIT_FAILED',
  'PAYMENT_FAILED',
  'COMPENSATED',
]

export interface SagaEvent {
  state: SagaState
  at: string
  note?: string
}

/** GET/POST /conversions — backend fields, plus `history` / `merchantId` / `error`
 *  which the client fills in from observed polling + the original request. */
export interface Conversion {
  transactionId: string
  cif: string
  sourceAccountNo: string
  destAccountNo: string
  sourceCurrency: string
  destCurrency: string
  sourceAmount: string
  destAmount: string | null
  lockedRate: number | null
  sagaState: SagaState
  merchantId: string | null
  paymentId: string | null
  error: string | null
  createdAt: string
  updatedAt: string
  history: SagaEvent[]
}

export type PaymentStatus = 'PENDING' | 'COMPLETED' | 'FAILED' | 'REFUNDED'

export interface Payment {
  paymentId: string
  transactionId: string
  merchantId: string
  amount: string
  currency: string
  acquirerRef: string | null
  status: PaymentStatus
  createdAt: string
  updatedAt: string
}

export type EntryType = 'DEBIT' | 'CREDIT'

export interface LedgerEntry {
  transactionId: string
  entryNo: string
  accountNo: string
  entryType: EntryType
  amount: string
  currency: string
  balanceAfter: string
  createdAt: string
}

export interface StatementResponse {
  accountNo: string
  entries: LedgerEntry[]
}
