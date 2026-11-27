package it.uhde.periph.chips.rfid

import it.uhde.periph.connection.Connection
import java.io.IOException

/** PN532 protocol or status error; [code] is the PN532 status code (`Status & 0x3F`) or -1. */
open class Pn532Exception(message: String, val code: Int = -1) : IOException(message)

/** No response arrived within the timeout (the command was aborted with an ACK frame). */
class Pn532TimeoutException(message: String) : Pn532Exception(message)

/**
 * PN532 NFC/RFID controller (NXP) — minimal interface: detects one ISO/IEC 14443 A card at
 * 106 kbps and returns its UID.
 *
 * The host exchanges frames with the PN532 firmware: a command frame
 * `00 00 FF LEN LCS D4 code data DCS 00`, the chip's ACK frame `00 00 FF 00 FF 00` and a
 * response frame (TFI 0xD5, code + 1). Every I²C read starts with a status byte (bit 0 = frame
 * ready); a 1-byte read does not consume the frame. The driver reads the 6-byte response header,
 * sends a NACK so the chip resends the response, then reads exactly `1 + LEN + 7` bytes.
 *
 * [init] checks GetFirmwareVersion (IC 0x32), sets SAMConfiguration normal mode with the IRQ pin
 * enabled and MxRtyPassiveActivation = 2. Writes are retried up to 10 times (2 ms apart) to
 * wake the chip; the ACK is awaited 15 ms and the frame resent up to 3 times. Wire I0 low and
 * I1 high; address 0x24. The constructor does not touch the bus.
 *
 * @param connection plain I²C connection bound to address 0x24
 */
open class Pn532Minimal(protected val connection: Connection) {
    companion object {
        /** Fixed 7-bit I²C address. */
        const val ADDRESS = 0x24

        internal val ACK = byteArrayOf(0x00, 0x00, 0xFF.toByte(), 0x00, 0xFF.toByte(), 0x00)
        internal val NACK = byteArrayOf(0x00, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0x00, 0x00)
        const val TFI_HOST = 0xD4
        const val TFI_CHIP = 0xD5
        const val TFI_ERROR = 0x7F

        const val CMD_DIAGNOSE = 0x00
        const val CMD_GET_FIRMWARE_VERSION = 0x02
        const val CMD_GET_GENERAL_STATUS = 0x04
        const val CMD_READ_REGISTER = 0x06
        const val CMD_WRITE_REGISTER = 0x08
        const val CMD_SAM_CONFIGURATION = 0x14
        const val CMD_POWER_DOWN = 0x16
        const val CMD_RF_CONFIGURATION = 0x32
        const val CMD_IN_DATA_EXCHANGE = 0x40
        const val CMD_IN_COMMUNICATE_THRU = 0x42
        const val CMD_IN_DESELECT = 0x44
        const val CMD_IN_LIST_PASSIVE_TARGET = 0x4A
        const val CMD_IN_RELEASE = 0x52
        const val CMD_IN_SELECT = 0x54

        const val IC = 0x32
        const val WRITE_RETRIES = 10
        const val ACK_TIMEOUT = 0.015
        const val ACK_ATTEMPTS = 3

        /** Builds a host command frame. */
        fun frame(command: Int, data: ByteArray): ByteArray {
            val length = data.size + 2
            val f = ByteArray(data.size + 9)
            f[2] = 0xFF.toByte()
            f[3] = length.toByte()
            f[4] = (0x100 - length).toByte()
            f[5] = TFI_HOST.toByte()
            f[6] = command.toByte()
            var sum = TFI_HOST + command
            data.forEachIndexed { i, b ->
                f[7 + i] = b
                sum += b.toInt() and 0xFF
            }
            f[7 + data.size] = (0x100 - (sum and 0xFF)).toByte()
            return f
        }
    }

    /** Wakes the chip, checks the firmware (IC 0x32) and configures SAM and retries. */
    fun init() {
        val d = command(CMD_GET_FIRMWARE_VERSION, ByteArray(0), 1.0)
        if (d.size < 4 || (d[0].toInt() and 0xFF) != IC) {
            throw Pn532Exception("Unexpected PN532 IC 0x%02X, expected 0x32".format(if (d.isNotEmpty()) d[0].toInt() and 0xFF else 0))
        }
        command(CMD_SAM_CONFIGURATION, byteArrayOf(0x01, 0x14, 0x01), 1.0)
        command(CMD_RF_CONFIGURATION, byteArrayOf(0x05, 0xFF.toByte(), 0x01, 0x02), 1.0)
    }

    /**
     * Detects one ISO/IEC 14443 A card and returns its UID; the target is released.
     *
     * @param timeout response timeout in s; a timeout aborts the command
     * @return the NFCID1 (4, 7 or 10 bytes), or null when no card answered
     */
    @JvmOverloads
    fun readUid(timeout: Double = 1.0): ByteArray? {
        val d = list(1, 0x00, ByteArray(0), timeout) ?: return null
        if (d.size < 6 || d[0].toInt() == 0) return null
        val n = d[5].toInt() and 0xFF
        if (6 + n > d.size) throw Pn532Exception("PN532 bad target data")
        val uid = d.copyOfRange(6, 6 + n)
        command(CMD_IN_RELEASE, byteArrayOf(d[1]), 1.0)
        return uid
    }

    @Synchronized
    protected fun list(maxTargets: Int, baud: Int, initiatorData: ByteArray, timeout: Double): ByteArray? {
        start(CMD_IN_LIST_PASSIVE_TARGET, byteArrayOf(maxTargets.toByte(), baud.toByte()) + initiatorData)
        return try {
            response(CMD_IN_LIST_PASSIVE_TARGET, timeout)
        } catch (e: Pn532TimeoutException) {
            null
        }
    }

    protected fun writeFrame(data: ByteArray) {
        var attempt = 0
        while (true) {
            try {
                connection.write(data)
                return
            } catch (e: IOException) {
                if (++attempt >= WRITE_RETRIES) throw e
                Thread.sleep(2)
            }
        }
    }

    protected fun ready(): Boolean {
        val s = connection.read(1)
        return s.isNotEmpty() && (s[0].toInt() and 0x01) != 0
    }

    protected fun waitFrame(timeout: Double): Boolean {
        val polls = (timeout * 1000).toInt() + 1
        repeat(polls) {
            if (ready()) return true
            Thread.sleep(1)
        }
        return false
    }

    protected fun start(command: Int, data: ByteArray) {
        val f = frame(command, data)
        repeat(ACK_ATTEMPTS) {
            writeFrame(f)
            if (waitFrame(ACK_TIMEOUT)) {
                val ack = connection.read(7)
                if (ack.size == 7 && ack.copyOfRange(1, 7).contentEquals(ACK)) return
            }
        }
        throw Pn532Exception("PN532 did not ACK command 0x%02X".format(command))
    }

    protected fun response(command: Int, timeout: Double): ByteArray {
        if (!waitFrame(timeout)) {
            writeFrame(ACK) // abort the running command
            throw Pn532TimeoutException("PN532 response timeout (command 0x%02X)".format(command))
        }
        val h = connection.read(6)
        if (h.size < 6 || h[1].toInt() != 0 || h[2].toInt() != 0 || (h[3].toInt() and 0xFF) != 0xFF) {
            throw Pn532Exception("PN532 bad frame preamble")
        }
        val length = h[4].toInt() and 0xFF
        if (((length + (h[5].toInt() and 0xFF)) and 0xFF) != 0) throw Pn532Exception("PN532 bad length checksum")
        writeFrame(NACK) // have the chip resend the whole frame
        if (!waitFrame(timeout)) throw Pn532TimeoutException("PN532 response timeout (command 0x%02X)".format(command))
        val f = connection.read(1 + length + 7)
        if (f.size < 1 + length + 7) throw Pn532Exception("PN532 short frame")
        if (length == 1 && (f[6].toInt() and 0xFF) == TFI_ERROR) {
            throw Pn532Exception("PN532 error frame (command 0x%02X)".format(command))
        }
        if (length < 2 || (f[6].toInt() and 0xFF) != TFI_CHIP || (f[7].toInt() and 0xFF) != command + 1) {
            throw Pn532Exception("PN532 unexpected response to command 0x%02X".format(command))
        }
        var sum = f[6 + length].toInt() and 0xFF
        for (i in 6 until 6 + length) sum += f[i].toInt() and 0xFF
        if ((sum and 0xFF) != 0) throw Pn532Exception("PN532 bad data checksum")
        return f.copyOfRange(8, 6 + length)
    }

    @Synchronized
    protected fun command(command: Int, data: ByteArray, timeout: Double): ByteArray {
        start(command, data)
        return response(command, timeout)
    }
}
