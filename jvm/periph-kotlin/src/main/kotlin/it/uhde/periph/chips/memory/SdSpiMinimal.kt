package it.uhde.periph.chips.memory

import it.uhde.periph.connection.SPIExtensions
import it.uhde.periph.storage.BlockDeviceException
import it.uhde.periph.storage.BlockDeviceException.Kind
import java.io.IOException

/**
 * SD / SDHC / SDXC / microSD card in SPI mode (minimal driver) — `specs/memory/sd_spi.md`.
 *
 * Runs the SPI-mode init sequence (CMD0, CMD8, ACMD41, CMD58, ...) at construction and
 * offers single 512-byte block reads and writes. Default configuration: init at 400 kHz,
 * then 12 MHz; CRC16 off.
 *
 * The connection must implement [SPIExtensions] (`idleClocks`, `setSpeed`,
 * `select`/`deselect`, `readInto`) and clock 0xFF on MOSI while reading. Cards are 3.3 V only.
 *
 * @param spi SPI connection with the optional extensions
 * @param speedHz data-phase clock after init (init always runs at 400 kHz)
 * @param crc enable CMD59 and send/verify CRC16 on data blocks
 * @throws BlockDeviceException `NOT_READY` if no (supported) card answers, `TIMEOUT` if it never leaves idle
 */
open class SdSpiMinimal @Throws(IOException::class) @JvmOverloads constructor(
    protected val spi: SPIExtensions,
    private val speedHz: Int = 12_000_000,
    private val crc: Boolean = false
) {
    /** Card generation detected during init. */
    enum class CardType { NONE, SD_V1, SD_V2, SDHC }

    companion object {
        /** Block size of an SD card in SPI mode. */
        const val BLOCK_SIZE = 512

        protected const val INIT_TIMEOUT_MS = 1000L
        protected const val TOKEN_TIMEOUT_MS = 200L
        protected const val BUSY_TIMEOUT_MS = 500L
        protected const val ERASE_TIMEOUT_MS = 30_000L

        private val CRC16_T = intArrayOf(
            0x0000, 0x1021, 0x2042, 0x3063, 0x4084, 0x50A5, 0x60C6, 0x70E7,
            0x8108, 0x9129, 0xA14A, 0xB16B, 0xC18C, 0xD1AD, 0xE1CE, 0xF1EF)

        /** CRC7 (polynomial x^7 + x^3 + 1) of the first [len] bytes, not shifted. */
        @JvmStatic
        fun crc7(data: ByteArray, len: Int): Int {
            var crc = 0
            for (i in 0 until len) {
                var b = data[i].toInt() and 0xFF
                repeat(8) {
                    crc = (crc shl 1) and 0xFF
                    if (((b xor crc) and 0x80) != 0) crc = crc xor 0x09
                    b = (b shl 1) and 0xFF
                }
            }
            return crc and 0x7F
        }

        /** CRC16-CCITT (polynomial 0x1021, initial value 0) of [len] bytes from [off]. */
        @JvmStatic
        fun crc16(data: ByteArray, off: Int, len: Int): Int {
            var crc = 0
            for (i in off until off + len) {
                crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
                crc = ((crc shl 4) and 0xFFFF) xor CRC16_T[crc shr 12]
                crc = ((crc shl 4) and 0xFFFF) xor CRC16_T[crc shr 12]
            }
            return crc
        }

        internal fun csdBlocks(csd: ByteArray): Long {
            if (((csd[0].toInt() and 0xFF) shr 6) == 1) {   // CSD v2: C_SIZE = CSD[69:48]
                val csize = ((csd[7].toInt() and 0x3F).toLong() shl 16) or
                    ((csd[8].toInt() and 0xFF).toLong() shl 8) or (csd[9].toInt() and 0xFF).toLong()
                return (csize + 1) * 1024
            }
            val readBlLen = csd[5].toInt() and 0x0F   // CSD v1
            val csize = ((csd[6].toInt() and 0x03).toLong() shl 10) or
                ((csd[7].toInt() and 0xFF).toLong() shl 2) or ((csd[8].toInt() and 0xFF) shr 6).toLong()
            val mult = ((csd[9].toInt() and 0x03) shl 1) or ((csd[10].toInt() and 0xFF) shr 7)
            return ((csize + 1) shl (mult + 2) shl readBlLen) / BLOCK_SIZE
        }

        private fun nowMs(): Long = System.nanoTime() / 1_000_000L
    }

    protected var crcOn = false
    protected var type = CardType.NONE
    protected var blockAddressing = false
    protected var blockCountValue = 0L
    protected var csdValue = ByteArray(16)
    private val one = ByteArray(1)

    init {
        begin()
    }

    // ---- bus primitives --------------------------------------------------

    @Throws(IOException::class)
    protected fun rx(): Int {
        spi.readInto(one)
        return one[0].toInt() and 0xFF
    }

    @Throws(IOException::class)
    protected fun waitReady(ms: Long) {
        val start = nowMs()
        while (true) {
            if (rx() == 0xFF) return
            if (nowMs() - start >= ms) throw BlockDeviceException(Kind.TIMEOUT, "card busy")
        }
    }

    @Throws(IOException::class)
    protected fun release() {
        spi.deselect()
        spi.idleClocks(8)   // let the card free MISO
    }

    /** Send a command with CS already selected; returns R1 (0xFF on no response). */
    @Throws(IOException::class)
    protected fun command(cmd: Int, arg: Int): Int {
        if (cmd != 12 && cmd != 0) waitReady(BUSY_TIMEOUT_MS)
        val frame = ByteArray(6)
        frame[0] = (0x40 or cmd).toByte()
        frame[1] = (arg ushr 24).toByte()
        frame[2] = (arg ushr 16).toByte()
        frame[3] = (arg ushr 8).toByte()
        frame[4] = arg.toByte()
        frame[5] = ((crc7(frame, 5) shl 1) or 1).toByte()
        spi.write(frame)
        if (cmd == 12) rx()   // discard the stuff byte that follows CMD12
        repeat(10) {   // Ncr <= 8 bytes
            val r = rx()
            if ((r and 0x80) == 0) return r
        }
        return 0xFF
    }

    @Throws(IOException::class)
    protected fun appCommand(cmd: Int, arg: Int): Int {
        val r = command(55, 0)
        if (r > 1) return r
        return command(cmd, arg)
    }

    @Throws(IOException::class)
    protected fun readData(buf: ByteArray, off: Int, len: Int) {
        val start = nowMs()
        var token = rx()
        while (token == 0xFF) {
            if (nowMs() - start >= TOKEN_TIMEOUT_MS) throw BlockDeviceException(Kind.TIMEOUT, "no data token")
            token = rx()
        }
        if (token != 0xFE) throw BlockDeviceException(Kind.NOT_READY, String.format("data error token 0x%02X", token))
        val tmp = ByteArray(len)
        spi.readInto(tmp)
        System.arraycopy(tmp, 0, buf, off, len)
        val c = ByteArray(2)
        spi.readInto(c)
        if (crcOn && (((c[0].toInt() and 0xFF) shl 8) or (c[1].toInt() and 0xFF)) != crc16(buf, off, len)) {
            throw BlockDeviceException(Kind.CRC, "data CRC16 mismatch")
        }
    }

    @Throws(IOException::class)
    protected fun writeData(token: Int, data: ByteArray?, off: Int) {
        waitReady(BUSY_TIMEOUT_MS)
        spi.write(byteArrayOf(token.toByte()))
        if (token == 0xFD) return   // stop-tran token has no payload
        val block = data!!.copyOfRange(off, off + BLOCK_SIZE)
        spi.write(block)
        if (crcOn) {
            val c = crc16(block, 0, BLOCK_SIZE)
            spi.write(byteArrayOf((c shr 8).toByte(), c.toByte()))
        } else {
            spi.write(byteArrayOf(0xFF.toByte(), 0xFF.toByte()))
        }
        val resp = rx() and 0x1F
        if (resp == 0x05) return
        if (resp == 0x0B) throw BlockDeviceException(Kind.CRC, "write CRC rejected")
        throw BlockDeviceException(Kind.NOT_READY, String.format("write rejected 0x%02X", resp))
    }

    @Throws(IOException::class)
    protected fun readRegister(cmd: Int): ByteArray {
        spi.select()
        try {
            if (command(cmd, 0) != 0) throw BlockDeviceException(Kind.NOT_READY, "CMD$cmd failed")
            val out = ByteArray(16)
            readData(out, 0, 16)
            return out
        } finally {
            release()
        }
    }

    protected fun address(block: Long): Int = (if (blockAddressing) block else block * BLOCK_SIZE).toInt()

    // ---- init --------------------------------------------------------------

    /**
     * Run the SPI-mode init sequence (also usable to re-init a swapped card).
     *
     * @throws BlockDeviceException `NOT_READY` if no (supported) card answers, `TIMEOUT` if it never leaves idle
     */
    @Throws(IOException::class)
    fun begin() {
        type = CardType.NONE
        blockCountValue = 0
        crcOn = false
        spi.setSpeed(400_000)
        spi.deselect()
        spi.idleClocks(80)

        var idle = false
        var tries = 0
        while (tries < 10 && !idle) {   // CMD0 with CS low enters SPI mode; a fresh card needs a few tries
            spi.select()
            try {
                idle = command(0, 0) == 0x01
            } finally {
                release()
            }
            tries++
        }
        if (!idle) throw BlockDeviceException(Kind.NOT_READY, "no card responded to CMD0")

        spi.select()
        try {
            var v2 = false
            val r = command(8, 0x1AA)
            if (r == 0x01) {
                val r7 = ByteArray(4)
                spi.readInto(r7)
                if ((r7[2].toInt() and 0x0F) != 0x01 || (r7[3].toInt() and 0xFF) != 0xAA) {
                    throw BlockDeviceException(Kind.NOT_READY, "CMD8 echo mismatch")
                }
                v2 = true
            } else if ((r and 0x04) == 0) {   // anything but "illegal command" (v1 card) is a failure
                throw BlockDeviceException(Kind.NOT_READY, "CMD8 failed")
            }

            val start = nowMs()
            while (appCommand(41, if (v2) 0x40000000 else 0) != 0x00) {
                if (nowMs() - start >= INIT_TIMEOUT_MS) throw BlockDeviceException(Kind.TIMEOUT, "ACMD41 never completed")
            }

            type = if (v2) CardType.SD_V2 else CardType.SD_V1
            blockAddressing = false
            if (v2 && command(58, 0) == 0x00) {
                val ocr = ByteArray(4)
                spi.readInto(ocr)
                if ((ocr[0].toInt() and 0x40) != 0) {
                    blockAddressing = true
                    type = CardType.SDHC
                }
            }
            if (!blockAddressing) command(16, BLOCK_SIZE)
            if (crc && command(59, 1) == 0x00) crcOn = true
        } catch (e: IOException) {
            type = CardType.NONE
            throw e
        } finally {
            release()
        }

        spi.setSpeed(speedHz)
        csdValue = readRegister(9)
        blockCountValue = csdBlocks(csdValue)
    }

    // ---- minimal API -------------------------------------------------------

    /** Capacity in 512-byte blocks (from the CSD). */
    open fun blockCount(): Long = blockCountValue

    /** Alias of [blockCount] (FatFs terminology). */
    fun sectorCount(): Long = blockCountValue

    /** The card generation. */
    fun cardType(): CardType = type

    /**
     * Read one 512-byte block (CMD17).
     *
     * @throws BlockDeviceException `OUT_OF_RANGE` if [block] is beyond the card
     */
    @Throws(IOException::class)
    fun readBlock(block: Long): ByteArray {
        if (block < 0 || block >= blockCountValue) throw BlockDeviceException(Kind.OUT_OF_RANGE, "block $block")
        spi.select()
        try {
            if (command(17, address(block)) != 0) throw BlockDeviceException(Kind.NOT_READY, "CMD17 failed")
            val out = ByteArray(BLOCK_SIZE)
            readData(out, 0, BLOCK_SIZE)
            return out
        } finally {
            release()
        }
    }

    /**
     * Write one 512-byte block (CMD24).
     *
     * @throws BlockDeviceException `OUT_OF_RANGE` if [block] is beyond the card
     */
    @Throws(IOException::class)
    fun writeBlock(block: Long, data: ByteArray) {
        require(data.size == BLOCK_SIZE) { "data must be 512 bytes" }
        if (block < 0 || block >= blockCountValue) throw BlockDeviceException(Kind.OUT_OF_RANGE, "block $block")
        spi.select()
        try {
            if (command(24, address(block)) != 0) throw BlockDeviceException(Kind.NOT_READY, "CMD24 failed")
            writeData(0xFE, data, 0)
            waitReady(BUSY_TIMEOUT_MS)
        } finally {
            release()
        }
    }
}
