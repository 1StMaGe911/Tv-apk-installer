package ru.tvapk.installer

import org.junit.Assert.assertThrows
import org.junit.Test

class KadbTlsTransportValidationTest {

    @Test
    fun validIpv4AndIpv6EndpointsAreAccepted() {
        validateKadbEndpoint("192.168.1.25", 5555)
        validateKadbEndpoint("2001:db8::25", 37123)
        validateKadbEndpoint(" tv.local ", 1)
        validateKadbEndpoint("tv.local", 65535)
    }

    @Test
    fun blankHostIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            validateKadbEndpoint("  ", 5555)
        }
    }

    @Test
    fun invalidPortsAreRejected() {
        listOf(-1, 0, 65536).forEach { port ->
            assertThrows(IllegalArgumentException::class.java) {
                validateKadbEndpoint("192.168.1.25", port)
            }
        }
    }

    @Test
    fun exactlySixDigitPairingCodeIsAccepted() {
        validateKadbPairingCode("012345")
    }

    @Test
    fun malformedPairingCodesAreRejected() {
        listOf("", "12345", "1234567", "12a456", "１２３４５６", "123 56").forEach { code ->
            assertThrows(IllegalArgumentException::class.java) {
                validateKadbPairingCode(code)
            }
        }
    }
}
