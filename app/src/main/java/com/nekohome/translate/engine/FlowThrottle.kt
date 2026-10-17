package com.nekohome.translate.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Emits the first value immediately, then at most one value per [periodMillis], always the most
 * recent one; intermediate values are dropped. The last upstream value is always delivered
 * (after at most [periodMillis]), so nothing is lost at the end of a stream.
 *
 * Used for streaming translation output: the model can produce tokens faster than the screen
 * refreshes, and each UI update re-lays-out the output text, so updates are capped at ~30 fps.
 */
@OptIn(ExperimentalCoroutinesApi::class) // produce()
fun <T> Flow<T>.throttleLatest(periodMillis: Long): Flow<T> {
    require(periodMillis >= 0) { "periodMillis must be >= 0" }
    val upstream = this
    return flow {
        coroutineScope {
            val latest = produce(capacity = Channel.CONFLATED) { upstream.collect { send(it) } }
            for (value in latest) {
                emit(value)
                // While we wait, the conflated channel keeps only the newest value.
                if (periodMillis > 0) delay(periodMillis)
            }
        }
    }
}
