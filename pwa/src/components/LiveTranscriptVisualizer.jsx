import { motion } from 'framer-motion'

const flaggedWords = /(\b(?:otp|kyc|urgent|freeze|verify|bank|wallet|arrest)\b)/gi

function highlight(line) {
  return line.split(flaggedWords).map((part, index) => {
    if (flaggedWords.test(part)) {
      flaggedWords.lastIndex = 0
      return (
        <span key={`${part}-${index}`} className="rounded border border-threat/35 bg-threat/10 px-1 text-threat">
          {part}
        </span>
      )
    }
    flaggedWords.lastIndex = 0
    return <span key={`${part}-${index}`}>{part}</span>
  })
}

export default function LiveTranscriptVisualizer({ lines }) {
  return (
    <article className="panel flex min-h-[560px] flex-col p-4">
      <div className="mb-4 flex items-center justify-between gap-3">
        <div>
          <p className="eyebrow">Live Transcript Visualizer</p>
          <h2 className="mt-1 text-lg font-bold uppercase tracking-[0.18em] text-white">Intercept Stream</h2>
        </div>
        <div className="audio-bars" aria-hidden="true">
          <span />
          <span />
          <span />
          <span />
          <span />
        </div>
      </div>

      <div className="relative flex-1 overflow-hidden rounded border border-electric/20 bg-black/55">
        <div className="absolute inset-x-0 top-0 z-10 h-10 bg-gradient-to-b from-black to-transparent" />
        <div className="h-full space-y-3 overflow-y-auto px-3 py-4 text-sm leading-7 text-electric/85 sm:px-4">
          {lines.map((entry, index) => (
            <motion.div
              key={entry.id}
              initial={{ opacity: 0, x: -12 }}
              animate={{ opacity: 1, x: 0 }}
              transition={{ delay: index * 0.08 }}
              className="grid grid-cols-[52px_1fr] gap-3 border-b border-electric/10 pb-3"
            >
              <span className="text-electric/40">{entry.time}</span>
              <p>
                <span className={entry.source === 'caller' ? 'text-threat' : 'text-amber-300'}>
                  {entry.source.toUpperCase()}:
                </span>{' '}
                {highlight(entry.text)}
              </p>
            </motion.div>
          ))}
        </div>
      </div>
    </article>
  )
}
