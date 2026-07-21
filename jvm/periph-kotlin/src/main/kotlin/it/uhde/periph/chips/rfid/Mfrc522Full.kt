package it.uhde.periph.chips.rfid

import java.io.IOException

/**
 * MFRC522 — 13.56 MHz contactless reader/writer (NXP). Full driver.
 *
 * Extends [Mfrc522Minimal] with configuration, antenna control, self test,
 * MIFARE Classic authenticated read/write/value operations, and MIFARE
 * Ultralight / NTAG page read/write.
 */
class Mfrc522Full @JvmOverloads constructor(
    transport: Transport,
    busType: Int = Mfrc522Minimal.BUS_SPI
) : Mfrc522Minimal(transport, busType) {

    /** Re-run SoftReset and the full initialization sequence. */
    fun reset() { initChip() }

    /** Enable the antenna driver (TX1 + TX2). */
    fun antennaOn() { setBits(REG_TX_CONTROL, 0x03) }

    /** Disable the antenna driver (TX1 + TX2). */
    fun antennaOff() { clearBits(REG_TX_CONTROL, 0x03) }

    /**
     * Set the receiver gain.
     *
     * @param dB one of 18, 23, 33, 38, 43, 48.
     */
    fun setAntennaGain(dB: Int) {
        val gain = when (dB) {
            18 -> RX_GAIN_18_DB
            23 -> RX_GAIN_23_DB
            33 -> RX_GAIN_33_DB
            38 -> RX_GAIN_38_DB
            43 -> RX_GAIN_43_DB
            48 -> RX_GAIN_48_DB
            else -> return
        }
        val cur = readReg(REG_RF_CFG) and 0x8F
        writeReg(REG_RF_CFG, cur or gain)
    }

    /**
     * Read the currently configured receiver gain.
     * @return gain in dB (one of 18, 23, 33, 38, 43, 48), or 0 if unknown.
     */
    fun antennaGain(): Int {
        val cur = readReg(REG_RF_CFG) and 0x70
        return when (cur) {
            RX_GAIN_18_DB -> 18
            RX_GAIN_23_DB -> 23
            RX_GAIN_33_DB -> 33
            RX_GAIN_38_DB -> 38
            RX_GAIN_43_DB -> 43
            RX_GAIN_48_DB -> 48
            else -> 0
        }
    }

    /**
     * Read the version register and decode it.
     *
     * @return two-element array: {chipType, version}. For MFRC522, chipType=0x09.
     */
    fun version(): IntArray {
        val raw = readReg(REG_VERSION)
        return intArrayOf((raw shr 4) and 0x0F, raw and 0x0F)
    }

    /**
     * Run the datasheet-defined digital self test.
     *
     * @return true if all 64 FIFO bytes match the version-specific reference.
     */
    fun selfTest(): Boolean {
        val refV10 = intArrayOf(
            0x00, 0x87, 0x98, 0x0F, 0x49, 0xFF, 0x07, 0x19,
            0xBF, 0x22, 0x30, 0x49, 0x59, 0x63, 0xAD, 0xCA,
            0x7F, 0xE3, 0x4E, 0x03, 0x5C, 0x4E, 0x49, 0x50,
            0x47, 0x9A, 0x37, 0x61, 0xE7, 0xE2, 0xC6, 0x2E,
            0x75, 0x5A, 0xED, 0x04, 0x3D, 0x02, 0x4B, 0x78,
            0x32, 0xFF, 0x58, 0x3B, 0x7C, 0xE9, 0x00, 0x94,
            0xB4, 0x4A, 0x59, 0x5B, 0xFD, 0xC9, 0x29, 0xDF,
            0x35, 0x96, 0x98, 0x9E, 0x4F, 0x30, 0x32, 0x8D
        )
        val refV20 = intArrayOf(
            0x00, 0xEB, 0x66, 0xBA, 0x57, 0xBF, 0x23, 0x95,
            0xD0, 0xE3, 0x0D, 0x3D, 0x27, 0x89, 0x5C, 0xDE,
            0x9D, 0x3B, 0xA7, 0x00, 0x21, 0x5B, 0x89, 0x82,
            0x51, 0x3A, 0xEB, 0x02, 0x0C, 0xA5, 0x00, 0x49,
            0x7C, 0x84, 0x4D, 0xB3, 0xCC, 0xD2, 0x1B, 0x81,
            0x5D, 0x48, 0x76, 0xD5, 0x71, 0x61, 0x21, 0xA9,
            0x86, 0x96, 0x83, 0x38, 0xCF, 0x9D, 0x5B, 0x6D,
            0xDC, 0x15, 0xBA, 0x3E, 0x7D, 0x95, 0x3B, 0x2F
        )
        val v = version()
        val ref = if (v[1] == 1) refV10 else refV20
        writeReg(REG_AUTO_TEST, 0x09)
        writeReg(REG_FIFO_LEVEL, FIFO_FLUSH)
        writeReg(REG_COMMAND, CMD_IDLE)
        for (i in 0 until 255) {
            if (readReg(REG_FIFO_LEVEL) >= 64) break
            writeReg(REG_COMMAND, CMD_CALC_CRC)
            sleep(1)
        }
        writeReg(REG_AUTO_TEST, 0x00)
        writeReg(REG_COMMAND, CMD_SOFT_RESET)
        sleep(50)
        initChip()
        val got = readFifo(64)
        for (i in 0 until 64) {
            if (got[i] != ref[i]) return false
        }
        return true
    }

    /** WUPA — wake a HALTed card. Same as isCardPresent but with WUPA. */
    fun wakeupCard(): Boolean {
        writeReg(REG_BIT_FRAMING, 0x07)
        writeReg(REG_TX_MODE, 0x00)
        writeReg(REG_RX_MODE, 0x00)
        val back = transceive(intArrayOf(PICC_WUPA))
        return back != null && back.size == 2
    }

    /**
     * Run anticollision / Select only — leaves the card active for further ops.
     */
    fun selectCard(): ByteArray? {
        if (!wakeupCard()) return null
        val uidArr = selectCardInternal() ?: return null
        return ByteArray(uidArr.size) { i -> uidArr[i].toByte() }
    }

    /** Send HLTA — put the currently selected card into HALT state. */
    fun haltCard() { super.haltCard() }

    /**
     * Run MIFARE Classic Crypto1 authentication.
     */
    fun authenticate(blockAddress: Int, keyType: Int, key: ByteArray, uid: ByteArray): Boolean {
        if (key.size != 6 || uid.size != 4) return false
        val buf = IntArray(12)
        buf[0] = keyType
        buf[1] = blockAddress and 0xFF
        for (i in 0 until 6) buf[2 + i] = key[i].toInt() and 0xFF
        for (i in 0 until 4) buf[8 + i] = uid[i].toInt() and 0xFF
        writeReg(REG_COM_IRQ, IRQ_ALL)
        writeReg(REG_STATUS_2, 0x00)
        flushFifo()
        writeFifo(buf)
        writeReg(REG_COMMAND, CMD_MFAUTHENT)
        for (i in 0 until 200) {
            if ((readReg(REG_STATUS_2) and STATUS_2_CRYPTO1ON) != 0) return true
            sleep(1)
        }
        return false
    }

    /** Clear Status2Reg.MFCrypto1On. */
    fun stopCrypto() { clearBits(REG_STATUS_2, STATUS_2_CRYPTO1ON) }

    /**
     * Read a 16-byte MIFARE Classic block.
     */
    fun readBlock(blockAddress: Int): ByteArray? {
        val cmd = intArrayOf(0x30, blockAddress and 0xFF)
        writeReg(REG_TX_MODE, 0x80)
        writeReg(REG_RX_MODE, 0x80)
        val crc = calcCrc(cmd)
        val full = intArrayOf(cmd[0], cmd[1], crc[0], crc[1])
        val back = transceive(full)
        writeReg(REG_TX_MODE, 0x00)
        writeReg(REG_RX_MODE, 0x00)
        if (back == null || back.size != 16) return null
        return ByteArray(16) { i -> back[i].toByte() }
    }

    /**
     * Write a 16-byte MIFARE Classic block.
     */
    fun writeBlock(blockAddress: Int, data: ByteArray): Boolean {
        if (data.size != 16) return false
        val c = intArrayOf(0xA0, blockAddress and 0xFF)
        writeReg(REG_TX_MODE, 0x80)
        writeReg(REG_RX_MODE, 0x80)
        val crc = calcCrc(c)
        val full = intArrayOf(c[0], c[1], crc[0], crc[1])
        val back = transceive(full)
        if (back == null || back.isEmpty() || (back[0] and 0x0F) != 0x0A) {
            writeReg(REG_TX_MODE, 0x00)
            writeReg(REG_RX_MODE, 0x00)
            return false
        }
        val dataArr = IntArray(16) { data[it].toInt() and 0xFF }
        val crc2 = calcCrc(dataArr)
        val buf = IntArray(18)
        System.arraycopy(dataArr, 0, buf, 0, 16)
        buf[16] = crc2[0]
        buf[17] = crc2[1]
        val back2 = transceive(buf)
        writeReg(REG_TX_MODE, 0x00)
        writeReg(REG_RX_MODE, 0x00)
        return back2 != null && back2.isNotEmpty() && (back2[0] and 0x0F) == 0x0A
    }

    private fun valueOp(cmd: Int, blockAddress: Int, delta: Long, dummy: Boolean): Boolean {
        val c = intArrayOf(cmd, blockAddress and 0xFF)
        writeReg(REG_TX_MODE, 0x80)
        writeReg(REG_RX_MODE, 0x80)
        val crc = calcCrc(c)
        val full = intArrayOf(c[0], c[1], crc[0], crc[1])
        val back = transceive(full)
        if (back == null || back.isEmpty() || (back[0] and 0x0F) != 0x0A) {
            writeReg(REG_TX_MODE, 0x00)
            writeReg(REG_RX_MODE, 0x00)
            return false
        }
        val data = IntArray(4)
        if (dummy) {
            for (i in 0 until 4) data[i] = 0
        } else {
            data[0] = (delta and 0xFF).toInt()
            data[1] = ((delta shr 8) and 0xFF).toInt()
            data[2] = ((delta shr 16) and 0xFF).toInt()
            data[3] = ((delta shr 24) and 0xFF).toInt()
        }
        val crc2 = calcCrc(data)
        val buf = intArrayOf(data[0], data[1], data[2], data[3], crc2[0], crc2[1])
        val back2 = transceive(buf)
        writeReg(REG_TX_MODE, 0x00)
        writeReg(REG_RX_MODE, 0x00)
        return back2 != null && back2.isNotEmpty() && (back2[0] and 0x0F) == 0x0A
    }

    private fun transfer(blockAddress: Int): Boolean {
        val c = intArrayOf(0xB0, blockAddress and 0xFF)
        writeReg(REG_TX_MODE, 0x80)
        writeReg(REG_RX_MODE, 0x80)
        val crc = calcCrc(c)
        val full = intArrayOf(c[0], c[1], crc[0], crc[1])
        val back = transceive(full)
        writeReg(REG_TX_MODE, 0x00)
        writeReg(REG_RX_MODE, 0x00)
        return back != null && back.isNotEmpty() && (back[0] and 0x0F) == 0x0A
    }

    /** Increment the value block at blockAddress by delta and transfer it back. */
    fun incrementValue(blockAddress: Int, delta: Long): Boolean {
        if (!valueOp(0xC1, blockAddress, delta, false)) return false
        return transfer(blockAddress)
    }

    /** Decrement the value block at blockAddress by delta and transfer it back. */
    fun decrementValue(blockAddress: Int, delta: Long): Boolean {
        if (!valueOp(0xC0, blockAddress, delta, false)) return false
        return transfer(blockAddress)
    }

    /** Restore (re-read) the value block at blockAddress into the internal data register. */
    fun restoreValue(blockAddress: Int): Boolean {
        if (!valueOp(0xC2, blockAddress, 0, true)) return false
        return transfer(blockAddress)
    }

    /** Commit the internal data register to destinationBlock. */
    fun transferValue(destinationBlock: Int): Boolean = transfer(destinationBlock)

    /**
     * Read 4 consecutive pages (16 bytes) starting at pageAddress.
     */
    fun readUltralightPage(pageAddress: Int): ByteArray? {
        val cmd = intArrayOf(0x30, pageAddress and 0xFF)
        writeReg(REG_TX_MODE, 0x80)
        writeReg(REG_RX_MODE, 0x80)
        val crc = calcCrc(cmd)
        val full = intArrayOf(cmd[0], cmd[1], crc[0], crc[1])
        val back = transceive(full)
        writeReg(REG_TX_MODE, 0x00)
        writeReg(REG_RX_MODE, 0x00)
        if (back == null || back.size != 16) return null
        return ByteArray(16) { i -> back[i].toByte() }
    }

    /**
     * Write a 4-byte page (MIFARE Ultralight / NTAG).
     */
    fun writeUltralightPage(pageAddress: Int, data: ByteArray): Boolean {
        if (data.size != 4) return false
        val buf = IntArray(8)
        buf[0] = 0xA2
        buf[1] = pageAddress and 0xFF
        for (i in 0 until 4) buf[2 + i] = data[i].toInt() and 0xFF
        writeReg(REG_TX_MODE, 0x80)
        writeReg(REG_RX_MODE, 0x80)
        val crc = calcCrc(intArrayOf(buf[0], buf[1], buf[2], buf[3], buf[4], buf[5]))
        buf[6] = crc[0]
        buf[7] = crc[1]
        val back = transceive(buf)
        writeReg(REG_TX_MODE, 0x00)
        writeReg(REG_RX_MODE, 0x00)
        return back != null && back.isNotEmpty() && (back[0] and 0x0F) == 0x0A
    }

    /** Run the Generate RandomID command and return the 10-byte ID. */
    fun generateRandomId(): ByteArray {
        writeReg(REG_COMMAND, CMD_IDLE)
        writeReg(REG_COM_IRQ, IRQ_ALL)
        writeReg(REG_DIV_IRQ, 0x14)
        writeReg(REG_COMMAND, CMD_RANDOM_ID)
        for (i in 0 until 50) {
            if ((readReg(REG_COM_IRQ) and 0x10) != 0) break
            sleep(1)
        }
        writeReg(REG_COMMAND, CMD_IDLE)
        val got = readFifo(10)
        return ByteArray(10) { i -> got[i].toByte() }
    }

    companion object {
        const val KEY_A = 0x60.toInt()
        const val KEY_B = 0x61.toInt()

        const val RX_GAIN_18_DB = 0x00
        const val RX_GAIN_23_DB = 0x10
        const val RX_GAIN_33_DB = 0x40
        const val RX_GAIN_38_DB = 0x50
        const val RX_GAIN_43_DB = 0x60
        const val RX_GAIN_48_DB = 0x70
    }
}
