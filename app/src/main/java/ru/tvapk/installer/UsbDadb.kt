package ru.tvapk.installer

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Base64
import dadb.AdbStream
import dadb.Dadb
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.Sink
import okio.Source
import okio.Timeout
import okio.buffer
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.ArrayDeque
import kotlin.math.min

/**
 * ADB host connection over Android USB Host (OTG).
 *
 * The target must expose an ADB USB interface (class FF, subclass 42, protocol 01)
 * and have USB debugging enabled. The user must approve the Android USB permission
 * and the RSA debugging prompt on the target.
 */
class UsbDadb private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint,
    private val keyFile: File
) : Dadb {

    private var connectionFeatures: Set<String> = emptySet()
    private val features: Set<String>
    private var maxPayload = DEFAULT_MAX_PAYLOAD
    private var nextLocalId = 1
    @Volatile private var closed = false

    init {
        handshake()
        features = connectionFeatures
    }

    override fun supportsFeature(feature: String): Boolean = feature in features

    override fun open(destination: String): AdbStream {
        check(!closed) { "USB ADB connection is closed" }
        val localId = nextLocalId++
        sendPacket(A_OPEN, localId, 0, (destination + "\u0000").toByteArray(Charsets.UTF_8))
        while (true) {
            val packet = readPacket()
            when {
                packet.command == A_OKAY && packet.arg1 == localId ->
                    return UsbAdbStream(this, localId, packet.arg0, maxPayload)
                packet.command == A_CLSE && packet.arg1 == localId ->
                    throw IOException("Телевизор отклонил ADB-сервис: $destination")
            }
        }
    }

    private fun handshake() {
        sendPacket(A_CNXN, A_VERSION, DEFAULT_MAX_PAYLOAD, "host::\u0000".toByteArray(Charsets.UTF_8))
        var packet = readPacket()
        if (packet.command == A_AUTH) {
            require(packet.arg0 == AUTH_TOKEN && packet.data.size == 20) {
                "Телевизор прислал неподдерживаемый запрос ADB-авторизации"
            }
            val keys = loadKeys(keyFile)
            val signer = Signature.getInstance("SHA1withRSA")
            signer.initSign(keys.privateKey)
            signer.update(packet.data)
            sendPacket(A_AUTH, AUTH_SIGNATURE, 0, signer.sign())
            packet = readPacket()
            if (packet.command == A_AUTH) {
                sendPacket(A_AUTH, AUTH_RSA_PUBLIC, 0, keys.adbPublicKey)
                packet = readPacket()
            }
        }
        if (packet.command == A_AUTH) {
            throw IOException("Телевизор не подтвердил ADB-ключ. Разреши USB-отладку на экране телевизора и подключись снова.")
        }
        if (packet.command != A_CNXN) {
            throw IOException("Неожиданный ответ ADB при подключении: 0x${packet.command.toString(16)}")
        }
        maxPayload = packet.arg1.coerceIn(1024, 1024 * 1024)
        val text = String(packet.data, Charsets.UTF_8)
        connectionFeatures = Regex("(?:^|;)features=([^;]*)")
            .find(text)?.groupValues?.getOrNull(1)
            ?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    }

    private fun sendPacket(command: Int, arg0: Int, arg1: Int, data: ByteArray = ByteArray(0)) {
        if (closed) throw IOException("USB ADB connection is closed")
        require(data.size <= 1024 * 1024) { "ADB packet is too large" }
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command).putInt(arg0).putInt(arg1).putInt(data.size)
            .putInt(checksum(data)).putInt(command.inv()).array()
        writeUsb(header)
        if (data.isNotEmpty()) writeUsb(data)
    }

    private fun readPacket(): Packet {
        val header = ByteArray(HEADER_SIZE)
        readUsbFully(header)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = buffer.int
        val arg0 = buffer.int
        val arg1 = buffer.int
        val length = buffer.int
        val expectedChecksum = buffer.int
        val magic = buffer.int
        if (magic != command.inv() || length !in 0..(1024 * 1024)) {
            throw IOException("Некорректный заголовок USB ADB")
        }
        val data = ByteArray(length)
        if (length > 0) readUsbFully(data)
        if (checksum(data) != expectedChecksum) throw IOException("Ошибка контрольной суммы USB ADB")
        return Packet(command, arg0, arg1, data)
    }

    private fun writeUsb(data: ByteArray) {
        var offset = 0
        val deadline = System.currentTimeMillis() + USB_TIMEOUT_MS
        while (offset < data.size) {
            val remaining = data.size - offset
            val moved = connection.bulkTransfer(bulkOut, data, offset, remaining, TRANSFER_TIMEOUT_MS)
            if (moved < 0) throw IOException("USB ADB: ошибка передачи данных")
            if (moved == 0) {
                if (System.currentTimeMillis() >= deadline) throw IOException("USB ADB: превышено время передачи")
                Thread.sleep(5)
            } else offset += moved
        }
    }

    private fun readUsbFully(target: ByteArray) {
        var offset = 0
        val deadline = System.currentTimeMillis() + USB_TIMEOUT_MS
        while (offset < target.size) {
            val moved = connection.bulkTransfer(bulkIn, target, offset, target.size - offset, TRANSFER_TIMEOUT_MS)
            if (moved < 0) throw IOException("USB ADB: устройство отключено или чтение завершилось ошибкой")
            if (moved == 0) {
                if (System.currentTimeMillis() >= deadline) throw IOException("USB ADB: ожидание ответа телевизора истекло")
                Thread.sleep(5)
            } else offset += moved
        }
    }

    private fun checksum(data: ByteArray): Int = data.sumOf { it.toInt() and 0xff }

    internal fun readStreamPacket(localId: Int, remoteId: Int, pending: ArrayDeque<Packet>): Packet? {
        while (true) {
            if (pending.isNotEmpty()) return pending.removeFirst()
            val packet = readPacket()
            if (packet.arg1 != localId && packet.command in intArrayOf(A_OKAY, A_WRTE, A_CLSE)) continue
            when (packet.command) {
                A_WRTE -> {
                    if (packet.arg1 == localId && packet.arg0 == remoteId) return packet
                }
                A_CLSE -> {
                    if (packet.arg1 == localId && packet.arg0 == remoteId) {
                        sendPacket(A_CLSE, localId, remoteId)
                        return null
                    }
                }
                A_OKAY -> Unit
                else -> throw IOException("Неожиданный пакет USB ADB-потока")
            }
        }
    }

    internal fun acknowledge(localId: Int, remoteId: Int) = sendPacket(A_OKAY, localId, remoteId)

    internal fun sendStreamData(localId: Int, remoteId: Int, bytes: ByteArray, pending: ArrayDeque<Packet>) {
        sendPacket(A_WRTE, localId, remoteId, bytes)
        while (true) {
            val packet = readPacket()
            if (packet.arg1 != localId && packet.command in intArrayOf(A_OKAY, A_WRTE, A_CLSE)) continue
            when (packet.command) {
                A_OKAY -> if (packet.arg1 == localId && packet.arg0 == remoteId) return
                A_WRTE -> if (packet.arg1 == localId && packet.arg0 == remoteId) pending.addLast(packet)
                A_CLSE -> throw IOException("Телевизор закрыл USB ADB-поток")
            }
        }
    }

    internal fun closeStream(localId: Int, remoteId: Int) {
        if (!closed) runCatching { sendPacket(A_CLSE, localId, remoteId) }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { connection.releaseInterface(usbInterface) }
        connection.close()
    }

    internal data class Packet(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private class UsbAdbStream(
        private val adb: UsbDadb,
        private val localId: Int,
        private val remoteId: Int,
        private val maxPayload: Int
    ) : AdbStream {
        private val pending = ArrayDeque<Packet>()
        private var current: Packet? = null
        private var currentOffset = 0
        private var streamClosed = false

        override val source: BufferedSource = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (byteCount == 0L) return 0L
                if (streamClosed) return -1L
                if (current == null || currentOffset >= current!!.data.size) {
                    current = adb.readStreamPacket(localId, remoteId, pending)
                    currentOffset = 0
                    if (current == null) {
                        streamClosed = true
                        return -1L
                    }
                    if (current!!.data.isEmpty()) {
                        adb.acknowledge(localId, remoteId)
                        current = null
                        return read(sink, byteCount)
                    }
                }
                val packet = current!!
                val count = min(byteCount, (packet.data.size - currentOffset).toLong()).toInt()
                sink.write(packet.data, currentOffset, count)
                currentOffset += count
                if (currentOffset >= packet.data.size) {
                    adb.acknowledge(localId, remoteId)
                    current = null
                }
                return count.toLong()
            }
            override fun timeout(): Timeout = Timeout.NONE
            override fun close() { closeStream() }
        }.buffer()

        override val sink: BufferedSink = object : Sink {
            override fun write(source: Buffer, byteCount: Long) {
                var remaining = byteCount
                while (remaining > 0) {
                    val count = min(remaining, maxPayload.toLong()).toInt()
                    val bytes = source.readByteArray(count.toLong())
                    adb.sendStreamData(localId, remoteId, bytes, pending)
                    remaining -= count
                }
            }
            override fun flush() {}
            override fun timeout(): Timeout = Timeout.NONE
            override fun close() { closeStream() }
        }.buffer()

        private fun closeStream() {
            if (streamClosed) return
            streamClosed = true
            adb.closeStream(localId, remoteId)
        }
    }

    private data class KeyMaterial(val privateKey: java.security.PrivateKey, val adbPublicKey: ByteArray)

    private fun loadKeys(file: File): KeyMaterial {
        val pem = file.readText(Charsets.UTF_8)
        val encoded = pem.substringAfter("-----BEGIN PRIVATE KEY-----")
            .substringBefore("-----END PRIVATE KEY-----")
            .filterNot { it.isWhitespace() }
        val privateKey = KeyFactory.getInstance("RSA").generatePrivate(
            PKCS8EncodedKeySpec(Base64.decode(encoded, Base64.DEFAULT))
        ) as RSAPrivateCrtKey
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(
            RSAPublicKeySpec(privateKey.modulus, privateKey.publicExponent)
        ) as RSAPublicKey
        return KeyMaterial(privateKey, encodeAdbPublicKey(publicKey))
    }

    private fun encodeAdbPublicKey(publicKey: RSAPublicKey): ByteArray {
        val words = 64
        val r32 = BigInteger.ONE.shiftLeft(32)
        val modulus = publicKey.modulus
        val n0inv = modulus.mod(r32).modInverse(r32).negate().toInt()
        val r = BigInteger.ONE.shiftLeft(words * 32)
        var n = modulus
        var rr = r.modPow(BigInteger.valueOf(2), modulus)
        val nWords = IntArray(words)
        val rrWords = IntArray(words)
        for (i in 0 until words) {
            val nPart = n.divideAndRemainder(r32)
            n = nPart[0]
            nWords[i] = nPart[1].toInt()
            val rrPart = rr.divideAndRemainder(r32)
            rr = rrPart[0]
            rrWords[i] = rrPart[1].toInt()
        }
        val raw = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(words)
            putInt(n0inv)
            nWords.forEach { putInt(it) }
            rrWords.forEach { putInt(it) }
            putInt(publicKey.publicExponent.toInt())
        }.array()
        val encoded = Base64.encodeToString(raw, Base64.NO_WRAP)
        return (encoded + " tvapk@android\u0000").toByteArray(Charsets.UTF_8)
    }

    companion object {
        private const val HEADER_SIZE = 24
        private const val DEFAULT_MAX_PAYLOAD = 4096
        private const val USB_TIMEOUT_MS = 60_000L
        private const val TRANSFER_TIMEOUT_MS = 5_000
        private const val A_SYNC = 0x434e5953
        private const val A_CNXN = 0x4e584e43
        private const val A_AUTH = 0x48545541
        private const val A_OPEN = 0x4e45504f
        private const val A_OKAY = 0x59414b4f
        private const val A_CLSE = 0x45534c43
        private const val A_WRTE = 0x45545257
        private const val A_VERSION = 0x01000000
        private const val AUTH_TOKEN = 1
        private const val AUTH_SIGNATURE = 2
        private const val AUTH_RSA_PUBLIC = 3

        fun connect(context: Context, device: UsbDevice, keyFile: File): UsbDadb {
            val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            require(manager.hasPermission(device)) { "Нет разрешения Android на доступ к USB-устройству" }
            val match = findAdbInterface(device) ?: throw IOException("Устройство не предоставляет ADB USB-интерфейс")
            val connection = manager.openDevice(device) ?: throw IOException("Не удалось открыть USB-устройство")
            if (!connection.claimInterface(match.first, true)) {
                connection.close()
                throw IOException("Не удалось захватить ADB USB-интерфейс")
            }
            return try {
                UsbDadb(connection, match.first, match.second.first, match.second.second, keyFile)
            } catch (e: Exception) {
                runCatching { connection.releaseInterface(match.first) }
                connection.close()
                throw e
            }
        }

        fun hasAdbInterface(device: UsbDevice): Boolean = findAdbInterface(device) != null

        private fun findAdbInterface(device: UsbDevice): Pair<UsbInterface, Pair<UsbEndpoint, UsbEndpoint>>? {
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass != 0xff || intf.interfaceSubclass != 0x42 || intf.interfaceProtocol != 0x01) continue
                var input: UsbEndpoint? = null
                var output: UsbEndpoint? = null
                for (e in 0 until intf.endpointCount) {
                    val endpoint = intf.getEndpoint(e)
                    if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                    if (endpoint.direction == UsbConstants.USB_DIR_IN) input = endpoint
                    if (endpoint.direction == UsbConstants.USB_DIR_OUT) output = endpoint
                }
                if (input != null && output != null) return intf to (input to output)
            }
            return null
        }
    }
}
