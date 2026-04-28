export default function RecentInterceptsLog({ intercepts }) {
  return (
    <article className="panel p-4">
      <div className="mb-4 flex items-start justify-between">
        <div>
          <p className="eyebrow">Recent Intercepts Log</p>
          <h2 className="mt-1 text-lg font-bold uppercase tracking-[0.18em] text-white">Threat Ledger</h2>
        </div>
        <span className="text-xs uppercase tracking-[0.18em] text-electric/55">Encrypted</span>
      </div>

      <div className="space-y-2">
        {intercepts.map((item) => (
          <div key={item.id} className="grid grid-cols-[1fr_auto] gap-3 rounded border border-electric/15 bg-black/45 p-3">
            <div>
              <p className="text-sm font-bold text-white">{item.number}</p>
              <p className="mt-1 text-xs uppercase tracking-[0.12em] text-electric/50">{item.vector}</p>
            </div>
            <div className="text-right">
              <p className={item.severity === 'critical' ? 'text-sm text-threat' : 'text-sm text-amber-300'}>
                {item.score}
              </p>
              <p className="mt-1 text-[10px] uppercase tracking-[0.14em] text-white/40">{item.time}</p>
            </div>
          </div>
        ))}
      </div>
    </article>
  )
}
