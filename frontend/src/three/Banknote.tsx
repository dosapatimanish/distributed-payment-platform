import { useEffect, useMemo, useRef } from 'react'
import { Canvas, useFrame } from '@react-three/fiber'
import { ContactShadows, Sparkles } from '@react-three/drei'
import * as THREE from 'three'
import { useAppSelector } from '../app/hooks'
import { useSystemTheme } from '../hooks/useSystemTheme'
import { gsap } from '../lib/gsap'
import { makeNoteTexture } from './noteTexture'
import { glowTexture } from './glow'
import type { LoaderStatus } from './types'

const NOTES = 8
const SEG_X = 28
const SEG_Y = 12
const W = 3.0
const H = 1.28

// pivot the fan swings around (a hand holding the bottom of the wad)
const PIVOT_Y = -0.42
const RADIUS = 0.5

// base tint the lit material lerps from (green so shading never greys it out)
const BASE = new THREE.Color('#e8f7ea')

interface Drivers {
  wave: number // per-note cloth ripple
  speed: number
  riffle: number // small independent flutter
  fan: number // radians between adjacent notes → the fan spread
  shuffle: number // travelling wave through the fan (riffling cash)
  droop: number
  scatter: number
  move: number // bundle sway amplitude
  scale: number
  tint: number
}

const IDLE: Drivers = {
  wave: 0.4,
  speed: 0.9,
  riffle: 0.3,
  fan: 0.108,
  shuffle: 0,
  droop: 0,
  scatter: 0,
  move: 1,
  scale: 1,
  tint: 0,
}

interface NoteCfg {
  geo: THREE.PlaneGeometry
  base: Float32Array
  seed: number
  rot: number
  ox: number
  oy: number
}

function Bundle({ status, dark }: { status: LoaderStatus; dark: boolean }) {
  const group = useRef<THREE.Group>(null)
  const meshes = useRef<(THREE.Mesh | null)[]>([])

  const map = useMemo(() => makeNoteTexture(), [])
  const material = useMemo(
    () =>
      new THREE.MeshStandardMaterial({
        map,
        color: BASE.clone(),
        side: THREE.DoubleSide,
        roughness: 0.85,
        metalness: 0,
        emissive: new THREE.Color('#1f8f3d'),
        emissiveIntensity: dark ? 0.14 : 0.08,
      }),
    [map, dark],
  )

  const notes = useMemo<NoteCfg[]>(
    () =>
      Array.from({ length: NOTES }, (_, i) => {
        const geo = new THREE.PlaneGeometry(W, H, SEG_X, SEG_Y)
        return {
          geo,
          base: Float32Array.from(geo.attributes.position.array),
          seed: i * 1.937 + 0.31,
          rot: (Math.random() - 0.5) * 0.08,
          ox: (Math.random() - 0.5) * 0.05,
          oy: (Math.random() - 0.5) * 0.04,
        }
      }),
    [],
  )

  const d = useRef<Drivers>({ ...IDLE })
  const tintColor = useRef(new THREE.Color('#c9a85c'))

  useEffect(() => {
    const cur = d.current
    gsap.killTweensOf(cur)
    type Vars = Parameters<typeof gsap.to>[1]
    const to = (vars: Partial<Drivers>, opts: Vars = {}) =>
      gsap.to(cur, { duration: 1, ease: 'power3.out', ...vars, ...opts })
    const loop = (vars: Vars) =>
      gsap.to(cur, { repeat: -1, yoyo: true, ease: 'sine.inOut', ...vars })

    if (status === 'idle') {
      to({ ...IDLE })
      loop({ fan: 0.14, duration: 4.2 }) // slow breathing open / close
      loop({ wave: 0.52, duration: 3.6 })
    } else if (status === 'processing') {
      to({
        wave: 0.75,
        speed: 1.8,
        riffle: 1.1,
        fan: 0.17,
        shuffle: 1,
        droop: 0,
        scatter: 0.08,
        move: 1.15,
        scale: 1,
        tint: 0,
      })
      loop({ fan: 0.21, duration: 1.5 })
    } else if (status === 'success') {
      tintColor.current.set(dark ? '#dcc37f' : '#c9a85c')
      gsap
        .timeline()
        .to(cur, {
          duration: 0.55,
          ease: 'power3.out',
          wave: 0.1,
          speed: 1,
          riffle: 0.06,
          fan: 0.018, // snaps into a squared stack
          shuffle: 0,
          droop: 0,
          scatter: 0,
          move: 0.5,
          tint: 1,
        })
        .to(cur, { duration: 0.18, scale: 1.05, ease: 'power2.out' })
        .to(cur, { duration: 1.2, scale: 1, ease: 'elastic.out(1, 0.4)' })
    } else if (status === 'failure') {
      tintColor.current.set('#9a6b3f')
      gsap
        .timeline()
        .to(cur, {
          duration: 0.34,
          ease: 'power2.in',
          wave: 0.3,
          speed: 2,
          riffle: 0.4,
          fan: 0.26,
          shuffle: 0.3,
          droop: 1,
          scatter: 1,
          move: 0.4,
          tint: 1,
        })
        .to(cur, { duration: 1.4, ease: 'elastic.out(1, 0.5)', scatter: 0.8 })
    }

    return () => {
      gsap.killTweensOf(cur)
    }
  }, [status, dark])

  useFrame((state, dt) => {
    const g = group.current
    if (!g) return
    const p = d.current
    const t = state.clock.elapsedTime
    const mid = (NOTES - 1) / 2

    for (let k = 0; k < notes.length; k++) {
      const mesh = meshes.current[k]
      const n = notes[k]
      if (!mesh) continue
      const off = k - mid // -3.5 .. 3.5

      // cloth ripple
      const pos = mesh.geometry.attributes.position
      const arr = pos.array as Float32Array
      const b = n.base
      const ph = n.seed
      for (let i = 0; i < pos.count; i++) {
        const ix = i * 3
        const x = b[ix]
        const y = b[ix + 1]
        const u = x / W
        let z =
          Math.sin(x * 1.6 + t * 1.3 * p.speed + ph) * 0.035 * p.wave +
          Math.sin(y * 2.1 - t * p.speed + ph) * 0.024 * p.wave +
          Math.sin((x + y) * 1.1 - t * 0.7 * p.speed + ph) * 0.02 * p.wave
        z += Math.sin(u * Math.PI) * 0.016 * p.wave
        z -= p.droop * (0.13 + 0.1 * (u + 0.5)) * (0.4 + 0.6 * Math.abs(u))
        arr[ix + 2] = z
      }
      pos.needsUpdate = true
      mesh.geometry.computeVertexNormals()

      // fan: each note is rotated about the bottom pivot by a growing angle
      const angle =
        off * p.fan +
        Math.sin(t * 0.8 + ph) * 0.012 * p.riffle +
        Math.sin(t * 2.6 + k * 0.85) * 0.05 * p.shuffle +
        p.scatter * n.rot * 4
      const ca = Math.cos(angle)
      const sa = Math.sin(angle)
      mesh.position.set(
        -RADIUS * sa + n.ox,
        PIVOT_Y + RADIUS * ca + n.oy + 0.12 - p.droop * 0.05 * Math.abs(off),
        off * 0.004 - Math.abs(off) * 0.006,
      )
      mesh.rotation.z = angle
      mesh.rotation.x = Math.sin(t * 0.5 + ph) * 0.02 * p.riffle + p.droop * 0.14
      mesh.rotation.y = Math.sin(t * 0.4 + ph) * 0.014 * p.riffle
    }

    // whole wad: slight, slow sway — no spin
    const tx = -0.12 + Math.sin(t * 0.4) * 0.05 * p.move
    const ty = 0.18 + Math.sin(t * 0.26) * 0.07 * p.move
    const tz = Math.sin(t * 0.5) * 0.02 * p.move
    const kf = Math.min(1, dt * 3)
    g.rotation.x += (tx - g.rotation.x) * kf
    g.rotation.y += (ty - g.rotation.y) * kf
    g.rotation.z += (tz - g.rotation.z) * kf
    g.position.y = Math.sin(t * 0.7) * 0.02 * p.move
    g.scale.setScalar(p.scale)

    material.color.lerpColors(
      BASE,
      tintColor.current,
      THREE.MathUtils.clamp(p.tint, 0, 1),
    )
  })

  return (
    <group ref={group}>
      {notes.map((n, k) => (
        <mesh
          key={k}
          ref={(el) => {
            meshes.current[k] = el
          }}
          geometry={n.geo}
          material={material}
        />
      ))}
    </group>
  )
}

/** A spotlight that slowly sweeps a small circle — the "party" focus light. */
function SweepLight({
  dark,
  color,
  phase,
  radius,
}: {
  dark: boolean
  color: string
  phase: number
  radius: number
}) {
  const ref = useRef<THREE.SpotLight>(null)
  useFrame((s) => {
    const t = s.clock.elapsedTime * 0.16 + phase
    ref.current?.position.set(
      Math.sin(t) * radius,
      2.6 + Math.cos(t * 0.8) * 0.25,
      3.4 + Math.cos(t) * 0.3,
    )
  })
  return (
    <spotLight
      ref={ref}
      angle={0.36}
      penumbra={0.8}
      distance={18}
      decay={1.3}
      intensity={dark ? 11 : 4}
      color={color}
    />
  )
}

/** Additive radial disc — a visible circular pool of light behind the fan. */
function GlowDisc({
  core,
  size,
  z,
  speed,
  opacity,
}: {
  core: string
  size: number
  z: number
  speed: number
  opacity: number
}) {
  const ref = useRef<THREE.Mesh>(null)
  const tex = useMemo(() => glowTexture(core), [core])
  useFrame((s) => {
    const t = s.clock.elapsedTime * speed
    const m = ref.current
    if (!m) return
    m.position.set(Math.sin(t) * 0.3, 0.12 + Math.cos(t * 0.8) * 0.15, z)
    const p = size * (1 + Math.sin(s.clock.elapsedTime * 0.8) * 0.02)
    m.scale.set(p, p, 1)
  })
  return (
    <mesh ref={ref}>
      <planeGeometry args={[1, 1]} />
      <meshBasicMaterial
        map={tex}
        transparent
        opacity={opacity}
        depthWrite={false}
        blending={THREE.AdditiveBlending}
      />
    </mesh>
  )
}

export function BanknoteScene({ status }: { status: LoaderStatus }) {
  const pref = useAppSelector((s) => s.ui.theme)
  const sys = useSystemTheme()
  const dark = (pref === 'system' ? sys : pref) === 'dark'

  return (
    <Canvas
      camera={{ position: [0, 0.25, 4.6], fov: 34 }}
      dpr={[1, 1.75]}
      frameloop="always"
      gl={{ antialias: true, alpha: true }}
      style={{ background: 'transparent' }}
    >
      <ambientLight intensity={dark ? 0.18 : 0.55} />

      {/* focus lights — two slow, wide cones, green + gold */}
      <SweepLight
        dark={dark}
        color={dark ? '#eef8f1' : '#ffffff'}
        phase={0}
        radius={1.6}
      />
      <SweepLight
        dark={dark}
        color={dark ? '#8bcf98' : '#c4dcae'}
        phase={Math.PI}
        radius={1.1}
      />

      <directionalLight
        position={[-3, -1, 1.6]}
        intensity={dark ? 0.14 : 0.34}
        color={dark ? '#bfe8c6' : '#cdb37a'}
      />

      {/* soft glow blooms behind the fan — no hard edges */}
      <GlowDisc
        core={dark ? 'rgba(70,180,105,0.55)' : 'rgba(120,195,120,0.32)'}
        size={3}
        z={-0.6}
        speed={0.12}
        opacity={dark ? 0.5 : 0.26}
      />
      <GlowDisc
        core={dark ? 'rgba(220,190,120,0.45)' : 'rgba(210,180,110,0.26)'}
        size={2}
        z={-0.5}
        speed={-0.18}
        opacity={dark ? 0.4 : 0.2}
      />

      <Sparkles
        count={22}
        scale={[5.5, 3.2, 2]}
        size={2}
        speed={0.18}
        opacity={dark ? 0.5 : 0.25}
        color={dark ? '#9be6a8' : '#4fae63'}
      />

      <Bundle status={status} dark={dark} />

      <ContactShadows
        position={[0, -1.15, 0]}
        scale={7}
        blur={2.6}
        far={3}
        opacity={dark ? 0.5 : 0.3}
        color={dark ? '#000000' : '#123f20'}
      />
    </Canvas>
  )
}
