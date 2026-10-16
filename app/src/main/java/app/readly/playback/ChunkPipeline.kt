package app.readly.playback

import app.readly.text.Chunk
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Audio output for [ChunkPipeline]. Implemented by the `AudioTrack` wrapper in the service
 * and by fakes in tests.
 */
interface AudioSink {
    /** Writes PCM samples, returning early (not throwing) once [isStopped] is true. */
    fun write(samples: FloatArray, isStopped: () -> Boolean)

    /** Discards buffered audio (called by [ChunkPipeline.stop]). Must not throw. */
    fun flush()

    /** Blocks until buffered audio has finished playing (called after the last chunk). */
    fun drain()
}

/**
 * Synthesizes [chunks] one chunk ahead of playback and streams them to [sink].
 *
 * Pure Kotlin and JVM-testable. A producer thread synthesizes up to 2 chunks ahead of
 * playback into an [ArrayBlockingQueue]; a consumer thread writes each chunk to the sink in
 * ~100 ms slices so pause and stop react quickly. [startIndex] lets playback resume
 * mid-document (previous/next sentence).
 *
 * Threading contract:
 * - [pause]/[resume] block and unblock the consumer only; the producer keeps filling.
 * - [stop] sets the generation flag that [isStopped] reads, interrupts both threads,
 *   flushes the sink and joins both threads. It never joins the calling thread, so it is
 *   safe to call from inside a callback. After [stop] returns, no further callback runs.
 * - An exception in [synth] or [sink] reports [onError] once and stops the pipeline.
 * - [onUnderrun] fires when the consumer waited for the producer mid-playback (the
 *   engine can't keep up); the initial buffering before the first chunk doesn't count.
 */
class ChunkPipeline(
    private val chunks: List<Chunk>,
    private val startIndex: Int,
    private val sampleRate: Int,
    private val synth: (index: Int, isStopped: () -> Boolean) -> FloatArray,
    private val sink: AudioSink,
    private val onChunkStarted: (Int) -> Unit,
    private val onFinished: () -> Unit,
    private val onError: (Throwable) -> Unit,
    private val onUnderrun: () -> Unit = {},
) {
    private data class Ready(val index: Int, val samples: FloatArray)

    private object Finish

    /** ~100 ms of audio per sink write. */
    private val sliceSamples = (sampleRate / 10).coerceAtLeast(1)

    private val queue = ArrayBlockingQueue<Any>(2)

    /**
     * Slots for synthesized-but-not-yet-playing chunks. The producer acquires before each
     * synthesis and the consumer releases on take, so at most 2 chunks are ever
     * synthesized ahead of playback (a bare bounded queue would allow a third: the
     * producer synthesizes, then blocks on put).
     */
    private val aheadSlots = Semaphore(2)

    private val stopped = AtomicBoolean(false)
    private val errorReported = AtomicBoolean(false)
    private val pauseLock = Object()

    @Volatile
    private var paused = false

    @Volatile
    private var producer: Thread? = null

    @Volatile
    private var consumer: Thread? = null

    /** Generation flag: true once this pipeline was stopped. Passed to [synth]/[sink]. */
    val isStopped: () -> Boolean = { stopped.get() }

    fun start() {
        val producerThread = Thread(::runProducer, "readly-producer")
        val consumerThread = Thread(::runConsumer, "readly-consumer")
        producer = producerThread
        consumer = consumerThread
        producerThread.start()
        consumerThread.start()
    }

    /** Blocks the consumer (pauses output); the producer keeps filling the queue. */
    fun pause() {
        synchronized(pauseLock) { paused = true }
    }

    /** Unblocks the consumer after [pause]. */
    fun resume() {
        synchronized(pauseLock) {
            paused = false
            pauseLock.notifyAll()
        }
    }

    /**
     * Stops the pipeline and joins both worker threads. Never joins the calling thread,
     * so calling it from inside a callback cannot deadlock. Never throws.
     */
    fun stop() {
        requestStop()
        val self = Thread.currentThread()
        try {
            producer?.let { if (it !== self) it.join() }
            consumer?.let { if (it !== self) it.join() }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun requestStop() {
        stopped.set(true)
        synchronized(pauseLock) { pauseLock.notifyAll() }
        producer?.interrupt()
        consumer?.interrupt()
        try {
            sink.flush()
        } catch (_: Exception) {
            // Terminal state: stop() must still join the threads.
        }
    }

    /** Reports [onError] once, unless the pipeline was already stopped. */
    private fun fail(e: Throwable) {
        if (stopped.get()) return
        if (!errorReported.compareAndSet(false, true)) return
        requestStop()
        try {
            onError(e)
        } catch (_: Exception) {
            // A throwing callback must not take the worker thread down silently... it
            // already did its damage; there is nowhere else to report it.
        }
    }

    private fun runProducer() {
        try {
            var index = startIndex.coerceIn(0, chunks.size)
            while (index < chunks.size && !stopped.get()) {
                try {
                    aheadSlots.acquire()
                } catch (_: InterruptedException) {
                    return
                }
                if (stopped.get()) {
                    aheadSlots.release()
                    return
                }
                val samples = try {
                    synth(index, isStopped)
                } catch (e: Throwable) {
                    aheadSlots.release()
                    if (e is InterruptedException && stopped.get()) {
                        Thread.currentThread().interrupt()
                        return
                    }
                    fail(e)
                    return
                }
                if (stopped.get()) {
                    aheadSlots.release()
                    return
                }
                try {
                    queue.put(Ready(index, samples))
                } catch (_: InterruptedException) {
                    aheadSlots.release()
                    return
                }
                index++
            }
            if (!stopped.get()) {
                try {
                    queue.put(Finish)
                } catch (_: InterruptedException) {
                    // Stopped while finishing; the consumer is leaving anyway.
                }
            }
        } catch (e: Throwable) {
            // Unexpected death must still stop the pipeline and report once.
            fail(e)
        }
    }

    private fun runConsumer() {
        try {
            var firstTaken = false
            while (true) {
                if (stopped.get()) return
                val item = try {
                    queue.poll(UNDERRUN_POLL_MS, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    return
                }
                if (item == null) {
                    // Queue empty while active: the producer can't keep up. The initial
                    // fill before the first chunk is expected buffering, not an underrun.
                    if (firstTaken && !paused && !stopped.get()) {
                        try {
                            onUnderrun()
                        } catch (_: Exception) {
                            // Best-effort signal; never break playback.
                        }
                    }
                    continue
                }
                if (item === Finish) break
                item as Ready
                aheadSlots.release()
                if (stopped.get()) return
                if (!waitIfPaused()) return
                try {
                    onChunkStarted(item.index)
                } catch (e: Throwable) {
                    fail(e)
                    return
                }
                if (!writeSlices(item.samples)) return
                firstTaken = true
            }
            if (stopped.get()) return
            // A pause landing on the last slice must hold the finish too: draining a
            // paused (frozen) track would spin until resume.
            if (!waitIfPaused()) return
            try {
                sink.drain()
            } catch (e: Throwable) {
                fail(e)
                return
            }
            if (stopped.get()) return
            try {
                onFinished()
            } catch (_: Throwable) {
                // Terminal callback; nothing left to report to.
            }
        } catch (e: Throwable) {
            // Unexpected death must still stop the pipeline and report once.
            fail(e)
        }
    }

    /** Writes one chunk in ~100 ms slices. Returns false when stopped. */
    private fun writeSlices(samples: FloatArray): Boolean {
        var offset = 0
        while (offset < samples.size) {
            if (stopped.get()) return false
            if (!waitIfPaused()) return false
            val end = (offset + sliceSamples).coerceAtMost(samples.size)
            try {
                sink.write(samples.copyOfRange(offset, end), isStopped)
            } catch (e: Throwable) {
                fail(e)
                return false
            }
            offset = end
        }
        return !stopped.get()
    }

    /** Parks while paused. Returns false when stopped. */
    private fun waitIfPaused(): Boolean {
        synchronized(pauseLock) {
            while (paused && !stopped.get()) {
                try {
                    pauseLock.wait()
                } catch (_: InterruptedException) {
                    return false
                }
            }
            return !stopped.get()
        }
    }

    companion object {
        private const val UNDERRUN_POLL_MS = 200L
    }
}
