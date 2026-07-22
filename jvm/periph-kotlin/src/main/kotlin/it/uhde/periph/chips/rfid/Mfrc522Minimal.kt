package it.uhde.periph.chips.rfid

import it.uhde.periph.transport.Transport
import java.io.IOException

/**
 * MFRC522 — 13.56 MHz contactless reader/writer (NXP). Minimal driver.
 *
 * Detects an ISO/IEC 14443 Type A card in the field and reads its UID. No
 * configuration beyond the transport and bus type is required.
 *
 * Supports three host transports — I²C, SPI, and UART — all of which
 * expose the same 64-register internal bank; the address-byte framing
 * differs per transport. The driver selects the correct framing from the
 * `busType` parameter.
 *
 * Default configuration (baked in at construction):
 * - 25 ms receive timeout (TReloadReg = 1000 @ TPrescaler = 169)
 * - Force100ASK modulation
 * - ISO/IEC 14443-3 CRC_A preset (0x6363)
 * - Antenna enabled
 * - 106 kBd, 33 dB RX gain (reset default)
 *
 * Default I²C address: 0x28 (EA=LOW, all ADR pins LOW); 0x28–0x2F for
 * EA=LOW with the lower 3 address bits set by pins ADR_0–ADR_2.
 */
open class Mfrc522Minimal @JvmOverloads constructor(
    protected val transport: Transport,
    protected val busType: Int = BUS_SPI
) {
    init {
        try { initChip() } catch (e: IOException) { throw RuntimeException(e) }
    }

    /**
     * Detect a card in the RF field.
     *
     * Sends a REQA short frame. Returns true if a card answered with a
     * valid 2-byte ATQA.
     *
     * @return true if a card is in the field.
     * @throws IOException on transport error.
     */
    open fun isCardPresent(): Boolean {
        try {
            writeReg(REG_BIT_FRAMING, 0x07)
            writeReg(REG_TX_MODE, 0x00)
            writeReg(REG_RX_MODE, 0x00)
            val back = transceive(intArrayOf(PICC_REQA))
            return back != null && back.size == 2
        } catch (e: IOException) { throw RuntimeException(e) }
    }

    /**
     * Detect a card, run anticollision/Select (all cascade levels), and HLTA.
     *
     * Returns the reassembled UID (4, 7, or 10 bytes). A card read this way
     * is immediately halted, so the next call re-detects it from scratch.
     *
     * @return UID bytes, or `null` if no card answered.
     * @throws IOException on transport error.
     */
    open fun readUid(): ByteArray? {
        if (!isCardPresent()) return null
        val uidArr = try { selectCardInternal() } catch (e: IOException) { throw RuntimeException(e) }
        try { haltCard() } catch (e: IOException) { throw RuntimeException(e) }
        if (uidArr == null) return null
        return ByteArray(uidArr.size) { i -> uidArr[i].toByte() }
    }

    protected fun addrFor(reg: Int, read: Boolean): Int {
        return when (busType) {
            BUS_SPI -> ((reg shl 1) and 0x7E) or (if (read) 0x80 else 0x00)
            BUS_UART -> (reg and 0x3F) or (if (read) 0x80 else 0x00)
            else -> reg and 0x3F
        }
    }

    protected fun writeReg(reg: Int, value: Int) {
        try { transport.write(byteArrayOf(addrFor(reg, false).toByte(), (value and 0xFF).toByte())) }
        catch (e: IOException) { throw RuntimeException(e) }
    }

    protected fun readReg(reg: Int): Int {
        try {
            val b = transport.writeRead(byteArrayOf(addrFor(reg, true).toByte()), 1)
            return b[0].toInt() and 0xFF
        } catch (e: IOException) { throw RuntimeException(e) }
    }

    protected fun setBits(reg: Int, mask: Int) {
        writeReg(reg, readReg(reg) or mask)
    }

    protected fun clearBits(reg: Int, mask: Int) {
        writeReg(reg, readReg(reg) and mask.inv())
    }

    protected fun initChip() {
        try {
            writeReg(REG_COMMAND, CMD_SOFT_RESET)
            for (i in 0 until 50) {
                if ((readReg(REG_COMMAND) and 0x10) == 0) break
                sleep(1)
            }
            sleep(50)
            writeReg(REG_T_MODE,      0x80)
            writeReg(REG_T_PRESCALER, 0xA9)
            writeReg(REG_T_RELOAD_H,  0x03)
            writeReg(REG_T_RELOAD_L,  0xE8)
            writeReg(REG_TX_ASK, 0x40)
            writeReg(REG_MODE, 0x3D)
            setBits(REG_TX_CONTROL, 0x03)
        } catch (e: IOException) { throw RuntimeException(e) }
    }

    protected fun sleep(ms: Int) {
        try { Thread.sleep(ms.toLong()) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
    }

    protected fun readFifo(n: Int): IntArray {
        val out = IntArray(n)
        for (i in 0 until n) out[i] = readReg(REG_FIFO_DATA)
        return out
    }

    protected fun writeFifo(data: IntArray) {
        for (b in data) writeReg(REG_FIFO_DATA, b)
    }

    protected fun flushFifo() { writeReg(REG_FIFO_LEVEL, FIFO_FLUSH) }

    protected fun cardCommand(command: Int, waitIrq: Int, sendData: IntArray?): Boolean {
        try {
            writeReg(REG_COMMAND, CMD_IDLE)
            writeReg(REG_COM_IRQ, 0x7F)
            flushFifo()
            if (sendData != null && sendData.isNotEmpty()) writeFifo(sendData)
            writeReg(REG_COMMAND, command)
            if (command == CMD_TRANSCEIVE) setBits(REG_BIT_FRAMING, 0x80)
            for (i in 0 until 200) {
                val n = readReg(REG_COM_IRQ)
                if ((n and waitIrq) != 0) return true
                if ((n and 0x01) != 0) return false
            }
            return false
        } catch (e: IOException) { throw RuntimeException(e) }
    }

    protected fun transceive(send: IntArray): IntArray? {
        if (!cardCommand(CMD_TRANSCEIVE, IRQ_RX or IRQ_IDLE, send)) return null
        val err = readReg(REG_ERROR)
        if ((err and 0x13) != 0) return null
        val fifoLevel = readReg(REG_FIFO_LEVEL)
        if (fifoLevel == 0) return null
        return readFifo(fifoLevel)
    }

    protected fun calcCrc(data: IntArray): IntArray {
        writeReg(REG_COMMAND, CMD_IDLE)
        writeReg(REG_DIV_IRQ, 0x04)
        flushFifo()
        writeFifo(data)
        writeReg(REG_COMMAND, CMD_CALC_CRC)
        for (i in 0 until 100) {
            if ((readReg(REG_DIV_IRQ) and 0x04) != 0) break
            sleep(1)
        }
        writeReg(REG_COMMAND, CMD_IDLE)
        return intArrayOf(readReg(REG_CRC_RESULT_H), readReg(REG_CRC_RESULT_L))
    }

    protected fun anticollision(cmd: Int): IntArray? {
        writeReg(REG_TX_MODE, 0x00)
        writeReg(REG_RX_MODE, 0x00)
        writeReg(REG_BIT_FRAMING, 0x00)
        val send = IntArray(7)
        send[0] = cmd
        send[1] = 0x20
        sleep(1)
        val back = transceive(send) ?: return null
        if (back.size != 5) return null
        var bcc = 0
        for (i in 0 until 4) bcc = bcc xor back[i]
        if (bcc != back[4]) return null
        return intArrayOf(back[0], back[1], back[2], back[3])
    }

    protected fun select(cmd: Int, uidPart: IntArray): Int? {
        val buf = IntArray(9)
        buf[0] = cmd
        buf[1] = PICC_SEL_BIT
        System.arraycopy(uidPart, 0, buf, 2, 4)
        var bcc = 0
        for (i in 0 until 4) bcc = bcc xor uidPart[i]
        buf[6] = bcc
        val crc = calcCrc(intArrayOf(buf[0], buf[1], buf[2], buf[3], buf[4], buf[5], buf[6]))
        buf[7] = crc[0]
        buf[8] = crc[1]
        writeReg(REG_TX_MODE, 0x80)
        writeReg(REG_RX_MODE, 0x80)
        sleep(1)
        val back = transceive(buf)
        writeReg(REG_TX_MODE, 0x00)
        writeReg(REG_RX_MODE, 0x00)
        if (back == null || back.isEmpty()) return null
        return back[0]
    }

    protected fun selectCardInternal(): IntArray? {
        var uid = IntArray(10)
        var len = 0
        val cascade = arrayOf(intArrayOf(0x93, 0x93), intArrayOf(0x95, 0x95), intArrayOf(0x97, 0x97))
        for (level in cascade) {
            val part = anticollision(level[0]) ?: return null
            val sak = select(level[1], part) ?: return null
            if ((sak and PICC_SAK_NOT_COMPLETE) == 0) {
                if (part[0] == PICC_CT) {
                    val out = uid.copyOf(len + 3)
                    System.arraycopy(part, 1, out, len, 3)
                    return out
                } else {
                    val out = uid.copyOf(len + 4)
                    System.arraycopy(part, 0, out, len, 4)
                    return out
                }
            } else {
                val out = IntArray(len + 3)
                System.arraycopy(uid, 0, out, 0, len)
                System.arraycopy(part, 1, out, len, 3)
                uid = out
                len += 3
            }
        }
        return null
    }

    protected open fun haltCard() {
        val buf = IntArray(4)
        buf[0] = PICC_HLTA
        buf[1] = 0x00
        writeReg(REG_TX_MODE, 0x80)
        writeReg(REG_RX_MODE, 0x80)
        val crc = calcCrc(intArrayOf(buf[0], buf[1]))
        buf[2] = crc[0]
        buf[3] = crc[1]
        sleep(1)
        cardCommand(CMD_TRANSCEIVE, IRQ_RX or IRQ_IDLE, buf)
        writeReg(REG_TX_MODE, 0x00)
        writeReg(REG_RX_MODE, 0x00)
    }

    companion object {
        const val BUS_I2C  = 0
        const val BUS_SPI  = 1
        const val BUS_UART = 2

        // Command set
        protected const val CMD_IDLE            = 0x00
        protected const val CMD_RANDOM_ID       = 0x02
        protected const val CMD_CALC_CRC        = 0x03
        protected const val CMD_TRANSCEIVE      = 0x0C
        protected const val CMD_MFAUTHENT       = 0x0E
        protected const val CMD_SOFT_RESET      = 0x0F

        // Registers
        protected const val REG_COMMAND        = 0x01
        protected const val REG_COM_IRQ        = 0x04
        protected const val REG_DIV_IRQ        = 0x05
        protected const val REG_ERROR          = 0x06
        protected const val REG_STATUS_2       = 0x08
        protected const val REG_FIFO_DATA      = 0x09
        protected const val REG_FIFO_LEVEL     = 0x0A
        protected const val REG_BIT_FRAMING    = 0x0D
        protected const val REG_TX_MODE        = 0x12
        protected const val REG_RX_MODE        = 0x13
        protected const val REG_TX_CONTROL     = 0x14
        protected const val REG_TX_ASK         = 0x15
        protected const val REG_MODE           = 0x11
        protected const val REG_CRC_RESULT_H   = 0x21
        protected const val REG_CRC_RESULT_L   = 0x22
        protected const val REG_RF_CFG         = 0x26
        protected const val REG_T_MODE         = 0x2A
        protected const val REG_T_PRESCALER    = 0x2B
        protected const val REG_T_RELOAD_H     = 0x2C
        protected const val REG_T_RELOAD_L     = 0x2D
        protected const val REG_AUTO_TEST      = 0x36
        protected const val REG_VERSION        = 0x37

        protected const val IRQ_RX    = 0x30
        protected const val IRQ_IDLE  = 0x10
        protected const val IRQ_ALL   = 0x7F

        protected const val FIFO_FLUSH = 0x80
        protected const val STATUS_2_CRYPTO1ON = 0x08

        protected const val PICC_REQA  = 0x26
        protected const val PICC_WUPA  = 0x52
        protected const val PICC_HLTA  = 0x50
        protected const val PICC_CT    = 0x88
        protected const val PICC_SEL_BIT = 0x70
        protected const val PICC_SAK_NOT_COMPLETE = 0x04
    }
}
