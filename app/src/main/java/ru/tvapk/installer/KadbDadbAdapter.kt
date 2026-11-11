package ru.tvapk.installer

import com.flyfishxu.kadb.Kadb
import dadb.AdbStream
import dadb.Dadb
import okio.BufferedSink
import okio.BufferedSource

/**
 * Small boundary around Kadb so the Dadb bridge can be verified on the JVM
 * without opening a real TLS socket or depending on Android services.
 */
internal interface KadbDadbConnection {
    fun supportsFeature(feature: String): Boolean
    fun open(destination: String): KadbDadbStream
    fun close()
}

internal interface KadbDadbStream {
    val source: BufferedSource
    val sink: BufferedSink
    fun close()
}

private class RealKadbDadbConnection(
    private val delegate: Kadb
) : KadbDadbConnection {
    override fun supportsFeature(feature: String): Boolean =
        delegate.supportsFeature(feature)

    override fun open(destination: String): KadbDadbStream {
        val stream = delegate.open(destination)
        return object : KadbDadbStream {
            override val source: BufferedSource = stream.source
            override val sink: BufferedSink = stream.sink
            override fun close() = stream.close()
        }
    }

    override fun close() = delegate.close()
}

/**
 * Exposes Kadb's TLS ADB transport through Dadb's existing API.
 *
 * Dadb owns the higher-level shell, sync, install and uninstall implementations;
 * only the low-level stream boundary is adapted here. This keeps existing
 * maintenance operations transport-agnostic.
 */
internal class KadbDadbAdapter(
    private val delegate: KadbDadbConnection
) : Dadb {

    constructor(delegate: Kadb) : this(RealKadbDadbConnection(delegate))

    override fun supportsFeature(feature: String): Boolean =
        delegate.supportsFeature(feature)

    override fun open(destination: String): AdbStream {
        val stream = delegate.open(destination)
        return object : AdbStream {
            override val source: BufferedSource = stream.source
            override val sink: BufferedSink = stream.sink

            override fun close() {
                stream.close()
            }
        }
    }

    override fun close() {
        delegate.close()
    }
}
