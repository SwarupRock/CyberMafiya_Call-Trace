import { motion } from 'framer-motion'

const actions = ['Mute', 'Trace', 'Deploy Bait', 'Block', 'Report']

export default function BottomActionBar() {
  return (
    <nav className="fixed inset-x-0 bottom-0 z-30 border-t border-electric/25 bg-black/90 px-3 py-3 backdrop-blur-xl">
      <div className="mx-auto grid max-w-3xl grid-cols-5 gap-2">
        {actions.map((action) => {
          const danger = action === 'Block' || action === 'Report'
          return (
            <motion.button
              key={action}
              type="button"
              whileTap={{ scale: 0.95 }}
              whileHover={{ y: -2 }}
              className={`h-14 rounded border bg-obsidian text-[10px] font-bold uppercase tracking-[0.12em] transition ${
                danger
                  ? 'border-threat/55 text-threat shadow-[0_0_18px_rgba(255,0,0,0.16)]'
                  : 'border-electric/35 text-electric shadow-cyan'
              }`}
            >
              {action}
            </motion.button>
          )
        })}
      </div>
    </nav>
  )
}
