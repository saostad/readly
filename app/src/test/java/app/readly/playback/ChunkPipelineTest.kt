package app.readly.playback

import app.readly.text.Chunk
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkPipelineTest {

    private companion object {
        const val SAMPLE_RATE = 1000 // 100-sample slices (~100 ms)
        const val TIMEOUT_S = 10L
    }

    private fun chunks(n: Int): List<Chunk> =
        List(n) { i -> Chunk("sentence $i", i, i + 1) }

    private fun samples(n: Int): FloatArray = FloatArray(n) { 0.1f }

    /** Sink that records writes and can block the first write until released. */
    private class FakeSink : AudioSink {
        val writes = Collections.synchronizedList(mutableListOf<Int>())
        val releaseFirstWrite = CountDownLatch(1)

        @Volatile
        var blockFirstWrite = false

        @Volatile
        var flushed = false

        @Volatile
        var drained = false

        override fun write(samples: FloatArray, isStopped: () -> Boolean) {
            if (blockFirstWrite) {
                blockFirstWrite = false
                assertTrue(releaseFirstWrite.await(TIMEOUT_S, TimeUnit.SECONDS))
            }
            writes.add(samples.size)
        }

        override fun flush() {
            flushed = true
        }

        override fun drain() {
            drained = true
        }

        fun totalSamples(): Int = writes.sum()
    }

    private fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_S * 1000
        while (!condition()) {
            assertTrue(message, System.currentTimeMillis() < deadline)
            Thread.sleep(10)
        }
    }

    @Test
    fun pipeline_reportsChunksInOrderThenFinishes() {
        val sink = FakeSink()
        val started = Collections.synchronizedList(mutableListOf<Int>())
        val finished = CountDownLatch(1)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val pipeline = ChunkPipeline(
            chunks = chunks(5),
            startIndex = 0,
            sampleRate = SAMPLE_RATE,
            synth = { _, _ -> samples(250) },
            sink = sink,
            onChunkStarted = { started.add(it) },
            onFinished = { finished.countDown() },
            onError = { errors.add(it) },
        )
        try {
            pipeline.start()
            assertTrue(finished.await(TIMEOUT_S, TimeUnit.SECONDS))
            assertEquals(listOf(0, 1, 2, 3, 4), started)
            assertTrue(errors.isEmpty())
            assertTrue(sink.drained)
            assertEquals(5 * 250, sink.totalSamples())
            // 250 samples at 100-sample slices = 3 writes per chunk.
            assertEquals(5 * 3, sink.writes.size)
        } finally {
            pipeline.stop()
        }
    }

    @Test
    fun pipeline_synthesizesWithin20sDurationBudget() {
        val sink = FakeSink().apply { blockFirstWrite = true }
        val synthCalls = Collections.synchronizedList(mutableListOf<Int>())
        val started = Collections.synchronizedList(mutableListOf<Int>())
        val finished = CountDownLatch(1)
        val pipeline = ChunkPipeline(
            chunks = chunks(6),
            startIndex = 0,
            sampleRate = SAMPLE_RATE,
            synth = { index, _ ->
                synthCalls.add(index)
                samples(5 * SAMPLE_RATE)
            },
            sink = sink,
            onChunkStarted = { started.add(it) },
            onFinished = { finished.countDown() },
            onError = { throw AssertionError("unexpected error", it) },
        )
        try {
            pipeline.start()
            // The consumer is stuck writing chunk 0 (5 s); the 20 s budget holds
            // 4 chunks total (20 s / 5 s), then the producer must stall.
            waitFor("producer stalls at the 20 s budget") { synthCalls.size == 4 }
            Thread.sleep(300)
            assertEquals(listOf(0, 1, 2, 3), synthCalls)
            assertEquals(listOf(0), started)
            sink.releaseFirstWrite.countDown()
            assertTrue(finished.await(TIMEOUT_S, TimeUnit.SECONDS))
            assertEquals(listOf(0, 1, 2, 3, 4, 5), started)
            assertEquals(listOf(0, 1, 2, 3, 4, 5), synthCalls)
        } finally {
            sink.releaseFirstWrite.countDown()
            pipeline.stop()
        }
    }

    @Test
    fun pipeline_shortChunks_hit12ChunkCapBeforeDurationBudget() {
        val sink = FakeSink().apply { blockFirstWrite = true }
        val synthCalls = Collections.synchronizedList(mutableListOf<Int>())
        val started = Collections.synchronizedList(mutableListOf<Int>())
        val finished = CountDownLatch(1)
        val pipeline = ChunkPipeline(
            chunks = chunks(16),
            startIndex = 0,
            sampleRate = SAMPLE_RATE,
            synth = { index, _ ->
                synthCalls.add(index)
                samples(SAMPLE_RATE)
            },
            sink = sink,
            onChunkStarted = { started.add(it) },
            onFinished = { finished.countDown() },
            onError = { throw AssertionError("unexpected error", it) },
        )
        try {
            pipeline.start()
            // 1 s chunks: 12 s is under the 20 s budget, so the 12-chunk cap
            // stalls the producer at 12 chunks total.
            waitFor("producer stalls at the 12-chunk cap") { synthCalls.size == 12 }
            Thread.sleep(300)
            assertEquals((0..11).toList(), synthCalls)
            assertEquals(listOf(0), started)
            sink.releaseFirstWrite.countDown()
            assertTrue(finished.await(TIMEOUT_S, TimeUnit.SECONDS))
            assertEquals((0..15).toList(), started)
            assertEquals((0..15).toList(), synthCalls)
        } finally {
            sink.releaseFirstWrite.countDown()
            pipeline.stop()
        }
    }

    @Test
    fun pipeline_stopDuringSynthesis_returnsQuicklyWithNoCallbacks() {
        val sink = FakeSink()
        val enteredSynth = CountDownLatch(1)
        val started = Collections.synchronizedList(mutableListOf<Int>())
        val finished = AtomicBooleanLatch()
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val pipeline = ChunkPipeline(
            chunks = chunks(4),
            startIndex = 0,
            sampleRate = SAMPLE_RATE,
            synth = { _, isStopped ->
                enteredSynth.countDown()
                while (!isStopped()) Thread.sleep(10)
                FloatArray(0)
            },
            sink = sink,
            onChunkStarted = { started.add(it) },
            onFinished = { finished.countDown() },
            onError = { errors.add(it) },
        )
        try {
            pipeline.start()
            assertTrue(enteredSynth.await(TIMEOUT_S, TimeUnit.SECONDS))
            val begin = System.currentTimeMillis()
            pipeline.stop()
            assertTrue(
                "stop took ${System.currentTimeMillis() - begin} ms",
                System.currentTimeMillis() - begin < 5000,
            )
            Thread.sleep(200)
            assertTrue(started.isEmpty())
            assertTrue(errors.isEmpty())
            assertTrue(!finished.isDone)
            assertTrue(sink.flushed)
        } finally {
            pipeline.stop()
        }
    }

    @Test
    fun pipeline_synthException_reachesOnErrorOnce() {
        val sink = FakeSink()
        val started = Collections.synchronizedList(mutableListOf<Int>())
        val finished = AtomicBooleanLatch()
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val errorArrived = CountDownLatch(1)
        val pipeline = ChunkPipeline(
            chunks = chunks(4),
            startIndex = 0,
            sampleRate = SAMPLE_RATE,
            synth = { index, _ ->
                if (index == 1) {
                    // Let chunk 0 finish playing first so the state is deterministic.
                    Thread.sleep(300)
                    throw RuntimeException("boom-1")
                }
                samples(100)
            },
            sink = sink,
            onChunkStarted = { started.add(it) },
            onFinished = { finished.countDown() },
            onError = {
                errors.add(it)
                errorArrived.countDown()
            },
        )
        try {
            pipeline.start()
            assertTrue(errorArrived.await(TIMEOUT_S, TimeUnit.SECONDS))
            pipeline.stop()
            Thread.sleep(200)
            assertEquals(listOf(0), started)
            assertEquals(1, errors.size)
            assertEquals("boom-1", errors[0].message)
            assertTrue(!finished.isDone)
        } finally {
            pipeline.stop()
        }
    }

    @Test
    fun pipeline_pauseHoldsOutputAndResumeContinues() {
        val sink = FakeSink()
        val finished = CountDownLatch(1)
        val pipeline = ChunkPipeline(
            chunks = chunks(3),
            startIndex = 0,
            sampleRate = SAMPLE_RATE,
            synth = { _, _ -> samples(400) },
            sink = sink,
            onChunkStarted = {},
            onFinished = { finished.countDown() },
            onError = { throw AssertionError("unexpected error", it) },
        )
        try {
            pipeline.start()
            waitFor("first slice written") { sink.writes.isNotEmpty() }
            pipeline.pause()
            Thread.sleep(100) // let an in-flight slice land
            val held = sink.writes.size
            Thread.sleep(300)
            assertEquals(held, sink.writes.size)
            pipeline.resume()
            assertTrue(finished.await(TIMEOUT_S, TimeUnit.SECONDS))
            // 400 samples at 100-sample slices = 4 writes per chunk.
            assertEquals(3 * 4, sink.writes.size)
            assertEquals(3 * 400, sink.totalSamples())
        } finally {
            pipeline.stop()
        }
    }

    @Test
    fun pipeline_slowProducer_reportsUnderrunThenFinishes() {
        val sink = FakeSink()
        val underruns = Collections.synchronizedList(mutableListOf<Int>())
        val underrunArrived = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val pipeline = ChunkPipeline(
            chunks = chunks(3),
            startIndex = 0,
            sampleRate = SAMPLE_RATE,
            synth = { _, _ ->
                Thread.sleep(500)
                samples(50)
            },
            sink = sink,
            onChunkStarted = {},
            onFinished = { finished.countDown() },
            onError = { throw AssertionError("unexpected error", it) },
            onUnderrun = { index ->
                underruns.add(index)
                underrunArrived.countDown()
            },
        )
        try {
            pipeline.start()
            assertTrue(underrunArrived.await(TIMEOUT_S, TimeUnit.SECONDS))
            assertTrue(finished.await(TIMEOUT_S, TimeUnit.SECONDS))
            assertTrue(underruns.isNotEmpty())
        } finally {
            pipeline.stop()
        }
    }

    @Test
    fun pipeline_underrun_reportsAwaitedIndexOncePerGap() {
        val sink = FakeSink()
        val underruns = Collections.synchronizedList(mutableListOf<Int>())
        val finished = CountDownLatch(1)
        val pipeline = ChunkPipeline(
            chunks = chunks(3),
            startIndex = 0,
            sampleRate = SAMPLE_RATE,
            synth = { _, _ ->
                // 700 ms per chunk vs the 200 ms consumer poll: several empty
                // polls per gap, but only one report each, naming the chunk
                // the consumer is waiting for.
                Thread.sleep(700)
                samples(50)
            },
            sink = sink,
            onChunkStarted = {},
            onFinished = { finished.countDown() },
            onError = { throw AssertionError("unexpected error", it) },
            onUnderrun = { underruns.add(it) },
        )
        try {
            pipeline.start()
            assertTrue(finished.await(TIMEOUT_S, TimeUnit.SECONDS))
            // One gap before chunk 1 and one before chunk 2; the initial fill
            // before chunk 0 is buffering, not an underrun.
            assertEquals(listOf(1, 2), underruns)
        } finally {
            pipeline.stop()
        }
    }

    /** A CountDownLatch(1) that also reports whether it fired. */
    private class AtomicBooleanLatch {
        private val latch = CountDownLatch(1)

        @Volatile
        var isDone = false
            private set

        fun countDown() {
            isDone = true
            latch.countDown()
        }
    }
}
