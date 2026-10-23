package com.suarvis.butler.core.domain.turn

/** Polled between steps of a turn rather than interrupting threads, so core stays free of threading and the transcript is always left valid. */
fun interface TurnCancellation {
    fun isCancelled(): Boolean

    companion object {
        /** For runs nothing can stop part-way, such as a single scripted prompt. */
        val NEVER = TurnCancellation { false }
    }
}
