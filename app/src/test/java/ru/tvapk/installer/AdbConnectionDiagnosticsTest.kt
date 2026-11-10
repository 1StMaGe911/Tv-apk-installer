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
    @Test
    fun retriesConnectionEstablishmentUntilConnected() {
        var attempts = 0
        val pauses = mutableListOf<Long>()
        val connected = AdbConnectionDiagnostics.retryConnection(
            maxAttempts = 3,
            retryDelayMillis = 250L,
            connect = {
                attempts++
                if (attempts < 3) throw java.net.SocketTimeoutException("timeout")
                "connected"
            },
            isRetryable = { it is java.net.SocketTimeoutException },
            pause = { pauses += it }
        )
        assertEquals("connected", connected)
        assertEquals(3, attempts)
        assertEquals(listOf(250L, 250L), pauses)
    }

    @Test
    fun doesNotRetryNonRetryableConnectionFailures() {
        var attempts = 0
        try {
            AdbConnectionDiagnostics.retryConnection(
                maxAttempts = 3,
                connect = { attempts++; throw IllegalArgumentException("bad endpoint") },
                isRetryable = { false },
                pause = {}
            )
        } catch (_: IllegalArgumentException) {
            // Expected: invalid connection parameters are not retried.
        }
        assertEquals(1, attempts)
    }

    @Test
    fun propagatesLastRetryableConnectionFailure() {
        var attempts = 0
        var thrown: Exception? = null
        try {
            AdbConnectionDiagnostics.retryConnection(
                maxAttempts = 2,
                connect = { attempts++; throw java.net.SocketTimeoutException("still offline") },
                isRetryable = { true },
                pause = {}
            )
        } catch (error: Exception) {
            thrown = error
        }
        assertEquals(2, attempts)
        assertTrue(thrown is java.net.SocketTimeoutException)
    }

}
