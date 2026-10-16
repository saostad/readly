package app.readly.tts

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechEngineTest {

    @Test
    fun stopCallback_hasTypedInvokeMethodForJni() {
        // sherpa-onnx's JNI calls GetMethodID(cls, "invoke", "([F)Ljava/lang/Integer;").
        // A lambda compiles to a synthetic class without that method and aborts the app,
        // so StopCallback must stay a named class with exactly this signature.
        val method = StopCallback::class.java.getMethod("invoke", FloatArray::class.java)
        assertTrue(Modifier.isPublic(method.modifiers))
        assertEquals(Integer::class.java, method.returnType)
    }

    @Test
    fun stopCallback_returnsZeroWhenStopped() {
        assertEquals(0, StopCallback { true }.invoke(floatArrayOf(0.1f, 0.2f)))
        assertEquals(1, StopCallback { false }.invoke(floatArrayOf(0.1f, 0.2f)))
    }

    @Test
    fun streamingCallback_hasTypedInvokeMethodForJni() {
        // Same JNI rule as StopCallback: the streaming callback must stay a named
        // class with invoke([F)Ljava/lang/Integer;.
        val method = StreamingCallback::class.java.getMethod("invoke", FloatArray::class.java)
        assertTrue(Modifier.isPublic(method.modifiers))
        assertEquals(Integer::class.java, method.returnType)
    }

    @Test
    fun streamingCallback_forwardsChunksAndStops() {
        val seen = mutableListOf<FloatArray>()
        val live = StreamingCallback({ false }, seen::add)
        assertEquals(1, live.invoke(floatArrayOf(0.1f)))
        val stopped = StreamingCallback({ true }, seen::add)
        assertEquals(0, stopped.invoke(floatArrayOf(0.2f)))
        assertEquals(2, seen.size)
        assertEquals(0.1f, seen[0][0], 0.0f)
        assertEquals(0.2f, seen[1][0], 0.0f)
    }
}
