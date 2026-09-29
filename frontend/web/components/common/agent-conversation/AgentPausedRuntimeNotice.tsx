'use client'

import { CaretRightOutlined, StopOutlined } from '@ant-design/icons'
import './agent-paused-runtime-notice.css'

/**
 * Soft-pause notice: shown when the user pauses SSE receiving while the Skill
 * run keeps going in the background (`paused === true` + runtimeFeedbackEnabled).
 * Resume reconnects the event stream from the saved afterSeq.
 */
export function AgentPausedRuntimeNotice({
  onResume,
  onStop,
  disabled = false
}: {
  onResume: () => void
  onStop?: () => void
  disabled?: boolean
}) {
  return (
    <section className="agent-paused-runtime-notice" role="status">
      <div className="agent-paused-runtime-notice__copy">
        <strong>已暂停接收</strong>
        <p>任务仍在后台处理，恢复后会继续展示生成内容</p>
      </div>
      <div className="agent-paused-runtime-notice__actions">
        {onStop ? <button type="button" className="agent-paused-runtime-notice__stop"
          disabled={disabled} onClick={onStop}>
          <StopOutlined aria-hidden /><span>停止生成</span>
        </button> : null}
        <button type="button" className="agent-paused-runtime-notice__resume"
          disabled={disabled} onClick={onResume}>
          <CaretRightOutlined aria-hidden /><span>恢复生成</span>
        </button>
      </div>
    </section>
  )
}

export default AgentPausedRuntimeNotice
