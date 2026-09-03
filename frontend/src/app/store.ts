import { combineReducers, configureStore } from '@reduxjs/toolkit'
import {
  persistReducer,
  persistStore,
  FLUSH,
  REHYDRATE,
  PAUSE,
  PERSIST,
  PURGE,
  REGISTER,
} from 'redux-persist'
import ui from '../features/ui/uiSlice'
import session from '../features/session/sessionSlice'

// Minimal localStorage adapter — avoids redux-persist's ESM default-export
// interop breaking under Vite ("storage.getItem is not a function").
const storage = {
  getItem: (key: string) => Promise.resolve(safeLocal()?.getItem(key) ?? null),
  setItem: (key: string, value: string) => {
    safeLocal()?.setItem(key, value)
    return Promise.resolve()
  },
  removeItem: (key: string) => {
    safeLocal()?.removeItem(key)
    return Promise.resolve()
  },
}

function safeLocal(): Storage | null {
  try {
    return window.localStorage
  } catch {
    return null
  }
}

// Persist the theme choice and the signed-in CIF.
const uiPersisted = persistReducer({ key: 'ui', storage, whitelist: ['theme'] }, ui)
const sessionPersisted = persistReducer(
  { key: 'session', storage, whitelist: ['cif'] },
  session,
)

const rootReducer = combineReducers({ ui: uiPersisted, session: sessionPersisted })

export const store = configureStore({
  reducer: rootReducer,
  middleware: (getDefaultMiddleware) =>
    getDefaultMiddleware({
      serializableCheck: {
        ignoredActions: [FLUSH, REHYDRATE, PAUSE, PERSIST, PURGE, REGISTER],
      },
    }),
})

export const persistor = persistStore(store)

export type RootState = ReturnType<typeof store.getState>
export type AppDispatch = typeof store.dispatch
