package it.uhde.periph.chips.memory

import it.uhde.periph.connection.Connection
import it.uhde.periph.storage.BlockDeviceException
import it.uhde.periph.storage.BlockDeviceException.Kind
import java.io.IOException

/**
 * Macronix MX25L12845E 128-Mbit (16 MiB) serial NOR flash — minimal driver
 * (specs/memory/mx25l12845e.md).
 *
 * A byte-addressable store with transparent erase: [readBytes], an erase-aware [writeBytes]
 * (read-modify-erase-write per 4 KiB sector, programming only what changed) and [eraseSector].
 * Plain single-I/O SPI Mode 0 over a bare [Connection] (`write` and `writeRead`; CS is held low
 * for each frame by the connection).
 *
 * Construction runs the init sequence (RDP, RDID check C2 20 18, wait until not busy).
 *
 * @param connection SPI connection (`write`, `writeRead`)
 * @param initTimeoutMs how long init waits for a chip that is still busy (a reset during a chip
 *   erase can leave it busy for up to 200 s)
 * @throws BlockDeviceException `NOT_READY` if the JEDEC ID did not match, `TIMEOUT` if the chip stayed busy
 */
open class Mx25l12845eMinimal @Throws(IOException::class) @JvmOverloads constructor(
    protected val connection: Connection,
    initTimeoutMs: Long = T_CE_MS
) {
    companion object {
        /** Capacity in bytes. */
        const val SIZE = 16 * 1024 * 1024
        /** Page-program size in bytes. */
        const val PAGE_SIZE = 256
        /** Smallest erase unit in bytes. */
        const val SECTOR_SIZE = 4096

        const val WREN = 0x06
        const val RDID = 0x9F
        const val RES = 0xAB
        const val REMS = 0x90
        const val RDSR = 0x05
        const val WRSR = 0x01
        const val READ = 0x03
        const val FAST_READ = 0x0B
        const val PP = 0x02
        const val SE = 0x20
        const val BE32K = 0x52
        const val BE64K = 0xD8
        const val CE = 0xC7
        const val DP = 0xB9
        const val RDP = 0xAB
        const val RDSFDP = 0x5A
        const val ENSO = 0xB1
        const val EXSO = 0xC1
        const val RDSCUR = 0x2B
        const val WRSCUR = 0x2F
        const val CLSR = 0x30

        const val SR_WIP = 0x01
        const val SR_WEL = 0x02
        const val SR_BP_MASK = 0x3C
        const val SR_BP_SHIFT = 2
        const val SCUR_P_FAIL = 0x20
        const val SCUR_E_FAIL = 0x40

        const val T_WRSR_MS = 150L
        const val T_PP_MS = 10L
        const val T_SE_MS = 400L
        const val T_BE_MS = 3000L
        const val T_CE_MS = 250_000L
        private const val READ_CHUNK = 4096

        @JvmStatic
        protected fun sleepMs(ms: Long) {
            try {
                Thread.sleep(ms)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        private fun header(cmd: Int, address: Int): ByteArray =
            byteArrayOf(cmd.toByte(), (address shr 16).toByte(), (address shr 8).toByte(), address.toByte())
    }

    init {
        connection.write(byteArrayOf(RDP.toByte()))   // leave deep power-down if a previous session left it there
        sleepMs(1)                                    // tRES2 = 100 us
        val id = jedecId()
        if ((id[0].toInt() and 0xFF) != 0xC2 || (id[1].toInt() and 0xFF) != 0x20 || (id[2].toInt() and 0xFF) != 0x18) {
            throw BlockDeviceException(Kind.NOT_READY, String.format("MX25L12845E not found (JEDEC ID %02x%02x%02x)",
                id[0].toInt() and 0xFF, id[1].toInt() and 0xFF, id[2].toInt() and 0xFF))
        }
        waitReady(initTimeoutMs)                      // a reset during an erase can leave the chip busy
    }

    // ---- bus primitives --------------------------------------------------

    @Throws(IOException::class)
    protected fun status(): Int = connection.writeRead(byteArrayOf(RDSR.toByte()), 1)[0].toInt() and 0xFF

    @Throws(IOException::class)
    protected fun wren() {
        connection.write(byteArrayOf(WREN.toByte()))
        if ((status() and SR_WEL) == 0) throw BlockDeviceException(Kind.IO, "WEL not set after WREN")   // the next command would be ignored
    }

    /** Wait until WIP = 0; throws `TIMEOUT` after [timeoutMs]. */
    @Throws(IOException::class)
    protected fun waitReady(timeoutMs: Long) {
        val start = System.nanoTime()
        while ((status() and SR_WIP) != 0) {
            if ((System.nanoTime() - start) / 1_000_000L >= timeoutMs) throw BlockDeviceException(Kind.TIMEOUT, "chip busy")
            if (timeoutMs > 20) sleepMs(1)   // long operations: do not hammer the bus
        }
    }

    @Throws(IOException::class)
    protected fun checkFail() {
        val scur = connection.writeRead(byteArrayOf(RDSCUR.toByte()), 1)[0].toInt() and 0xFF
        if ((scur and (SCUR_P_FAIL or SCUR_E_FAIL)) != 0) {
            connection.write(byteArrayOf(CLSR.toByte()))
            if ((status() and SR_BP_MASK) != 0) throw BlockDeviceException(Kind.WRITE_PROTECTED, "program/erase hit the block-protected area")
            throw BlockDeviceException(Kind.IO, String.format("program/erase failed (security register 0x%02X)", scur))
        }
    }

    @Throws(IOException::class)
    protected fun eraseCmd(cmd: Int, address: Int, timeoutMs: Long) {
        wren()
        connection.write(header(cmd, address))
        waitReady(timeoutMs)
        checkFail()
    }

    @Throws(IOException::class)
    protected fun program(address: Int, data: ByteArray, off: Int, len: Int) {
        wren()
        val frame = ByteArray(4 + len)
        System.arraycopy(header(PP, address), 0, frame, 0, 4)
        System.arraycopy(data, off, frame, 4, len)
        connection.write(frame)
        waitReady(T_PP_MS)
        checkFail()
    }

    @Throws(IOException::class)
    protected fun sendCommand(cmd: Int) = connection.write(byteArrayOf(cmd.toByte()))

    @Throws(IOException::class)
    protected fun readRaw(cmd: Int, address: Int, dummy: Boolean, length: Int): ByteArray {
        val out = ByteArray(length)
        var done = 0
        while (done < length) {
            val n = minOf(READ_CHUNK, length - done)
            val h = header(cmd, address + done)
            val frame = if (dummy) h.copyOf(5) else h
            System.arraycopy(connection.writeRead(frame, n), 0, out, done, n)
            done += n
        }
        return out
    }

    // ---- Minimal API -----------------------------------------------------

    /** The three RDID bytes (manufacturer, memory type, density). */
    @Throws(IOException::class)
    fun jedecId(): ByteArray = connection.writeRead(byteArrayOf(RDID.toByte()), 3)

    /** Capacity in bytes (16 777 216). */
    fun size(): Int = SIZE

    /**
     * Read [length] bytes starting at [address] (READ, 03h), any length and alignment.
     *
     * @throws BlockDeviceException `OUT_OF_RANGE` if the range leaves the array
     */
    @Throws(IOException::class)
    fun readBytes(address: Int, length: Int): ByteArray {
        if (address < 0 || length < 0 || address.toLong() + length > SIZE) throw BlockDeviceException(Kind.OUT_OF_RANGE, "read beyond end")
        return readRaw(READ, address, false, length)
    }

    /**
     * Erase the 4 KiB sector containing [address] (SE, 20h).
     *
     * @throws BlockDeviceException `OUT_OF_RANGE`
     */
    @Throws(IOException::class)
    fun eraseSector(address: Int) {
        if (address < 0 || address >= SIZE) throw BlockDeviceException(Kind.OUT_OF_RANGE, "address beyond end")
        eraseCmd(SE, address, T_SE_MS)
    }

    /**
     * Erase-aware write; returns when the chip is ready. Per 4 KiB sector: if only 1->0 transitions
     * are needed just the changed pages are programmed, otherwise the sector is erased and rewritten
     * with the merged contents.
     *
     * @throws BlockDeviceException `OUT_OF_RANGE` if the range leaves the array, `WRITE_PROTECTED` if
     *   the chip reported a failure while block-protect bits are set
     */
    @Throws(IOException::class)
    fun writeBytes(address: Int, data: ByteArray) {
        var addr = address
        val length = data.size
        if (addr < 0 || addr.toLong() + length > SIZE) throw BlockDeviceException(Kind.OUT_OF_RANGE, "write beyond end")
        var pos = 0
        while (pos < length) {
            val base = addr and (SECTOR_SIZE - 1).inv()
            val off = addr - base
            val n = minOf(SECTOR_SIZE - off, length - pos)
            val old = readRaw(READ, base, false, SECTOR_SIZE)
            var needsErase = false
            for (i in 0 until n) {
                val o = old[off + i].toInt() and 0xFF
                val d = data[pos + i].toInt() and 0xFF
                if ((o and d) != d) { needsErase = true; break }   // would need a 0 -> 1 transition
            }
            if (!needsErase) {
                var done = 0
                while (done < n) {
                    val a = addr + done
                    val part = minOf(PAGE_SIZE - (a and (PAGE_SIZE - 1)), n - done)
                    if (!old.copyOfRange(off + done, off + done + part).contentEquals(data.copyOfRange(pos + done, pos + done + part))) {
                        program(a, data, pos + done, part)
                    }
                    done += part
                }
            } else {
                val merged = old.copyOf()
                System.arraycopy(data, pos, merged, off, n)
                eraseCmd(SE, base, T_SE_MS)
                var page = 0
                while (page < SECTOR_SIZE) {
                    var blank = true
                    for (i in 0 until PAGE_SIZE) {
                        if (merged[page + i] != 0xFF.toByte()) { blank = false; break }
                    }
                    if (!blank) program(base + page, merged, page, PAGE_SIZE)   // blank pages need no program
                    page += PAGE_SIZE
                }
            }
            addr += n
            pos += n
        }
    }
}
