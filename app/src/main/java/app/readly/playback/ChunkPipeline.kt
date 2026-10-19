package app.readly.playback

import app.readly.text.Chunk
import java.util.concurrent.LinkedBlockingQueue
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
 * Synthesizes [chunks] ahead of playback and streams them to [sink].
 *
 * Pure Kotlin and JVM-testable. A producer thread synthesizes into an unbounded queue
 * while the unplayed audio already synthesized stays under a duration budget; a consumer
 * thread writes each chunk to the sink in ~100 ms slices so pause and stop react quickly.
 * [startIndex] lets playback resume mid-document (previous/next sentence).
 *
 * Each chunk's audio arrives in one or more pieces: [synth] emits partial audio through
 * its `emit` callback as it finishes (Pocket streams per piece; sherpa engines emit
 * once at the end), and playback of a chunk starts with its first piece.
 *
 * Threading contract:
 * - [pause]/[resume] block and unblock the consumer only; the producer keeps filling
 *   until the budget is full, then waits.
 * - [stop] sets the generation flag that [isStopped] reads, interrupts both threads,
 *   flushes the sink and joins both threads. It never joins the calling thread, so it is
 *   safe to call from inside a callback. After [stop] returns, no further callback runs.
 * - An exception in [synth] or [sink] reports [onError] once and stops the pipeline.
 * - [onUnderrun] fires with the awaited chunk index when the consumer waited for the
 *   producer mid-playback (the engine can't keep up), at most once per chunk gap; the
 *   initial buffering before the first chunk doesn't count.
 */
class ChunkPipeline(
    private val chunks: List<Chunk>,
    private val startIndex: Int,
    private val sampleRate: Int,
    private val synth: (index: Int, isStopped: () -> Boolean, emit: (FloatArray) -> Unit) -> Unit,
    private val sink: AudioSink,
    private val onChunkStarted: (Int) -> Unit,
    private val onFinished: () -> Unit,
    private val onError: (Throwable) -> Unit,
    private val onUnderrun: (Int) -> Unit = {},
) {
    /** One partial piece of a chunk's audio, played in arrival order. */
    private data class Piece(val index: Int, val samples: FloatArray)

    /** End of a chunk's audio (emitted even when no piece arrived). */
    private data class ChunkDone(val index: Int)

    private object Finish

    /** ~100 ms of audio per sink write. */
    private val sliceSamples = (sampleRate / 10).coerceAtLeast(1)

    private val queue = LinkedBlockingQueue<Any>()

    /**
     * Duration budget for synthesized-but-not-yet-played audio (queued chunks plus the
     * rest of the chunk being written), counted in samples. The producer synthesizes
     * while [budgetedSamples] is under 20 s of audio and fewer than 12 chunks are
     * ahead; the consumer gives budget back slice by slice as it writes.
     * Memory: 20 s of 24 kHz float mono is about 1.9 MB (20 * 24000 * 4), plus at most
     * one overshooting chunk, which is fine.
     */
    private val budgetLock = Object()
    private var budgetedSamples = 0L
    private var budgetedChunks = 0
    private val budgetSamples = BUDGET_SECONDS * sampleRate.toLong()

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
        synchronized(budgetLock) { budgetLock.notifyAll() }
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
                synchronized(budgetLock) {
                    while (!stopped.get() &&
                        (budgetedSamples >= budgetSamples || budgetedChunks >= MAX_AHEAD_CHUNKS)
                    ) {
                        try {
                            budgetLock.wait()
                        } catch (_: InterruptedException) {
                            return
                        }
                    }
                    if (stopped.get()) return
                }
                try {
                    synth(index, isStopped) { piece -> emitPiece(index, piece) }
                } catch (e: Throwable) {
                    if (e is InterruptedException && stopped.get()) {
                        Thread.currentThread().interrupt()
                        return
                    }
                    fail(e)
                    return
                }
                if (stopped.get()) return
                synchronized(budgetLock) {
                    if (stopped.get()) return
                    budgetedChunks += 1
                }
                try {
                    queue.put(ChunkDone(index))
                } catch (_: InterruptedException) {
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

    /**
     * Queues one partial piece of chunk [index], accounting its samples against the
     * duration budget (the 20 s cap still holds plus at most one overshooting chunk,
     * as before). Pieces arriving after [stop] are dropped: a stopped synth can still
     * emit from another thread (Pocket's `onChunk` runs on `readly-tts`).
     */
    private fun emitPiece(index: Int, samples: FloatArray) {
        if (stopped.get()) return
        synchronized(budgetLock) {
            if (stopped.get()) return
            budgetedSamples += samples.size
        }
        try {
            queue.put(Piece(index, samples))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun runConsumer() {
        try {
            var firstTaken = false
            var nextIndex = startIndex.coerceIn(0, chunks.size)
            var startedIndex: Int? = null
            var underrunFired = false
            while (true) {
                if (stopped.get()) return
                val item = try {
                    queue.poll(UNDERRUN_POLL_MS, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    return
                }
                if (item == null) {
                    // Queue empty while active: the producer can't keep up. The initial
                    // fill before the first piece is expected buffering, not an underrun.
                    // Mid-chunk, the awaited chunk is the one still streaming pieces.
                    if (firstTaken && !underrunFired && !paused && !stopped.get()) {
                        underrunFired = true
                        try {
                            onUnderrun(startedIndex ?: nextIndex)
                        } catch (_: Exception) {
                            // Best-effort signal; never break playback.
                        }
                    }
                    continue
                }
                if (item === Finish) break
                firstTaken = true
                underrunFired = false
                if (stopped.get()) return
                if (!waitIfPaused()) return
                when (item) {
                    is Piece -> {
                        if (startedIndex != item.index) {
                            startedIndex = item.index
                            try {
                                onChunkStarted(item.index)
                            } catch (e: Throwable) {
                                fail(e)
                                return
                            }
                        }
                        if (!writeSlices(item.samples)) return
                    }
                    is ChunkDone -> {
                        if (startedIndex != item.index) {
                            // No piece arrived (empty audio): the chunk still starts.
                            startedIndex = item.index
                            try {
                                onChunkStarted(item.index)
                            } catch (e: Throwable) {
                                fail(e)
                                return
                            }
                        }
                        startedIndex = null
                        nextIndex = item.index + 1
                        synchronized(budgetLock) {
                            budgetedChunks = (budgetedChunks - 1).coerceAtLeast(0)
                            budgetLock.notifyAll()
                        }
                    }
                    else -> throw IllegalStateException("unexpected queue item $item")
                }
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

    /**
     * Writes one piece in ~100 ms slices, giving duration budget back slice by slice.
     * The chunk count goes back when the chunk's [ChunkDone] arrives. Returns false
     * when stopped.
     */
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
            synchronized(budgetLock) {
                budgetedSamples = (budgetedSamples - (end - offset)).coerceAtLeast(0)
                budgetLock.notifyAll()
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
        private const val BUDGET_SECONDS = 20L
        private const val MAX_AHEAD_CHUNKS = 12
    }
}
