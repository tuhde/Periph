package it.uhde.periph.chips.comms

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * MCP2515 — stand-alone CAN 2.0B controller with SPI interface (minimal driver).
 *
 * Sends and receives CAN 2.0B data frames with 11-bit standard or 29-bit
 * extended identifiers, payload length 0–8 bytes, and a single zero-configuration
 * API. Uses polled operation only (no INT pin).
 *
 * Sensible defaults baked in at construction:
 * - RXM[1:0]=`11` in both RXB0CTRL and RXB1CTRL (accept all, bypasses filters)
 * - BUKT=1 in RXB0CTRL (rollover from RXB0 to RXB1 on overflow)
 * - CANINTE=0x00 (polled operation)
 * - OSM=0 (retransmit on error or loss of arbitration)
 * - TXB0 used exclusively for TX; TXP=`11` (highest priority)
 *
 * Bit-timing presets supported (BTLMODE=1, SAM=0, SJW=1 TQ):
 * - 125 / 250 / 500 / 1000 kbit/s at 8 MHz
 * - 125 / 250 / 500 / 1000 kbit/s at 16 MHz
 *
 * @param connection  SPI connection bound to the device (CS managed by the kernel).
 * @param bitrateKbps bus bit rate in kbit/s (125, 250, 500, or 1000).
 * @param oscMhz      oscillator frequency in MHz (8 or 16).
 */
open class Mcp2515Minimal @JvmOverloads constructor(
    protected val connection: Connection,
    bitrateKbps: Int = 125,
    oscMhz: Int = 8
) {

    protected var bitrateKbps: Int = bitrateKbps
    protected var oscMhz: Int = oscMhz
    protected val rxDataBuf = ByteArray(8)

    companion object {
        // --- SPI instruction set ---
        const val INSTR_RESET       = 0xC0
        const val INSTR_READ        = 0x03
        const val INSTR_READ_RX_BUF = 0x90
        const val INSTR_WRITE       = 0x02
        const val INSTR_LOAD_TX_BUF = 0x40
        const val INSTR_RTS         = 0x80
        const val INSTR_READ_STATUS = 0xA0
        const val INSTR_RX_STATUS   = 0xB0
        const val INSTR_BIT_MODIFY  = 0x05

        // --- Register addresses ---
        const val REG_CANSTAT      = 0x0E
        const val REG_CANCTRL      = 0x0F
        const val REG_CNF3         = 0x28
        const val REG_CNF2         = 0x29
        const val REG_CNF1         = 0x2A
        const val REG_CANINTE      = 0x2B
        const val REG_CANINTF      = 0x2C
        const val REG_TXB0CTRL     = 0x30
        const val REG_TXB0SIDH     = 0x31
        const val REG_TXB0SIDL     = 0x32
        const val REG_TXB0EID8     = 0x33
        const val REG_TXB0EID0     = 0x34
        const val REG_TXB0DLC      = 0x35
        const val REG_TXB0D0       = 0x36
        const val REG_RXB0CTRL     = 0x60
        const val REG_RXB1CTRL     = 0x70

        // --- CANCTRL bit fields ---
        const val REQOP_NORMAL     = 0x00
        const val REQOP_SLEEP      = 0x20
        const val REQOP_LOOPBACK   = 0x40
        const val REQOP_LISTEN     = 0x60
        const val REQOP_CONFIG     = 0x80
        const val CANCTRL_ABAT     = 0x10
        const val CANCTRL_OSM      = 0x08

        // --- CANSTAT OPMOD[2:0] values (mirror REQOP encoding) ---
        const val OPMOD_NORMAL     = 0x00
        const val OPMOD_SLEEP      = 0x20
        const val OPMOD_LOOPBACK   = 0x40
        const val OPMOD_LISTEN_ONLY = 0x60
        const val OPMOD_CONFIG     = 0x80

        // --- CANINTF / CANINTE bits ---
        const val RX0IF = 0x01
        const val RX1IF = 0x02
        const val TX0IF = 0x04

        // --- TXBnCTRL bits ---
        const val TXREQ = 0x08
        const val TXP_HIGHEST = 0x03

        const val RXB0_RXM_ANY = 0x60
        const val RXB0_BUKT    = 0x04

        const val TX_RTS_TXB0 = 0x01

        // --- DLC bits ---
        const val DLC_RTR = 0x40

        // --- Pre-computed CNF1/CNF2/CNF3 for (bitrate, F_OSC) pairs ---
        // [bitrate index][F_OSC index]; bitrateIdx 0=125, 1=250, 2=500, 3=1000.
        private val CNF1_TABLE = intArrayOf(
            0x01, 0x03,  // 125 kbit/s @ 8 MHz, 16 MHz
            0x00, 0x01,  // 250 kbit/s
            0x00, 0x00,  // 500 kbit/s
            0x00, 0x00   // 1 Mbit/s
        )
        private val CNF2_TABLE = intArrayOf(
            0xBA, 0xBA,
            0xBA, 0xBA,
            0x91, 0xBA,
            0x80, 0x91
        )
        private val CNF3_TABLE = intArrayOf(
            0x03, 0x03,
            0x03, 0x03,
            0x01, 0x03,
            0x00, 0x01
        )

        protected const val RESET_DELAY_MS = 5
        protected const val MODE_POLL_MS = 1
        private const val MODE_TIMEOUT_MS = 50
        private const val TX_POLL_MS = 1
        private const val TX_TIMEOUT_MS = 100

        @JvmStatic
        protected fun sleepMs(ms: Int) {
            try {
                Thread.sleep(ms.toLong())
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        private fun bitrateIndex(bitrateKbps: Int): Int = when (bitrateKbps) {
            125 -> 0
            250 -> 1
            500 -> 2
            1000 -> 3
            else -> throw IllegalArgumentException(
                "bitrateKbps must be 125, 250, 500, or 1000 (got $bitrateKbps)")
        }

        private fun oscIndex(oscMhz: Int): Int = when (oscMhz) {
            8 -> 0
            16 -> 1
            else -> throw IllegalArgumentException("oscMhz must be 8 or 16 (got $oscMhz)")
        }
    }

    init {
        init(bitrateKbps, oscMhz)
    }

    /**
     * Re-run the initialization sequence with the given bitrate/oscillator pair.
     *
     * @param bitrateKbps bus bit rate in kbit/s (125, 250, 500, or 1000).
     * @param oscMhz      oscillator frequency in MHz (8 or 16).
     * @throws IOException on SPI error or invalid parameters.
     */
    @JvmOverloads
    open fun init(bitrateKbps: Int = this.bitrateKbps, oscMhz: Int = this.oscMhz) {
        this.bitrateKbps = bitrateKbps
        this.oscMhz = oscMhz
        val bIdx = bitrateIndex(bitrateKbps)
        val oIdx = oscIndex(oscMhz)
        val cnf1 = CNF1_TABLE[bIdx * 2 + oIdx]
        val cnf2 = CNF2_TABLE[bIdx * 2 + oIdx]
        val cnf3 = CNF3_TABLE[bIdx * 2 + oIdx]

        // 1. Reset the chip and wait ≥ 2 µs for POR settling.
        connection.write(byteArrayOf(INSTR_RESET.toByte()))
        sleepMs(RESET_DELAY_MS)

        // 2. Verify Configuration mode.
        var canstat = readReg(REG_CANSTAT)
        if ((canstat and 0xE0) != OPMOD_CONFIG) {
            sleepMs(RESET_DELAY_MS)
            canstat = readReg(REG_CANSTAT)
            if ((canstat and 0xE0) != OPMOD_CONFIG) {
                throw IOException(
                    "MCP2515 not in Configuration mode after RESET (CANSTAT=0x${Integer.toHexString(canstat)})")
            }
        }

        // 3. Write bit-timing registers.
        writeReg(REG_CNF1, cnf1)
        writeReg(REG_CNF2, cnf2)
        writeReg(REG_CNF3, cnf3)

        // 4. Acceptance masks all-ones so any ID passes until the user sets filters.
        for (r in 0x20..0x27) writeReg(r, 0xFF)

        // 5. RXBnCTRL: RXM[1:0]=11 (accept all), BUKT=1 in RXB0CTRL for rollover.
        writeReg(REG_RXB0CTRL, RXB0_RXM_ANY or RXB0_BUKT)
        writeReg(REG_RXB1CTRL, 0x60 /* RXM=11 */)

        // 6. No interrupts — polled operation.
        writeReg(REG_CANINTE, 0x00)

        // 7. Clear any pending interrupt flags.
        writeReg(REG_CANINTF, 0x00)

        // 8. Request Normal mode (REQOP=000), OSM=0.
        writeReg(REG_CANCTRL, REQOP_NORMAL)
        waitMode(OPMOD_NORMAL)
    }

    /**
     * Send a CAN frame using TXB0 (priority TXP=11, highest).
     *
     * @param id       11-bit or 29-bit CAN identifier.
     * @param data     payload, 0–8 bytes.
     * @param extended true for 29-bit identifier, false for 11-bit.
     * @return the TX buffer used (always 0 here).
     * @throws IOException on SPI error or TX timeout.
     */
    @JvmOverloads
    open fun send(id: Int, data: ByteArray, extended: Boolean = false): Int {
        return sendBuffered(id, data, extended, 0)
    }

    /**
     * Receive one CAN frame, blocking up to `timeoutMs` for a frame to arrive.
     *
     * @param timeoutMs timeout in milliseconds; 0 means non-blocking (returns
     *                  `null` immediately if no frame is pending).
     * @return the received frame, or `null` on timeout.
     * @throws IOException on SPI error.
     */
    @JvmOverloads
    open fun recv(timeoutMs: Int = 0): CanFrame? {
        var elapsed = 0
        while (true) {
            val intf = readReg(REG_CANINTF)
            if ((intf and RX0IF) != 0) return readFrameFromBuffer(0x90 or 0x00, 0, true)
            if ((intf and RX1IF) != 0) return readFrameFromBuffer(0x90 or 0x04, 1, true)
            if (timeoutMs <= 0) return null
            if (elapsed >= timeoutMs) return null
            sleepMs(MODE_POLL_MS)
            elapsed += MODE_POLL_MS
        }
    }

    // --- SPI-level helpers (used by Full via inheritance) ---

    protected fun writeReg(reg: Int, value: Int) {
        connection.write(byteArrayOf(INSTR_WRITE.toByte(), (reg and 0xFF).toByte(), (value and 0xFF).toByte()))
    }

    protected fun readReg(reg: Int): Int {
        val buf = connection.writeRead(byteArrayOf(INSTR_READ.toByte(), (reg and 0xFF).toByte()), 1)
        return buf[0].toInt() and 0xFF
    }

    protected fun modifyReg(reg: Int, mask: Int, value: Int) {
        connection.write(byteArrayOf(
            INSTR_BIT_MODIFY.toByte(),
            (reg and 0xFF).toByte(),
            (mask and 0xFF).toByte(),
            (value and 0xFF).toByte()
        ))
    }

    /**
     * Send a CAN frame using a specific TX buffer (0, 1, or 2).
     *
     * @param id       11-bit or 29-bit CAN identifier.
     * @param data     payload, 0–8 bytes.
     * @param extended true for 29-bit identifier.
     * @param buf      TX buffer index, 0..2.
     * @return the TX buffer index used.
     * @throws IOException on SPI error, invalid buffer index, or TX timeout.
     */
    protected open fun sendBuffered(id: Int, data: ByteArray?, extended: Boolean, buf: Int): Int {
        require(buf in 0..2) { "TX buffer index must be 0..2 (got $buf)" }
        val len = data?.let { minOf(it.size, 8) } ?: 0

        val txCtrlReg = if (buf == 0) REG_TXB0CTRL else (0x40 + buf * 0x10)
        var elapsed = 0
        while (true) {
            val ctrl = readReg(txCtrlReg)
            if ((ctrl and TXREQ) == 0) break
            if (elapsed >= TX_TIMEOUT_MS) {
                throw IOException("MCP2515 TXB$buf busy after $TX_TIMEOUT_MS ms")
            }
            sleepMs(TX_POLL_MS)
            elapsed += TX_POLL_MS
        }

        // Pack ID into SIDH/SIDL/EID8/EID0 + DLC + data bytes.
        val frame = ByteArray(5 + 8)
        val sidh: Int; val sidl: Int; val eid8: Int; val eid0: Int
        if (extended) {
            require((id and 0xE0000000.toInt()) == 0) { "29-bit id out of range: $id" }
            sidh = (id ushr 21) and 0xFF
            sidl = (((id ushr 18) and 0x07) shl 5) or 0x08 or ((id ushr 16) and 0x03)
            eid8 = (id ushr 8) and 0xFF
            eid0 = id and 0xFF
        } else {
            require((id and 0xFFFFF800.toInt()) == 0) { "11-bit id out of range: $id" }
            sidh = (id ushr 3) and 0xFF
            sidl = (id and 0x07) shl 5
            eid8 = 0
            eid0 = 0
        }
        frame[0] = sidh.toByte()
        frame[1] = sidl.toByte()
        frame[2] = eid8.toByte()
        frame[3] = eid0.toByte()
        frame[4] = (len and 0x0F).toByte()
        if (data != null) {
            for (i in 0 until len) frame[5 + i] = data[i]
        }

        // LOAD TX BUFFER instruction index 0->TXB0SIDH, 2->TXB0D0, 4->TXB1SIDH, ...
        val loadInstr = 0x40 or (buf * 4)
        val txBuf = ByteArray(1 + frame.size)
        txBuf[0] = loadInstr.toByte()
        frame.copyInto(txBuf, destinationOffset = 1)
        connection.write(txBuf)

        // Set TXBnCTRL = TXREQ | TXP=11 (highest priority).
        writeReg(txCtrlReg, TXREQ or TXP_HIGHEST)

        // Issue RTS for this buffer.
        connection.write(byteArrayOf((INSTR_RTS or (1 shl buf)).toByte()))

        // Wait until TXREQ clears (the chip drops it when the message has been sent,
        // aborted, or hit a TX error with OSM=1).
        elapsed = 0
        while (true) {
            val ctrl = readReg(txCtrlReg)
            if ((ctrl and TXREQ) == 0) return buf
            if (elapsed >= TX_TIMEOUT_MS) {
                throw IOException(
                    "MCP2515 TXB$buf did not complete within $TX_TIMEOUT_MS ms (ctrl=0x${Integer.toHexString(ctrl)})")
            }
            sleepMs(TX_POLL_MS)
            elapsed += TX_POLL_MS
        }
    }

    /**
     * Read a frame from one of the RX buffers using the READ RX BUFFER instruction.
     *
     * @param loadInstr LOAD TX/RX BUFFER opcode (0x90 for RXB0, 0x94 for RXB1).
     * @param bufIndex  RX buffer index (0 or 1).
     * @param clearFlag ignored — the chip auto-clears RXnIF on CS-deassert.
     * @return the decoded frame.
     * @throws IOException on SPI error or malformed frame.
     */
    protected fun readFrameFromBuffer(loadInstr: Int, bufIndex: Int, clearFlag: Boolean): CanFrame {
        val buf = connection.writeRead(byteArrayOf(loadInstr.toByte()), 14)
        val sidh = buf[0].toInt() and 0xFF
        val sidl = buf[1].toInt() and 0xFF
        val eid8 = buf[2].toInt() and 0xFF
        val eid0 = buf[3].toInt() and 0xFF
        val dlc  = buf[4].toInt() and 0xFF

        val extended = (sidl and 0x08) != 0
        val id: Int
        val rtr: Boolean
        if (extended) {
            id = (sidh shl 21) or (((sidl ushr 5) and 0x07) shl 18) or ((sidl and 0x03) shl 16) or (eid8 shl 8) or eid0
            rtr = (dlc and DLC_RTR) != 0
        } else {
            id = (sidh shl 3) or (sidl ushr 5)
            rtr = (sidl and 0x10) != 0
        }
        var dataLen = dlc and 0x0F
        if (dataLen > 8) dataLen = 8
        val payload = ByteArray(dataLen)
        buf.copyInto(payload, destinationOffset = 0, startIndex = 5, endIndex = 5 + dataLen)

        val intfBit = if (bufIndex == 0) RX0IF else RX1IF
        modifyReg(REG_CANINTF, intfBit, 0)
        return CanFrame(id, payload, extended, rtr)
    }

    /**
     * Poll CANSTAT until OPMOD matches the requested mode.
     *
     * @param opmod expected OPMOD[2:0] value (after masking with 0xE0).
     * @throws IOException on timeout.
     */
    protected fun waitMode(opmod: Int) {
        var elapsed = 0
        while (true) {
            val canstat = readReg(REG_CANSTAT)
            if ((canstat and 0xE0) == opmod) return
            if (elapsed >= MODE_TIMEOUT_MS) {
                throw IOException(
                    "MCP2515 mode transition timed out (CANSTAT=0x${Integer.toHexString(canstat)}, want OPMOD=0x${Integer.toHexString(opmod)})")
            }
            sleepMs(MODE_POLL_MS)
            elapsed += MODE_POLL_MS
        }
    }

    /**
     * A CAN frame — standard or extended, with payload and an RTR flag.
     */
    data class CanFrame(
        val id: Int,
        val data: ByteArray,
        val extended: Boolean,
        val rtr: Boolean
    )
}
