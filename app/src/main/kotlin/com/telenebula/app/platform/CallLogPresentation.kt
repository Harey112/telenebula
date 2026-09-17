package com.telenebula.app.platform

import com.telenebula.app.ui.icons.TnIcon
import com.telenebula.core.model.CallLog
import com.telenebula.core.model.CallOutcome
import com.telenebula.core.model.MessageDirection

/** One call log as a list row; [peerLabel] names the peer on cross-chat lists. */
class CallLogRowData(
    val id: String,
    val peerIp: String,
    val icon: TnIcon,
    val title: String,
    val subtitle: String,
    val isMissed: Boolean,
    val isVideo: Boolean,
)

object CallLogPresentation {
    private fun outcomeLabel(outcome: CallOutcome): String = when (outcome) {
        CallOutcome.ANSWERED -> ""
        CallOutcome.MISSED -> "Missed"
        CallOutcome.DECLINED -> "Declined"
        CallOutcome.NO_ANSWER -> "No answer"
        CallOutcome.UNREACHABLE -> "Unreachable"
        CallOutcome.CANCELLED -> "Cancelled"
        CallOutcome.FAILED -> "Failed"
    }

    fun toRow(log: CallLog, peerLabel: String? = null, now: Long = System.currentTimeMillis()): CallLogRowData {
        val isIncoming = log.direction == MessageDirection.IN
        val isMissed = isIncoming && (log.outcome == CallOutcome.MISSED || log.outcome == CallOutcome.DECLINED)
        val kind = if (log.isVideo) "video call" else "call"
        val way = if (isIncoming) "Incoming" else "Outgoing"
        val connectedAt = log.connectedAt
        val detail = if (log.outcome == CallOutcome.ANSWERED && connectedAt != null) Format.durationBetween(connectedAt, log.endedAt) else outcomeLabel(log.outcome)
        val time = Format.listTime(log.startedAt, now)
        val whenText = if (detail.isNotEmpty()) "$time · $detail" else time
        return CallLogRowData(
            id = log.id,
            peerIp = log.peerIp,
            icon = when {
                isMissed -> TnIcon.CALL_MISSED
                isIncoming -> TnIcon.CALL_IN
                else -> TnIcon.CALL_OUT
            },
            title = peerLabel ?: "$way $kind",
            subtitle = if (peerLabel != null) "$way $kind · $whenText" else whenText,
            isMissed = isMissed,
            isVideo = log.isVideo,
        )
    }
}
