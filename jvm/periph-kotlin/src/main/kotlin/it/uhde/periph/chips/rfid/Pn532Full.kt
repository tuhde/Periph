package it.uhde.periph.chips.rfid

import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import java.io.IOException

/**
 * PN532 NFC/RFID controller — full interface: target listing at every baud rate, data
 * exchange, MIFARE Classic and NTAG helpers, RF and retry configuration, register access,
 * power-down, the self test, raw commands and the IRQ interrupt API.
 *
 * @param connection plain I²C connection bound to address 0x24
 */
class Pn532Full(connection: Connection) : Pn532Minimal(connection) {
    companion object {
        /** InListPassiveTarget baud rate: 106 kbps ISO/IEC 14443 A. */
        const val BAUD_106_A = 0x00
        /** InListPassiveTarget baud rate: 212 kbps FeliCa. */
        const val BAUD_212_F = 0x01
        /** InListPassiveTarget baud rate: 424 kbps FeliCa. */
        const val BAUD_424_F = 0x02
        /** InListPassiveTarget baud rate: 106 kbps ISO/IEC 14443 B. */
        const val BAUD_106_B = 0x03

        private val PATTERN = "PERIPH01".toByteArray(Charsets.US_ASCII)
    }

    /** GetFirmwareVersion result; the booleans are support bits 0–2. */
    data class FirmwareVersion(
        val ic: Int, val version: Int, val revision: Int, val support: Int,
        val iso14443a: Boolean, val iso14443b: Boolean, val iso18092: Boolean,
    )

    /** Decoded GetGeneralStatus (per-target entries are skipped). */
    data class GeneralStatus(val error: Int, val field: Boolean, val targets: Int, val samStatus: Int)

    /**
     * A target found by InListPassiveTarget. [uid] is the NFCID1 (type A), NFCID2 (FeliCa) or
     * PUPI (type B); [ats] is the ATS (type A), PAD + system code (FeliCa) or ATTRIB_RES (type B);
     * [sensRes] and [selRes] are 0 for FeliCa and type B.
     */
    class Target(val tg: Int, val sensRes: Int, val selRes: Int, val uid: ByteArray, val ats: ByteArray) {
        override fun toString() = "Target(tg=$tg, sensRes=0x%04X, selRes=0x%02X, uid=%s)".format(
            sensRes, selRes, uid.joinToString("") { "%02X".format(it) })
    }

    @Volatile private var callback: (() -> Unit)? = null
    private var subscribedPin: InputPin? = null
    @Volatile private var polling = false
    private var pollThread: Thread? = null
    private val edgeHandler = EdgeHandler { callback?.invoke() }

    /** GetFirmwareVersion: IC, version, revision, support byte and its decoded bits. */
    fun firmwareVersion(): FirmwareVersion {
        val d = command(CMD_GET_FIRMWARE_VERSION, ByteArray(0), 1.0)
        if (d.size < 4) throw Pn532Exception("PN532 short response")
        val s = d[3].toInt() and 0xFF
        return FirmwareVersion(d[0].toInt() and 0xFF, d[1].toInt() and 0xFF, d[2].toInt() and 0xFF, s,
            s and 0x01 != 0, s and 0x02 != 0, s and 0x04 != 0)
    }

    /** GetGeneralStatus: error, field present, number of targets, SAM status. */
    fun generalStatus(): GeneralStatus {
        val d = command(CMD_GET_GENERAL_STATUS, ByteArray(0), 1.0)
        if (d.size < 3) throw Pn532Exception("PN532 short response")
        val n = d[2].toInt() and 0xFF
        val sam = 3 + 4 * n
        return GeneralStatus(d[0].toInt() and 0xFF, d[1].toInt() != 0, n, if (sam < d.size) d[sam].toInt() and 0xFF else 0)
    }

    /**
     * InListPassiveTarget.
     *
     * @param baud BAUD_106_A, BAUD_212_F, BAUD_424_F or BAUD_106_B
     * @param maxTargets 1 or 2
     * @param initiatorData optional InitiatorData (UID, FeliCa polling payload, AFI)
     * @param timeout response timeout in s; a timeout aborts the command and returns an empty list
     * @return targets found; empty when none answered
     */
    @JvmOverloads
    fun listPassiveTargets(
        baud: Int = BAUD_106_A, maxTargets: Int = 1, initiatorData: ByteArray = ByteArray(0), timeout: Double = 1.0,
    ): List<Target> {
        require(maxTargets in 1..2) { "maxTargets must be 1 or 2" }
        require(baud in 0..4) { "baud must be 0..4" }
        val d = list(maxTargets, baud, initiatorData, timeout) ?: return emptyList()
        val targets = mutableListOf<Target>()
        if (d.isEmpty()) return targets
        val n = d.size
        var i = 1
        var k = 0
        while (k < (d[0].toInt() and 0xFF) && i < n) {
            val tg = d[i].toInt() and 0xFF
            when (baud) {
                BAUD_106_A -> {
                    val sens = ((d[i + 1].toInt() and 0xFF) shl 8) or (d[i + 2].toInt() and 0xFF)
                    val sel = d[i + 3].toInt() and 0xFF
                    val len = d[i + 4].toInt() and 0xFF
                    val uid = d.copyOfRange(i + 5, i + 5 + len)
                    i += 5 + len
                    var ats = ByteArray(0)
                    if (sel and 0x20 != 0 && i < n) {
                        val tl = d[i].toInt() and 0xFF
                        ats = d.copyOfRange(i, minOf(i + tl, n))
                        i += tl
                    }
                    targets.add(Target(tg, sens, sel, uid, ats))
                }
                BAUD_212_F, BAUD_424_F -> {
                    val len = d[i + 1].toInt() and 0xFF
                    targets.add(Target(tg, 0, 0, d.copyOfRange(i + 3, i + 11), d.copyOfRange(i + 11, i + 1 + len)))
                    i += 1 + len
                }
                else -> {
                    val len = d[i + 13].toInt() and 0xFF
                    targets.add(Target(tg, 0, 0, d.copyOfRange(i + 2, i + 6), d.copyOfRange(i + 14, i + 14 + len)))
                    i += 14 + len
                }
            }
            k++
        }
        return targets
    }

    private fun statusCommand(command: Int, data: ByteArray): ByteArray {
        val d = command(command, data, 1.0)
        if (d.isEmpty()) throw Pn532Exception("PN532 empty status response")
        val code = d[0].toInt() and 0x3F
        if (code != 0) throw Pn532Exception("PN532 status error 0x%02X (command 0x%02X)".format(code, command), code)
        return d.copyOfRange(1, d.size)
    }

    /** InDataExchange with an activated target; a non-zero status throws with [Pn532Exception.code]. */
    fun dataExchange(target: Int, data: ByteArray): ByteArray =
        statusCommand(CMD_IN_DATA_EXCHANGE, byteArrayOf(target.toByte()) + data)

    /** InCommunicateThru: raw RF exchange with the current target. */
    fun communicateThru(data: ByteArray): ByteArray = statusCommand(CMD_IN_COMMUNICATE_THRU, data)

    /** InRelease; [target] 0 releases all. */
    @JvmOverloads
    fun release(target: Int = 0) { statusCommand(CMD_IN_RELEASE, byteArrayOf(target.toByte())) }

    /** InSelect a listed target. */
    @JvmOverloads
    fun select(target: Int = 0) { statusCommand(CMD_IN_SELECT, byteArrayOf(target.toByte())) }

    /** InDeselect (keeps the target's information); [target] 0 = all. */
    @JvmOverloads
    fun deselect(target: Int = 0) { statusCommand(CMD_IN_DESELECT, byteArrayOf(target.toByte())) }

    /**
     * Authenticates a MIFARE Classic sector (0x60 key A / 0x61 key B).
     *
     * @param key 6-byte key
     * @param uid card UID; its last 4 bytes are used
     */
    @JvmOverloads
    fun mifareAuthenticate(target: Int, block: Int, key: ByteArray, uid: ByteArray, keyB: Boolean = false) {
        require(key.size == 6) { "key must be 6 bytes" }
        require(uid.size >= 4) { "uid must have at least 4 bytes" }
        dataExchange(target, byteArrayOf((if (keyB) 0x61 else 0x60).toByte(), block.toByte()) + key + uid.copyOfRange(uid.size - 4, uid.size))
    }

    /** Reads a 16-byte MIFARE Classic block (0x30). */
    fun mifareReadBlock(target: Int, block: Int): ByteArray {
        val d = dataExchange(target, byteArrayOf(0x30, block.toByte()))
        if (d.size < 16) throw Pn532Exception("PN532 short block")
        return d.copyOf(16)
    }

    /** Writes a 16-byte MIFARE Classic block (0xA0). */
    fun mifareWriteBlock(target: Int, block: Int, data: ByteArray) {
        require(data.size == 16) { "data must be 16 bytes" }
        dataExchange(target, byteArrayOf(0xA0.toByte(), block.toByte()) + data)
    }

    /** Reads four NTAG / Ultralight pages starting at [page] (0x30), 16 bytes. */
    fun ntagReadPage(target: Int, page: Int): ByteArray = mifareReadBlock(target, page)

    /** Writes one 4-byte NTAG / Ultralight page (0xA2). */
    fun ntagWritePage(target: Int, page: Int, data: ByteArray) {
        require(data.size == 4) { "data must be 4 bytes" }
        dataExchange(target, byteArrayOf(0xA2.toByte(), page.toByte()) + data)
    }

    /** RFConfiguration item 0x01: RF field on/off and automatic RF collision avoidance. */
    @JvmOverloads
    fun setRfField(on: Boolean, autoRfca: Boolean = false) {
        command(CMD_RF_CONFIGURATION, byteArrayOf(0x01, ((if (on) 0x02 else 0) or (if (autoRfca) 0x01 else 0)).toByte()), 1.0)
    }

    /** RFConfiguration item 0x05: retry counts, 0xFF = infinite. */
    @JvmOverloads
    fun setMaxRetries(passiveActivation: Int = 2, atr: Int = 0xFF, psl: Int = 1) {
        command(CMD_RF_CONFIGURATION, byteArrayOf(0x05, atr.toByte(), psl.toByte(), passiveActivation.toByte()), 1.0)
    }

    /** ReadRegister (16-bit address). */
    fun readRegister(address: Int): Int {
        val d = command(CMD_READ_REGISTER, byteArrayOf((address shr 8).toByte(), address.toByte()), 1.0)
        if (d.isEmpty()) throw Pn532Exception("PN532 short response")
        return d[0].toInt() and 0xFF
    }

    /** WriteRegister (16-bit address). */
    fun writeRegister(address: Int, value: Int) {
        command(CMD_WRITE_REGISTER, byteArrayOf((address shr 8).toByte(), address.toByte(), value.toByte()), 1.0)
    }

    /** PowerDown; [wakeupSources] is the WakeUpEnable mask (0x20 = I²C). */
    @JvmOverloads
    fun powerDown(wakeupSources: Int = 0x20) { statusCommand(CMD_POWER_DOWN, byteArrayOf(wakeupSources.toByte())) }

    /**
     * Diagnose self test: 0x00 communication line (echo of an 8-byte pattern), 0x01 ROM, 0x02 RAM, ...
     *
     * @return true when the test passed
     */
    @JvmOverloads
    fun selfTest(test: Int = 0x00): Boolean {
        if (test == 0x00) {
            val req = byteArrayOf(0x00) + PATTERN
            return command(CMD_DIAGNOSE, req, 1.0).contentEquals(req)
        }
        val d = command(CMD_DIAGNOSE, byteArrayOf(test.toByte()), 1.0)
        return d.isNotEmpty() && d[0].toInt() == 0
    }

    /** Sends a raw command and returns its response data (after the response code). */
    @JvmOverloads
    fun sendCommand(command: Int, data: ByteArray = ByteArray(0), timeout: Double = 1.0): ByteArray =
        command(command, data, timeout)

    /** Writes a command frame and reads its ACK, without waiting for the response. */
    @JvmOverloads
    @Synchronized
    fun startCommand(command: Int, data: ByteArray = ByteArray(0)) = start(command, data)

    /** Reads the response to a command started with [startCommand]; a timeout aborts it. */
    @JvmOverloads
    @Synchronized
    fun readResponse(command: Int, timeout: Double = 1.0): ByteArray = response(command, timeout)

    /** Polls the status byte every 1 ms until a frame is ready (timeout in s). */
    @Synchronized
    fun waitReady(timeout: Double): Boolean = waitFrame(timeout)

    /** True when the status byte reports a ready ACK or response frame. */
    @Synchronized
    fun pollInterrupt(): Boolean = ready()

    /**
     * Subscribes to frame-ready events: falling edge on [intPin], or a 10 ms status-byte poll
     * that fires on each not-ready → ready transition when no pin is wired. Read the frame with
     * [readResponse].
     */
    @JvmOverloads
    fun onInterrupt(callback: () -> Unit, intPin: InputPin? = connection.intPin()) {
        offInterrupt()
        this.callback = callback
        if (intPin != null) {
            subscribedPin = intPin
            intPin.onEdge(edgeHandler, EdgeTrigger.FALLING)
        } else {
            polling = true
            pollThread = Thread({
                var was = false
                while (polling) {
                    val now = try { pollInterrupt() } catch (e: IOException) { false }
                    if (now && !was) edgeHandler.onEdge()
                    was = now
                    try {
                        Thread.sleep(10)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@Thread
                    }
                }
            }, "pn532-poll").apply {
                isDaemon = true
                start()
            }
        }
    }

    /** Unsubscribes and stops delivery. */
    fun offInterrupt() {
        callback = null
        subscribedPin?.offEdge(edgeHandler)
        subscribedPin = null
        polling = false
        pollThread?.interrupt()
        pollThread = null
    }
}
