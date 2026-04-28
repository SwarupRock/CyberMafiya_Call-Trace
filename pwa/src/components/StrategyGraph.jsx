import { motion } from 'framer-motion'

const nodes = [
  { label: 'Identity Hook', risk: 78, x: '12%', y: '18%' },
  { label: 'Authority Claim', risk: 91, x: '56%', y: '14%' },
  { label: 'Urgency Spike', risk: 96, x: '34%', y: '48%' },
  { label: 'Credential Ask', risk: 99, x: '68%', y: '68%' },
]

export default function StrategyGraph() {
  return (
    <article className="panel p-4">
      <div className="mb-4 flex items-start justify-between">
        <div>
          <p className="eyebrow">Strategy Graph</p>
          <h2 className="mt-1 text-lg font-bold uppercase tracking-[0.18em] text-white">Attack Path</h2>
        </div>
        <span className="status-pill border-threat/45 text-threat">4 Nodes Hot</span>
      </div>

      <div className="relative h-64 overflow-hidden rounded border border-electric/20 bg-black/50">
        <svg className="absolute inset-0 h-full w-full" viewBox="0 0 100 100" preserveAspectRatio="none">
          <path d="M18 26 L63 22 L41 56 L75 76" stroke="rgba(0,255,255,0.5)" strokeWidth="0.8" fill="none" />
          <path d="M63 22 L75 76" stroke="rgba(255,0,0,0.42)" strokeWidth="0.8" strokeDasharray="2 2" />
        </svg>

        {nodes.map((node, index) => (
          <motion.div
            key={node.label}
            initial={{ opacity: 0, scale: 0.75 }}
            animate={{ opacity: 1, scale: 1 }}
            transition={{ delay: index * 0.12 }}
            className="absolute w-32 -translate-x-1/2 -translate-y-1/2 rounded border border-electric/35 bg-obsidian px-3 py-2 shadow-cyan"
            style={{ left: node.x, top: node.y }}
          >
            <p className="text-xs font-bold uppercase tracking-[0.12em] text-white">{node.label}</p>
            <div className="mt-2 h-1.5 overflow-hidden rounded bg-white/10">
              <div className="h-full bg-threat" style={{ width: `${node.risk}%` }} />
            </div>
            <p className="mt-1 text-[10px] text-threat">{node.risk}% risk</p>
          </motion.div>
        ))}
      </div>
    </article>
  )
}
