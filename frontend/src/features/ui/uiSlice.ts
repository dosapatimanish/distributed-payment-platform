import { createSlice, type PayloadAction } from '@reduxjs/toolkit'

export type ThemePref = 'light' | 'dark' | 'system'

interface UiState {
  theme: ThemePref
  reducedMotion: boolean
  navOpen: boolean
}

const initialState: UiState = {
  theme: 'system',
  reducedMotion: false,
  navOpen: false,
}

const uiSlice = createSlice({
  name: 'ui',
  initialState,
  reducers: {
    setTheme(state, action: PayloadAction<ThemePref>) {
      state.theme = action.payload
    },
    cycleTheme(state) {
      state.theme =
        state.theme === 'light' ? 'dark' : state.theme === 'dark' ? 'system' : 'light'
    },
    setReducedMotion(state, action: PayloadAction<boolean>) {
      state.reducedMotion = action.payload
    },
    setNavOpen(state, action: PayloadAction<boolean>) {
      state.navOpen = action.payload
    },
  },
})

export const { setTheme, cycleTheme, setReducedMotion, setNavOpen } = uiSlice.actions
export default uiSlice.reducer
