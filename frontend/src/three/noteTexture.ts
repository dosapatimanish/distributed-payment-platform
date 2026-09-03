import * as THREE from 'three'

/** Stylised dollar-bill note face → canvas texture.
 *  Vibrant dollar green, dark-green double border, bold $ medallion. */
export function makeNoteTexture(): THREE.CanvasTexture {
  const W = 1024
  const H = 440 // ~2.33:1
  const c = document.createElement('canvas')
  c.width = W
  c.height = H
  const g = c.getContext('2d')!

  const GREEN = '#33ad49' // note field
  const GREEN_HI = '#3ec457'
  const DARK = '#136b30' // border + medallion
  const LIGHT = '#8fe0a0' // $ sign, corner marks

  // field
  const bg = g.createLinearGradient(0, 0, 0, H)
  bg.addColorStop(0, GREEN_HI)
  bg.addColorStop(1, GREEN)
  g.fillStyle = bg
  g.fillRect(0, 0, W, H)

  // double-rule border
  g.strokeStyle = DARK
  g.lineWidth = 10
  g.strokeRect(24, 24, W - 48, H - 48)
  g.lineWidth = 3
  g.strokeRect(44, 44, W - 88, H - 88)

  // centre medallion
  const cx = W / 2
  const cy = H / 2
  g.fillStyle = DARK
  g.beginPath()
  g.arc(cx, cy, 132, 0, Math.PI * 2)
  g.fill()
  g.strokeStyle = LIGHT
  g.lineWidth = 4
  g.beginPath()
  g.arc(cx, cy, 132, 0, Math.PI * 2)
  g.stroke()

  // $ glyph
  g.fillStyle = LIGHT
  g.textAlign = 'center'
  g.textBaseline = 'middle'
  g.font = '800 190px "Space Grotesk", system-ui, sans-serif'
  g.fillText('$', cx, cy + 10)

  // wordmarks
  g.fillStyle = DARK
  g.font = '700 26px "Space Grotesk", system-ui, sans-serif'
  g.fillText('G R E E N B A C K', cx, 70)
  g.font = '600 15px "Space Grotesk", system-ui, sans-serif'
  g.fillText('ONE  DISTRIBUTED  DOLLAR', cx, H - 64)

  // corner denominations
  g.fillStyle = LIGHT
  g.font = '800 40px "Space Grotesk", system-ui, sans-serif'
  const pad = 78
  g.fillText('1', pad, pad)
  g.fillText('1', W - pad, pad)
  g.fillText('1', pad, H - pad)
  g.fillText('1', W - pad, H - pad)

  const tex = new THREE.CanvasTexture(c)
  tex.colorSpace = THREE.SRGBColorSpace
  tex.anisotropy = 8
  tex.needsUpdate = true
  return tex
}
