package it.uhde.periph.chips.memory

import it.uhde.periph.connection.SPIExtensions
import it.uhde.periph.storage.BlockDevice
import it.uhde.periph.storage.BlockDeviceException
import it.uhde.periph.storage.BlockDeviceException.Kind
import it.uhde.periph.storage.BlockInfo
import java.io.IOException

/**
 * SD card in SPI mode (full driver) — extends [SdSpiMinimal] with multi-block transfers
 * (CMD18/CMD25), erase, CID/CSD/OCR access and CRC checking (on by default), and implements
 * [BlockDevice].
 *
 * @param spi SPI connection with the optional extensions
 * @param speedHz data-phase clock after init
 * @param crc CMD59 and CRC16 checking
 */
open class SdSpiFull @Throws(IOException::class) @JvmOverloads constructor(
    spi: SPIExtensions,
    speedHz: Int = 12_000_000,
    crc: Boolean = true
) : SdSpiMinimal(spi, speedHz, crc), BlockDevice {

    private var name: String = try {
        String(readRegister(10), 3, 5, Charsets.ISO_8859_1)   // PNM = CID[103:64]
    } catch (e: IOException) {
        ""
    }

    override fun blockSize(): Int = BLOCK_SIZE
    override fun blockCount(): Long = blockCountValue
    override fun eraseSize(): Int = BLOCK_SIZE
    override fun needsErase(): Boolean = false

    private fun checkRange(block: Long, count: Long) {
        if (block < 0 || block + count > blockCountValue) throw BlockDeviceException(Kind.OUT_OF_RANGE, "blocks $block..${block + count}")
    }

    /** Read [count] consecutive blocks (CMD18 for `count > 1`). */
    @Throws(IOException::class)
    override fun read(block: Long, count: Int): ByteArray {
        if (count < 1) return ByteArray(0)
        checkRange(block, count.toLong())
        if (count == 1) return readBlock(block)
        val out = ByteArray(count * BLOCK_SIZE)
        spi.select()
        try {
            if (command(18, address(block)) != 0) throw BlockDeviceException(Kind.NOT_READY, "CMD18 failed")
            try {
                for (i in 0 until count) readData(out, i * BLOCK_SIZE, BLOCK_SIZE)
            } finally {
                command(12, 0)   // stop transmission, also after an error
                waitReady(BUSY_TIMEOUT_MS)
            }
        } finally {
            release()
        }
        return out
    }

    /** Program `data.size / 512` consecutive blocks (CMD25 for more than one). */
    @Throws(IOException::class)
    override fun write(block: Long, data: ByteArray) {
        val count = data.size / BLOCK_SIZE
        require(count >= 1 && data.size % BLOCK_SIZE == 0) { "data must be a multiple of 512 bytes" }
        checkRange(block, count.toLong())
        if (count == 1) {
            writeBlock(block, data)
            return
        }
        spi.select()
        try {
            appCommand(23, count)   // pre-erase hint; cards may ignore it
            if (command(25, address(block)) != 0) throw BlockDeviceException(Kind.NOT_READY, "CMD25 failed")
            var err: IOException? = null
            try {
                for (i in 0 until count) writeData(0xFC, data, i * BLOCK_SIZE)
            } catch (e: IOException) {
                err = e
            }
            waitReady(BUSY_TIMEOUT_MS)
            writeData(0xFD, null, 0)
            rx()   // stuff byte after the stop token
            waitReady(BUSY_TIMEOUT_MS)
            if (err != null) throw err
        } finally {
            release()
        }
    }

    /** Erase [count] blocks (CMD32/33/38); contents afterwards are card dependent. */
    @Throws(IOException::class)
    override fun erase(block: Long, count: Int) {
        if (count < 1) return
        checkRange(block, count.toLong())
        spi.select()
        try {
            if (command(32, address(block)) != 0 || command(33, address(block + count - 1)) != 0 || command(38, 0) != 0) {
                throw BlockDeviceException(Kind.NOT_READY, "erase command failed")
            }
            waitReady(ERASE_TIMEOUT_MS)
        } finally {
            release()
        }
    }

    /** Wait until the card has finished programming. */
    @Throws(IOException::class)
    override fun sync() {
        spi.select()
        try {
            waitReady(BUSY_TIMEOUT_MS)
        } finally {
            release()
        }
    }

    override fun info(): BlockInfo = BlockInfo(BLOCK_SIZE, blockCountValue, BLOCK_SIZE, false, false, false, name)

    /** The 16-byte CID register (manufacturer, name, revision, serial, date). */
    @Throws(IOException::class)
    fun cid(): ByteArray = readRegister(10)

    /** The 16-byte CSD register as read during init. */
    fun csd(): ByteArray = csdValue.clone()

    /** The OCR word (CMD58). */
    @Throws(IOException::class)
    fun ocr(): Int {
        spi.select()
        try {
            if (command(58, 0) != 0) throw BlockDeviceException(Kind.NOT_READY, "CMD58 failed")
            val o = ByteArray(4)
            spi.readInto(o)
            return ((o[0].toInt() and 0xFF) shl 24) or ((o[1].toInt() and 0xFF) shl 16) or
                ((o[2].toInt() and 0xFF) shl 8) or (o[3].toInt() and 0xFF)
        } finally {
            release()
        }
    }

    /** Enable/disable CMD59 and CRC16 checking. */
    @Throws(IOException::class)
    fun setCrc(enabled: Boolean) {
        spi.select()
        try {
            if (command(59, if (enabled) 1 else 0) != 0) throw BlockDeviceException(Kind.NOT_READY, "CMD59 failed")
        } finally {
            release()
        }
        crcOn = enabled
    }

    /** Change the SCK frequency after init. */
    @Throws(IOException::class)
    fun setSpeed(hz: Int) = spi.setSpeed(hz)

    /** True if a card responds to CMD13. */
    @Throws(IOException::class)
    fun isPresent(): Boolean {
        spi.select()
        try {
            val r = command(13, 0)
            if (r != 0xFF) rx()   // R2 second byte
            return r != 0xFF
        } finally {
            release()
        }
    }
}
