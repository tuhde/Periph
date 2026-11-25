package it.uhde.periph.chips.memory

import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.OutputPin
import it.uhde.periph.storage.BlockDevice
import it.uhde.periph.storage.BlockDeviceException
import it.uhde.periph.storage.BlockDeviceException.Kind
import it.uhde.periph.storage.RamBlockDevice
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable

private const val FLASH_SIZE = Mx25l12845eMinimal.SIZE

/**
 * Behavioural MX25L12845E: 16 MiB array programmable 1 -> 0 only, page wrap on PP, 4/32/64 KiB and
 * chip erase, WEL/WIP handling (WIP reads high for a few RDSR polls after every program/erase),
 * BP block protect with P_FAIL/E_FAIL, secured OTP, deep power-down, RES/REMS/RDID/SFDP.
 */
private class FlashModel : Connection {
    val mem = ByteArray(FLASH_SIZE) { 0xFF.toByte() }
    val otp = ByteArray(512) { if (it < 16) (0xA0 + it).toByte() else 0xFF.toByte() }
    var inOtp = false
    var wel = false
    var dp = false
    var otpLocked = false
    var stuckBusy = false
    var sr = 0
    var scur = 0x01
    var busyPolls = 0
    var busyAfterOp = 2
    var pp = 0
    var se = 0
    var be32 = 0
    var be64 = 0
    var ce = 0
    var id = byteArrayOf(0xC2.toByte(), 0x20, 0x18)

    private fun protectedFrom(): Int {
        val bp = (sr shr 2) and 0xF
        if (bp == 0) return FLASH_SIZE
        if (bp >= 8) return 0
        return FLASH_SIZE - (65536 shl bp)
    }

    private fun isProtected(a: Int, n: Int) = a + n > protectedFrom()
    private fun startBusy() { busyPolls = busyAfterOp; wel = false }

    override fun enable() {}
    override fun disable() {}
    override fun isEnabled() = true
    override fun intPin(): InputPin? = null
    override fun enPin(): OutputPin? = null
    override fun close() {}
    override fun read(n: Int) = ByteArray(n)
    override fun write(data: ByteArray) { frame(data, 0) }
    override fun writeRead(data: ByteArray, n: Int) = frame(data, n)

    private fun frame(d: ByteArray, n: Int): ByteArray {
        val op = d[0].toInt() and 0xFF
        val out = ByteArray(n)
        if (dp && op != 0xAB) return out
        val busy = stuckBusy || busyPolls > 0
        if (busy && op != 0x05 && op != 0x2B && op != 0x9F && op != 0x30) return out
        val a = if (d.size >= 4) ((d[1].toInt() and 0xFF) shl 16) or ((d[2].toInt() and 0xFF) shl 8) or (d[3].toInt() and 0xFF) else 0
        when (op) {
            0x06 -> wel = true
            0x04 -> wel = false
            0x9F -> for (i in 0 until minOf(n, 3)) out[i] = id[i]
            0x05 -> {
                out[0] = ((sr and 0xFC) or (if (wel) 2 else 0) or (if (busy) 1 else 0)).toByte()
                if (busyPolls > 0) busyPolls--
            }
            0x01 -> if (wel && d.size >= 2) { sr = d[1].toInt() and 0xFC; startBusy() }
            0x03, 0x0B, 0x5A -> for (i in 0 until n) {
                out[i] = when {
                    op == 0x5A -> (a + i).toByte()
                    inOtp -> otp[(a + i) and 0x1FF]
                    else -> mem[(a + i) and 0xFFFFFF]
                }
            }
            0x02 -> if (wel) {
                pp++
                if (inOtp) {
                    if (otpLocked) scur = scur or 0x20
                    else for (i in 4 until d.size) otp[(a + i - 4) and 0x1FF] = (otp[(a + i - 4) and 0x1FF].toInt() and d[i].toInt()).toByte()
                } else if (isProtected(a and 255.inv(), 256)) {
                    scur = scur or 0x20
                } else {
                    val page = a and 255.inv()
                    for (i in 4 until d.size) {
                        val p = page + ((a + i - 4) and 255)
                        mem[p] = (mem[p].toInt() and d[i].toInt()).toByte()
                    }
                }
                startBusy()
            }
            0x20, 0x52, 0xD8 -> if (wel) {
                val size = if (op == 0x20) 4096 else if (op == 0x52) 32768 else 65536
                val base = a and (size - 1).inv()
                if (op == 0x20) se++ else if (op == 0x52) be32++ else be64++
                if (isProtected(base, size)) scur = scur or 0x40 else mem.fill(0xFF.toByte(), base, base + size)
                startBusy()
            }
            0xC7, 0x60 -> if (wel) {
                ce++
                if (((sr shr 2) and 0xF) != 0) scur = scur or 0x40 else mem.fill(0xFF.toByte())
                startBusy()
            }
            0xB9 -> dp = true
            0xAB -> { dp = false; if (n > 0) out[0] = 0x17 }
            0x90 -> if (n >= 2) { out[0] = 0xC2.toByte(); out[1] = 0x17 }
            0xB1 -> inOtp = true
            0xC1 -> inOtp = false
            0x2B -> out[0] = scur.toByte()
            0x2F -> if (wel) { scur = scur or 0x02; otpLocked = true; startBusy() }
            0x30 -> scur = scur and 0x60.inv()
        }
        return out
    }
}

class Mx25l12845eTest {

    private fun kind(e: Executable): Kind = assertThrows(BlockDeviceException::class.java, e).kind()

    @Test
    fun init() {
        val m = FlashModel()
        val f = Mx25l12845eMinimal(m)
        assertArrayEquals(byteArrayOf(0xC2.toByte(), 0x20, 0x18), f.jedecId())
        assertEquals(16 * 1024 * 1024, f.size())
        val wrong = FlashModel().apply { id = byteArrayOf(0xC2.toByte(), 0x20, 0x17) }
        assertEquals(Kind.NOT_READY, kind { Mx25l12845eMinimal(wrong) })
        val stuck = FlashModel().apply { stuckBusy = true }
        assertEquals(Kind.TIMEOUT, kind { Mx25l12845eMinimal(stuck, 20) })
        val asleep = FlashModel().apply { dp = true }
        Mx25l12845eMinimal(asleep)
        assertFalse(asleep.dp, "init releases deep power-down")
    }

    @Test
    fun minimalWrite() {
        val m = FlashModel()
        val f = Mx25l12845eMinimal(m)
        val pattern = ByteArray(600) { (it * 7 + 3).toByte() }
        f.writeBytes(0x1F0, pattern)
        assertArrayEquals(pattern, f.readBytes(0x1F0, 600))
        assertEquals(0, m.se, "blank flash: no erase needed")
        assertTrue(m.mem[0x1EF] == 0xFF.toByte() && m.mem[0x1F0 + 600] == 0xFF.toByte())
        val ppBefore = m.pp
        f.writeBytes(0x1F0, pattern)
        assertTrue(m.pp == ppBefore && m.se == 0, "identical rewrite programs and erases nothing")
        val more = ByteArray(600) { (pattern[it].toInt() and 0xF0).toByte() }
        f.writeBytes(0x1F0, more)
        assertTrue(more.contentEquals(f.readBytes(0x1F0, 600)) && m.se == 0, "1->0 only rewrite needs no erase")
        val ff = ByteArray(10) { 0xFF.toByte() }
        f.writeBytes(0x1F0, ff)
        assertTrue(m.se == 1 && ff.contentEquals(f.readBytes(0x1F0, 10)), "0->1 needs a sector erase")
        assertArrayEquals(more.copyOfRange(16, 36), f.readBytes(0x200, 20), "rest of the sector survived the erase")
        val big = ByteArray(9000) { (it xor (it shr 8)).toByte() }
        f.writeBytes(0x2FF0, big)
        assertArrayEquals(big, f.readBytes(0x2FF0, 9000))
        assertEquals(Kind.OUT_OF_RANGE, kind { f.writeBytes(FLASH_SIZE - 2, big.copyOf(5)) })
        f.eraseSector(0x2000)
        assertEquals(0xFF.toByte(), m.mem[0x2000])
    }

    @Test
    fun protection() {
        val m = FlashModel()
        val f = Mx25l12845eFull(m)
        assertEquals(0, f.protection())
        f.setProtection(8)
        assertEquals(8, f.protection())
        assertEquals(Kind.WRITE_PROTECTED, kind { f.pageProgram(0x100, byteArrayOf(0)) })
        assertEquals(0xFF.toByte(), m.mem[0x100])
        assertEquals(0, f.readSecurity() and 0x60, "fail flags cleared by the driver (CLSR)")
        assertEquals(Kind.WRITE_PROTECTED, kind { f.eraseSector(0) })
        assertTrue(f.info().readOnly())
        assertEquals(Kind.OUT_OF_RANGE, kind { f.setProtection(16) })
        f.setProtection(0)
        f.pageProgram(0x100, byteArrayOf(0))
        assertEquals(0, m.mem[0x100].toInt())
    }

    @Test
    fun rawFull() {
        val m = FlashModel()
        val f = Mx25l12845eFull(m)
        val data = ByteArray(300) { it.toByte() }
        f.pageProgram(0x1000, data.copyOf(256))
        assertArrayEquals(data.copyOf(256), f.readBytes(0x1000, 256))
        assertEquals(Kind.OUT_OF_RANGE, kind { f.pageProgram(0x10C0, data.copyOf(100)) })
        assertEquals(Kind.OUT_OF_RANGE, kind { f.pageProgram(0, data.copyOf(257)) })
        assertArrayEquals(f.readBytes(0x1000, 256), f.fastRead(0x1000, 256))
        f.eraseBlock32k(0x8000)
        f.eraseBlock64k(0x10000)
        assertTrue(m.be32 == 1 && m.be64 == 1)
        f.chipErase()
        assertTrue(m.ce == 1 && m.mem[0x1000] == 0xFF.toByte())
        assertEquals(0x17, f.electronicId())
        assertArrayEquals(intArrayOf(0xC2, 0x17), f.deviceIds())
        f.deepPowerDown()
        assertTrue(m.dp)
        f.releaseDeepPowerDown()
        assertFalse(m.dp)
        assertEquals(1, f.readSfdp(0, 8)[1].toInt())
        assertFalse(f.isBusy())
        f.writeStatus(0x04)
        assertEquals(0x04, m.sr)
        f.writeStatus(0)
    }

    @Test
    fun otp() {
        val m = FlashModel()
        val f = Mx25l12845eFull(m)
        val esn = f.serialNumber()
        assertTrue(esn[0] == 0xA0.toByte() && esn[15] == 0xAF.toByte())
        assertFalse(m.inOtp, "OTP mode exited")
        f.programOtp(16, byteArrayOf(1, 2, 3, 4))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), f.readOtp(16, 4))
        assertEquals(Kind.OUT_OF_RANGE, kind { f.programOtp(8, byteArrayOf(1)) })
        assertEquals(Kind.OUT_OF_RANGE, kind { f.programOtp(510, byteArrayOf(1, 2, 3, 4)) })
        f.lockOtp()
        assertTrue(m.otpLocked)
    }

    @Test
    fun blockDevice() {
        val m = FlashModel()
        val f = Mx25l12845eFull(m)
        assertTrue(f.blockSize() == 512 && f.blockCount() == 32768L && f.eraseSize() == 4096 && f.needsErase())
        assertEquals("MX25L12845E", f.info().name())
        val w = ByteArray(1024) { (it * 5 + 1).toByte() }
        f.write(8, w)
        assertArrayEquals(w, f.read(8, 2))
        f.write(8, w)
        assertArrayEquals(w, f.read(8, 2), "identical rewrite is fine")
        val ff = ByteArray(512) { 0xFF.toByte() }
        assertEquals(Kind.NOT_ERASED, kind { f.write(8, ff) })
        assertEquals(0, m.se, "NOT_ERASED did not erase anything")
        f.erase(8, 8)
        assertEquals(0xFF.toByte(), m.mem[8 * 512])
        assertEquals(Kind.OUT_OF_RANGE, kind { f.erase(9, 8) })
        assertEquals(Kind.OUT_OF_RANGE, kind { f.erase(32768 - 8, 16) })
        assertEquals(Kind.OUT_OF_RANGE, kind { f.read(32768, 1) })
        f.sync()
        val se0 = m.se
        val be640 = m.be64
        val be320 = m.be32
        f.erase(0, 128 + 64 + 8)
        assertTrue(m.be64 - be640 == 1 && m.be32 - be320 == 1 && m.se - se0 == 1, "erase picks 64K, then 32K, then 4K")
        val m2 = FlashModel()
        val g = Mx25l12845eFull(m2, true)
        g.write(0, w)
        g.write(0, ff)
        assertTrue(m2.se == 1 && ff.contentEquals(g.read(0, 1)), "auto-erase handles 0->1")
        assertArrayEquals(w.copyOfRange(512, 1024), g.read(1, 1), "neighbouring block kept")
        val ram = RamBlockDevice(512, 64, 8, false)
        val x = ByteArray(512) { 0x5A }
        for ((dev, blk) in listOf<Pair<BlockDevice, Long>>(g to 64L, ram to 0L)) {
            dev.erase(blk, 8)
            dev.write(blk, x)
            assertArrayEquals(x, dev.read(blk, 1))
            assertEquals(Kind.OUT_OF_RANGE, kind { dev.read(dev.blockCount(), 1) })
        }
    }

    @Test
    fun timeout() {
        val m = FlashModel()
        val f = Mx25l12845eFull(m)
        m.stuckBusy = true
        assertEquals(Kind.TIMEOUT, kind { f.waitUntilReady(5) })
        assertTrue(f.isBusy())
    }
}
