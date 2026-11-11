package ru.tvapk.installer

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class KadbDadbAdapterTest {

    @Test
    fun featureSupportIsForwardedToKadbConnection() {
        val connection = FakeConnection(supportedFeatures = setOf("shell_v2"))
        val adapter = KadbDadbAdapter(connection)

        assertTrue(adapter.supportsFeature("shell_v2"))
        assertFalse(adapter.supportsFeature("abb_exec"))
        assertEquals(listOf("shell_v2", "abb_exec"), connection.checkedFeatures)
    }

    @Test
    fun openForwardsDestinationAndPreservesStreamEndpoints() {
        val connection = FakeConnection()
        val adapter = KadbDadbAdapter(connection)

        val stream = adapter.open("shell:echo ready")

        assertEquals("shell:echo ready", connection.openedDestinations.single())
        assertSame(connection.stream.source, stream.source)
        assertSame(connection.stream.sink, stream.sink)
    }

    @Test
    fun closingAdaptedStreamClosesUnderlyingKadbStream() {
        val connection = FakeConnection()
        val stream = KadbDadbAdapter(connection).open("shell:id")

        stream.close()

        assertEquals(1, connection.stream.closeCount)
        assertEquals(0, connection.closeCount)
    }

    @Test
    fun closingAdapterClosesUnderlyingKadbConnection() {
        val connection = FakeConnection()

        KadbDadbAdapter(connection).close()

        assertEquals(1, connection.closeCount)
    }

    private class FakeConnection(
        private val supportedFeatures: Set<String> = emptySet()
    ) : KadbDadbConnection {
        val stream = FakeStream()
        val checkedFeatures = mutableListOf<String>()
        val openedDestinations = mutableListOf<String>()
        var closeCount = 0

        override fun supportsFeature(feature: String): Boolean {
            checkedFeatures += feature
            return feature in supportedFeatures
        }

        override fun open(destination: String): KadbDadbStream {
            openedDestinations += destination
            return stream
        }

        override fun close() {
            closeCount++
        }
    }

    private class FakeStream : KadbDadbStream {
        override val source = Buffer()
        override val sink = Buffer()
        var closeCount = 0

        override fun close() {
            closeCount++
        }
    }
}
