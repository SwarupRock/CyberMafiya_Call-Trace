import { motion } from 'framer-motion'
import BottomActionBar from './components/BottomActionBar'
import DashboardHeader from './components/DashboardHeader'
import LiveTranscriptVisualizer from './components/LiveTranscriptVisualizer'
import RecentInterceptsLog from './components/RecentInterceptsLog'
import StrategyGraph from './components/StrategyGraph'
import { intercepts, transcriptLines } from './data/dashboardData'

function App() {
  return (
    <main className="min-h-screen overflow-hidden bg-obsidian text-electric">
      <div className="fixed inset-0 cyber-grid" />
      <div className="fixed inset-0 scanlines" />

      <motion.div
        initial={{ opacity: 0, y: 18 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.55, ease: 'easeOut' }}
        className="relative z-10 mx-auto flex min-h-screen w-full max-w-7xl flex-col px-4 pb-28 pt-4 sm:px-6 lg:px-8"
      >
        <DashboardHeader />

        <section className="grid flex-1 grid-cols-1 gap-4 lg:grid-cols-[1.15fr_0.85fr]">
          <LiveTranscriptVisualizer lines={transcriptLines} />

          <div className="grid grid-cols-1 gap-4">
            <StrategyGraph />
            <RecentInterceptsLog intercepts={intercepts} />
          </div>
        </section>
      </motion.div>

      <BottomActionBar />
    </main>
  )
}

export default App
