package ru.tvapk.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbPairingDiscoveryTest {
    @Test
    fun matchingEndpointsKeepsOnlyTheSelectedTelevisionAddress() {
        val endpoints = listOf(
            AdbPairingEndpoint("Living Room TV", "192.168.1.40", 37123),
            AdbPairingEndpoint("Bedroom TV", "192.168.1.41", 38211),
            AdbPairingEndpoint("Living Room TV", "192.168.1.40", 37124)
        )

        val matching = matchingAdbPairingEndpoints("192.168.1.40", endpoints)

        assertEquals(listOf(37123, 37124), matching.map { it.port })
    }

    @Test
    fun matchingEndpointsComparesIpv6BinaryAddressNotTextSpelling() {
        val endpoints = listOf(
            AdbPairingEndpoint("TV", "2001:db8::40", 37123),
            AdbPairingEndpoint("Other TV", "2001:db8::41", 37124)
        )

        val matching = matchingAdbPairingEndpoints("2001:0db8:0:0:0:0:0:40", endpoints)

        assertEquals(listOf(37123), matching.map { it.port })
    }

    @Test
    fun invalidHostDoesNotMatchAnyPairingEndpoint() {
        val endpoints = listOf(AdbPairingEndpoint("TV", "192.168.1.40", 37123))

        assertTrue(matchingAdbPairingEndpoints("not-an-ip-address", endpoints).isEmpty())
    }
    @Test
    fun hostnameIsRejectedWithoutAttemptingDnsResolution() {
        val endpoints = listOf(AdbPairingEndpoint("TV", "tv.example.invalid", 37123))

        assertTrue(matchingAdbPairingEndpoints("tv.example.invalid", endpoints).isEmpty())
    }

    @Test
    fun nonCanonicalIpv4ShorthandIsRejected() {
        val endpoints = listOf(AdbPairingEndpoint("TV", "192.168.1.40", 37123))

        assertTrue(matchingAdbPairingEndpoints("192.168.1", endpoints).isEmpty())
    }

    @Test
    fun scopedIpv6AddressMatchesSameAddressWithoutZone() {
        val endpoints = listOf(AdbPairingEndpoint("TV", "fe80::40%wlan0", 37123))

        assertEquals(
            listOf(37123),
            matchingAdbPairingEndpoints("fe80:0:0:0:0:0:0:40%wlan1", endpoints).map { it.port }
        )
    }

}
