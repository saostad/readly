package app.readly.tts.phonon

import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** [phononLibraryLoadError] only: loading the real library needs the device ABI. */
class PhononTtsTest {

    @Test
    fun libraryLoadError_namesLibraryAndKeepsCause() {
        val cause = UnsatisfiedLinkError(
            "Unable to load library 'ptts_ffi': libptts_ffi.so: cannot open shared object file",
        )
        val error = phononLibraryLoadError(cause)

        assertTrue(error.message!!.contains("libptts_ffi.so"))
        assertTrue(error.message!!.contains("UnsatisfiedLinkError"))
        assertTrue(error.message!!.contains(cause.message!!))
        assertSame(cause, error.cause)
    }

    @Test
    fun libraryLoadError_describesAnyLaterFailure() {
        val cause = ExceptionInInitializerError("static init failed")
        val error = phononLibraryLoadError(cause)

        assertTrue(error.message!!.contains("libptts_ffi.so"))
        assertTrue(error.message!!.contains("ExceptionInInitializerError"))
        assertTrue(error.message!!.contains("static init failed"))
        assertSame(cause, error.cause)
    }
}
