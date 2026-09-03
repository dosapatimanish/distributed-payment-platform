import { createSlice, type PayloadAction } from '@reduxjs/toolkit'

/** Demo customer offered as a one-click sign-in on the login page. */
export const DUMMY_CIF = '2100000042'

interface SessionState {
  cif: string | null
}

const initialState: SessionState = {
  cif: null,
}

const sessionSlice = createSlice({
  name: 'session',
  initialState,
  reducers: {
    setCif(state, action: PayloadAction<string>) {
      state.cif = action.payload
    },
    clearCif(state) {
      state.cif = null
    },
  },
})

export const { setCif, clearCif } = sessionSlice.actions
export default sessionSlice.reducer
