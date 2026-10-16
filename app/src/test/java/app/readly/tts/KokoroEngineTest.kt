package app.readly.tts

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KokoroEngineTest {

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
}
