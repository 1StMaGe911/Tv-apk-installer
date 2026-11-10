package ru.tvapk.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbConnectionDiagnosticsTest {
    @Test
    fun authorizationErrorsAreNotRetried() {
        val result = AdbConnectionDiagnostics.classify("192.168.1.20", 5555, "device unauthorized: RSA key")
        assertEquals(AdbConnectionDiagnostics.Kind.AUTHORIZATION, result.kind)
        assertFalse(result.retryable)
    }

    @Test
    fun refusedConnectionsCanBeRetried() {
        val result = AdbConnectionDiagnostics.classify("192.168.1.20", 5555, "java.net.ConnectException: Connection refused")
        assertEquals(AdbConnectionDiagnostics.Kind.REFUSED, result.kind)
        assertTrue(result.retryable)
    }

    @Test
    fun timeoutsAndUnreachableNetworksCanBeRetried() {
        assertTrue(AdbConnectionDiagnostics.classify("192.168.1.20", 5555, "SocketTimeoutException").retryable)
        assertEquals(
            AdbConnectionDiagnostics.Kind.NETWORK_UNREACHABLE,
            AdbConnectionDiagnostics.classify("192.168.1.20", 5555, "No route to host").kind
        )
    }

    @Test
    fun invalidEndpointIsNotRetried() {
        val result = AdbConnectionDiagnostics.classify("", 70000, "bad endpoint")
        assertEquals(AdbConnectionDiagnostics.Kind.INVALID_ENDPOINT, result.kind)
        assertFalse(result.retryable)
    }

    @Test
    fun unknownErrorsKeepOriginalDetailsAndGuidance() {
        val formatted = AdbConnectionDiagnostics.format("192.168.1.20", 5555, "broken pipe")
        assertTrue(formatted.contains("broken pipe"))
        assertTrue(formatted.contains("Проверь"))
    }
}
