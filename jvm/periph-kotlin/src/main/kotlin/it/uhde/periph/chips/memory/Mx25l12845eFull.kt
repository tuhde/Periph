package it.uhde.periph.chips.memory

import it.uhde.periph.connection.Connection
import it.uhde.periph.storage.BlockDevice
import it.uhde.periph.storage.BlockDeviceException
import it.uhde.periph.storage.BlockDeviceException.Kind
import it.uhde.periph.storage.BlockInfo
import java.io.IOException

/**
 * Macronix MX25L12845E — full driver: raw program / erase commands, status and block protection,
 * deep power-down, SFDP and the secured OTP; implements [BlockDevice] (512-byte blocks, 4 KiB
 * erase units, `needsErase() == true`).
 *
 * @param connection SPI connection
 * @param autoErase [write] erases sectors itself when a 0->1 transition is needed; otherwise it
 *   throws `NOT_ERASED`
 * @param initTimeoutMs see [Mx25l12845eMinimal]
 */
open class Mx25l12845eFull @Throws(IOException::class) @JvmOverloads constructor(
    connection: Connection,
    private val autoErase: Boolean = false,
    initTimeoutMs: Long = T_CE_MS
) : Mx25l12845eMinimal(connection, initTimeoutMs), BlockDevice {

    private companion object {
        const val BLOCK_SIZE = 512
    }

    private fun outOfRange(what: String) = BlockDeviceException(Kind.OUT_OF_RANGE, what)

    // ---- raw operations --------------------------------------------------

    /** FAST_READ (0Bh) of [length] bytes. */
    @Throws(IOException::class)
    fun fastRead(address: Int, length: Int): ByteArray {
        if (address < 0 || length < 0 || address.toLong() + length > SIZE) throw outOfRange("read beyond end")
        return readRaw(FAST_READ, address, true, length)
    }

    /** One PP of 1..256 bytes that must not cross a 256-byte page boundary; the bytes must be erased. */
    @Throws(IOException::class)
    fun pageProgram(address: Int, data: ByteArray) {
        val n = data.size
        if (n == 0 || n > PAGE_SIZE || address < 0 || address.toLong() + n > SIZE) throw outOfRange("bad page program range")
        if ((address and (PAGE_SIZE - 1)) + n > PAGE_SIZE) throw outOfRange("page program would wrap inside the page")
        program(address, data, 0, n)
    }

    /** Erase the 32 KiB block containing [address] (BE32K, 52h). */
    @Throws(IOException::class)
    fun eraseBlock32k(address: Int) {
        if (address < 0 || address >= SIZE) throw outOfRange("address beyond end")
        eraseCmd(BE32K, address, T_BE_MS)
    }

    /** Erase the 64 KiB block containing [address] (BE, D8h). */
    @Throws(IOException::class)
    fun eraseBlock64k(address: Int) {
        if (address < 0 || address >= SIZE) throw outOfRange("address beyond end")
        eraseCmd(BE64K, address, T_BE_MS)
    }

    /** Erase the whole chip (CE, C7h); 80 s typical, 200 s max; needs BP3:0 == 0. */
    @Throws(IOException::class)
    fun chipErase() {
        wren()
        sendCommand(CE)
        waitReady(T_CE_MS)
        checkFail()
    }

    // ---- status / protection ---------------------------------------------

    /** The status register. */
    @Throws(IOException::class)
    fun readStatus(): Int = status()

    /** WREN + WRSR; waits for tW. */
    @Throws(IOException::class)
    fun writeStatus(value: Int) {
        wren()
        connection.write(byteArrayOf(WRSR.toByte(), value.toByte()))
        waitReady(T_WRSR_MS)
    }

    /** BP3:0 (0..15) from the status register. */
    @Throws(IOException::class)
    fun protection(): Int = (status() and SR_BP_MASK) shr SR_BP_SHIFT

    /** Set BP3:0 (non-volatile); QE and SRWD are preserved. */
    @Throws(IOException::class)
    fun setProtection(bp: Int) {
        if (bp < 0 || bp > 15) throw outOfRange("BP must be 0..15")
        writeStatus((status() and SR_BP_MASK.inv()) or (bp shl SR_BP_SHIFT))
    }

    /** True while a program, erase or WRSR is running (WIP). */
    @Throws(IOException::class)
    fun isBusy(): Boolean = (status() and SR_WIP) != 0

    /** Wait until WIP = 0; throws `TIMEOUT`. */
    @Throws(IOException::class)
    fun waitUntilReady(timeoutMs: Long) = waitReady(timeoutMs)

    /** The security register (RDSCUR). */
    @Throws(IOException::class)
    fun readSecurity(): Int = connection.writeRead(byteArrayOf(RDSCUR.toByte()), 1)[0].toInt() and 0xFF

    /** CLSR: clear P_FAIL / E_FAIL. */
    @Throws(IOException::class)
    fun clearFailFlags() = sendCommand(CLSR)

    // ---- power / identification ------------------------------------------

    /** DP: enter deep power-down (only RDP / RES are accepted afterwards). */
    @Throws(IOException::class)
    fun deepPowerDown() = sendCommand(DP)

    /** RDP: leave deep power-down and wait tRES2. */
    @Throws(IOException::class)
    fun releaseDeepPowerDown() {
        sendCommand(RDP)
        sleepMs(1)
    }

    /** The RES electronic ID (17h). */
    @Throws(IOException::class)
    fun electronicId(): Int = connection.writeRead(byteArrayOf(RES.toByte(), 0, 0, 0), 1)[0].toInt() and 0xFF

    /** REMS: `[manufacturer, device]` = `[0xC2, 0x17]`. */
    @Throws(IOException::class)
    fun deviceIds(): IntArray {
        val r = connection.writeRead(byteArrayOf(REMS.toByte(), 0, 0, 0), 2)
        return intArrayOf(r[0].toInt() and 0xFF, r[1].toInt() and 0xFF)
    }

    /** RDSFDP (5Ah): [length] bytes of the SFDP table from [address]. */
    @Throws(IOException::class)
    fun readSfdp(address: Int, length: Int): ByteArray = readRaw(RDSFDP, address, true, length)

    // ---- secured OTP (4 Kbit) --------------------------------------------

    /** Read [length] bytes at OTP offset [offset] (0..511). */
    @Throws(IOException::class)
    fun readOtp(offset: Int, length: Int): ByteArray {
        if (offset < 0 || length < 0 || offset + length > 512) throw outOfRange("OTP range")
        sendCommand(ENSO)
        try {
            return readRaw(READ, offset, false, length)
        } finally {
            sendCommand(EXSO)
        }
    }

    /** Program OTP bytes at [offset] (16..511 only: 0..15 are the ESN area); one-time programmable. */
    @Throws(IOException::class)
    fun programOtp(offset: Int, data: ByteArray) {
        if (offset < 16 || data.isEmpty() || offset + data.size > 512) throw outOfRange("OTP range (0..15 is the ESN area)")
        sendCommand(ENSO)
        try {
            var off = offset
            var pos = 0
            while (pos < data.size) {
                val n = minOf(PAGE_SIZE - (off and (PAGE_SIZE - 1)), data.size - pos)
                program(off, data, pos, n)
                off += n
                pos += n
            }
        } finally {
            sendCommand(EXSO)
        }
    }

    /** Lock the customer OTP area for good (WRSCUR, sets LDSO) — IRREVERSIBLE. */
    @Throws(IOException::class)
    fun lockOtp() {
        wren()
        sendCommand(WRSCUR)
        waitReady(T_WRSR_MS)
    }

    /** OTP 000h-00Fh (the factory ESN when `readSecurity() and 1`; blank 0xFF otherwise). */
    @Throws(IOException::class)
    fun serialNumber(): ByteArray = readOtp(0, 16)

    // ---- BlockDevice -----------------------------------------------------

    override fun blockSize(): Int = BLOCK_SIZE
    override fun blockCount(): Long = (SIZE / BLOCK_SIZE).toLong()
    override fun eraseSize(): Int = SECTOR_SIZE
    override fun needsErase(): Boolean = true

    /** Read [count] blocks of 512 bytes. */
    @Throws(IOException::class)
    override fun read(block: Long, count: Int): ByteArray {
        if (block < 0 || count < 0 || block + count > blockCount()) throw outOfRange("read beyond end")
        return readRaw(READ, block.toInt() * BLOCK_SIZE, false, count * BLOCK_SIZE)
    }

    /**
     * Program `data.size / 512` blocks. With auto-erase off a block that needs a 0->1 transition
     * throws `NOT_ERASED` (nothing is erased).
     */
    @Throws(IOException::class)
    override fun write(block: Long, data: ByteArray) {
        val count = (data.size / BLOCK_SIZE).toLong()
        if (count == 0L || data.size % BLOCK_SIZE != 0 || block < 0 || block + count > blockCount()) throw outOfRange("write range")
        var address = block.toInt() * BLOCK_SIZE
        if (autoErase) {
            writeBytes(address, data)
            return
        }
        var pos = 0
        while (pos < data.size) {   // program page by page, only where no 0 -> 1 transition is needed
            val n = minOf(PAGE_SIZE - (address and (PAGE_SIZE - 1)), data.size - pos)
            val old = readRaw(READ, address, false, n)
            for (i in 0 until n) {
                val o = old[i].toInt() and 0xFF
                val d = data[pos + i].toInt() and 0xFF
                if ((o and d) != d) throw BlockDeviceException(Kind.NOT_ERASED, "block needs an erase before it can be programmed")
            }
            if (!old.contentEquals(data.copyOfRange(pos, pos + n))) program(address, data, pos, n)
            address += n
            pos += n
        }
    }

    /** Erase [count] blocks; the range must be 4 KiB-aligned (multiples of 8 blocks). */
    @Throws(IOException::class)
    override fun erase(block: Long, count: Int) {
        if (block % 8 != 0L || count % 8 != 0 || block < 0 || count < 0 || block + count > blockCount()) {
            throw outOfRange("erase range must be 4 KiB aligned")
        }
        var address = block.toInt() * BLOCK_SIZE
        var left = count * BLOCK_SIZE
        while (left > 0) {
            val step: Int
            if ((address and 0xFFFF) == 0 && left >= 65536) {
                eraseBlock64k(address)
                step = 65536
            } else if ((address and 0x7FFF) == 0 && left >= 32768) {
                eraseBlock32k(address)
                step = 32768
            } else {
                eraseSector(address)
                step = SECTOR_SIZE
            }
            address += step
            left -= step
        }
    }

    /** Wait until the chip is idle. */
    @Throws(IOException::class)
    override fun sync() = waitReady(T_CE_MS)

    /** Geometry and capabilities; `readOnly` reflects BP3:0 >= 8. */
    override fun info(): BlockInfo {
        val readOnly = try { protection() >= 8 } catch (e: IOException) { false }
        return BlockInfo(BLOCK_SIZE, blockCount(), SECTOR_SIZE, true, readOnly, false, "MX25L12845E")
    }
}
