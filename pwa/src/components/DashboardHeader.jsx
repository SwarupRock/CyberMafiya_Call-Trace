import { motion } from 'framer-motion'

const metrics = [
  { label: 'Threat Index', value: '87%', tone: 'text-threat' },
  { label: 'Calls Screened', value: '1,284', tone: 'text-electric' },
  { label: 'Active Bait', value: '03', tone: 'text-amber-300' },
]

function ShieldGauge() {
  return (
    <div className="relative grid h-24 w-24 shrink-0 place-items-center rounded-full border border-electric/35 bg-black/50 shadow-cyan">
      <div className="absolute inset-2 rounded-full border border-threat/25" />
      <motion.div
        animate={{ rotate: 360 }}
        transition={{ duration: 9, repeat: Infinity, ease: 'linear' }}
        className="absolute inset-0 rounded-full border-t-2 border-t-electric"
      />
      <div className="text-center">
        <p className="text-xl font-bold leading-none">AI</p>
        <p className="mt-1 text-[10px] text-electric/55">ARMED</p>
      </div>
    </div>
  )
}

export default function DashboardHeader() {
  return (
    <header className="mb-4 panel px-4 py-4 sm:px-5">
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex items-center gap-4">
          <ShieldGauge />
          <div>
            <p className="text-xs uppercase tracking-[0.32em] text-electric/55">Cybersecurity Command Center</p>
            <h1 className="mt-1 text-2xl font-bold uppercase tracking-[0.18em] text-white sm:text-4xl">
              Scam Shield AI
            </h1>
            <div className="mt-3 flex flex-wrap items-center gap-2 text-xs uppercase tracking-[0.18em]">
              <span className="status-pill border-electric/45 text-electric">Call Intercept Online</span>
              <span className="status-pill border-threat/45 text-threat">Scam Pattern Lock</span>
            </div>
          </div>
        </div>

        <div className="grid grid-cols-3 gap-2 sm:min-w-[360px]">
          {metrics.map((metric) => (
            <div key={metric.label} className="metric-tile">
              <p className={`text-xl font-bold ${metric.tone}`}>{metric.value}</p>
              <p className="mt-1 text-[10px] uppercase tracking-[0.16em] text-white/45">{metric.label}</p>
            </div>
          ))}
        </div>
      </div>
    </header>
  )
}
