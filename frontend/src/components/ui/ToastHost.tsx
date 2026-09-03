import { useEffect, useState } from 'react'
import { onToast } from '../../lib/toast'

interface Item {
  id: number
  message: string
}

export function ToastHost() {
  const [items, setItems] = useState<Item[]>([])

  useEffect(() => {
    return onToast((message) => {
      const id = Date.now() + Math.random()
      setItems((prev) => [...prev, { id, message }])
      window.setTimeout(() => {
        setItems((prev) => prev.filter((i) => i.id !== id))
      }, 3200)
    })
  }, [])

  return (
    <div className="pointer-events-none fixed bottom-4 right-4 z-50 flex flex-col gap-2">
      {items.map((i) => (
        <div
          key={i.id}
          className="pointer-events-auto rounded-lg border border-gold/50 bg-surface px-4 py-2.5 text-sm text-text shadow-lg"
        >
          {i.message}
        </div>
      ))}
    </div>
  )
}
