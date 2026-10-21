package it.uhde.periph.chips.memory

import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.OutputPin
import it.uhde.periph.connection.SPIExtensions
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

/**
 * Behavioural model of an SD card in SPI mode behind [SPIExtensions]. Needs 80 idle clocks
 * before CMD0, checks the command CRC7, models R1/R3/R7, CSD/CID, single and multi-block
 * transfers with tokens, busy and CRC16.
 */
private open class SdModel(val v2: Boolean = true, val sdhc: Boolean = true) : SPIExtensions {
    var csizeV2 = 7                       // (7+1) * 512 KiB = 4 MiB = 8192 blocks
    var acmd41Calls = 0
    var acmd41Needed = 3
    var gotIdleClocks = false
    var selected = false
    var inIdle = false
    var crcOn = false
    var corruptReadCrc = false
    var currentSpeed = 0
    var csTogglesInCommand = 0
    val mem = ByteArray(8192 * 512)
    val cmdLog = ArrayList<Int>()
    private var state = 0                 // 0 CMD, 1 WTOKEN, 2 WDATA
    private val cmdBuf = ArrayList<Int>()
    private val out = ArrayDeque<Int>()
    private var appNext = false
    private var streaming = false
    private var multiWrite = false
    private var streamBlock = 0L
    private var wBlock = 0L
    private val wData = ArrayList<Int>()

    private fun pushBlock(data: ByteArray, off: Int, len: Int) {
        out.add(0xFE)
        for (i in 0 until len) out.add(data[off + i].toInt() and 0xFF)
        var c = SdSpiMinimal.crc16(data, off, len)
        if (corruptReadCrc) c = c xor 1
        out.add(c shr 8)
        out.add(c and 0xFF)
    }

    private fun blockOf(arg: Int): Long = if (sdhc) arg.toLong() and 0xFFFFFFFFL else (arg.toLong() and 0xFFFFFFFFL) / 512

    private fun doCommand() {
        val wasApp = appNext
        appNext = false
        val cb = ByteArray(6) { cmdBuf[it].toByte() }
        val cmd = cb[0].toInt() and 0x3F
        val arg = ((cb[1].toInt() and 0xFF) shl 24) or ((cb[2].toInt() and 0xFF) shl 16) or
            ((cb[3].toInt() and 0xFF) shl 8) or (cb[4].toInt() and 0xFF)
        cmdLog.add(if (wasApp) (0x80 or cmd) else cmd)
        if (((cb[5].toInt() and 0xFF) shr 1) != SdSpiMinimal.crc7(cb, 5)) { out.add(0xFF); out.add(0x09); return }
        out.add(0xFF)   // Ncr gap
        val idle = if (inIdle) 0x01 else 0x00
        if (wasApp) {
            if (cmd == 41) {
                acmd41Calls++
                if (acmd41Calls >= acmd41Needed) { inIdle = false; out.add(0x00) } else out.add(0x01)
            } else out.add(idle)
            return
        }
        when (cmd) {
            0 -> { if (!gotIdleClocks) { out.clear(); return }; inIdle = true; out.add(0x01) }
            8 -> if (!v2) out.add(0x05) else { out.add(0x01); out.add(0); out.add(0); out.add(0x01); out.add(0xAA) }
            9 -> {
                val csd = ByteArray(16)
                if (sdhc) {
                    csd[0] = 0x40; csd[7] = ((csizeV2 shr 16) and 0x3F).toByte(); csd[8] = (csizeV2 shr 8).toByte(); csd[9] = csizeV2.toByte()
                } else {
                    csd[5] = 9; csd[6] = 0x03; csd[7] = 0xFF.toByte(); csd[8] = 0xC0.toByte(); csd[9] = 0x03; csd[10] = 0x80.toByte()
                }
                out.add(0x00); out.add(0xFF); pushBlock(csd, 0, 16)
            }
            10 -> {
                val cid = byteArrayOf(0x03, 'S'.code.toByte(), 'D'.code.toByte(), 'T'.code.toByte(), 'E'.code.toByte(), 'S'.code.toByte(),
                    'T'.code.toByte(), '1'.code.toByte(), 0x10, 0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(), 0x01, 0x23, 0x01)
                out.add(0x00); out.add(0xFF); pushBlock(cid, 0, 16)
            }
            12 -> { streaming = false; out.clear(); listOf(0xFF, 0x00, 0x00, 0x00, 0xFF).forEach { out.add(it) } }
            13 -> { out.add(0x00); out.add(0x00) }
            16 -> out.add(idle)
            17 -> { out.add(0x00); out.add(0xFF); pushBlock(mem, blockOf(arg).toInt() * 512, 512) }
            18 -> { out.add(0x00); streaming = true; streamBlock = blockOf(arg) }
            24, 25 -> { out.add(0x00); state = 1; wBlock = blockOf(arg); multiWrite = cmd == 25 }
            32, 33 -> out.add(0x00)
            38 -> { out.add(0x00); out.add(0x00); out.add(0x00) }
            55 -> { appNext = true; out.add(idle) }
            58 -> { out.add(idle); out.add(if (sdhc) 0xC0 else 0x80); out.add(0xFF); out.add(0x80); out.add(0x00) }
            59 -> { crcOn = (arg and 1) != 0; out.add(idle) }
            else -> out.add(0x04)   // illegal command
        }
    }

    private fun feed(b: Int): Int {
        if (state == 1) {
            if (b == 0xFE || b == 0xFC) { state = 2; wData.clear() }
            else if (b == 0xFD) { state = 0; out.add(0xFF); out.add(0x00); out.add(0x00); out.add(0xFF); return 0xFF }
        } else if (state == 2) {
            wData.add(b)
            if (wData.size == 514) {
                val d = ByteArray(512) { wData[it].toByte() }
                val got = (wData[512] shl 8) or wData[513]
                val ok = !crcOn || got == SdSpiMinimal.crc16(d, 0, 512)
                if (ok) { System.arraycopy(d, 0, mem, wBlock.toInt() * 512, 512); wBlock++ }
                out.add(if (ok) 0xE5 else 0xEB); out.add(0x00); out.add(0x00); out.add(0xFF)
                state = if (multiWrite) 1 else 0
                return 0xFF   // the data response follows on the NEXT byte clock
            }
        } else if (cmdBuf.isNotEmpty() || (b and 0xC0) == 0x40) {
            cmdBuf.add(b)
            if (cmdBuf.size == 6) { doCommand(); cmdBuf.clear() }
            return 0xFF
        }
        if (out.isNotEmpty()) return out.removeFirst()
        if (streaming && state == 0) {
            pushBlock(mem, streamBlock.toInt() * 512, 512)
            streamBlock++
            return out.removeFirst()
        }
        return 0xFF
    }

    override fun idleClocks(n: Int) { if (!selected && n >= 80) gotIdleClocks = true }
    override fun setSpeed(hz: Int) { currentSpeed = hz }
    override fun select() { selected = true }
    override fun deselect() { selected = false; if (state == 0 && cmdBuf.isNotEmpty()) csTogglesInCommand++ }
    override fun readInto(buf: ByteArray) { for (i in buf.indices) buf[i] = feed(0xFF).toByte() }
    override fun write(data: ByteArray) { for (x in data) feed(x.toInt() and 0xFF) }
    override fun read(n: Int): ByteArray = ByteArray(n).also { readInto(it) }
    override fun writeRead(data: ByteArray, n: Int): ByteArray { write(data); return read(n) }
    override fun enable() {}
    override fun disable() {}
    override fun isEnabled(): Boolean = true
    override fun intPin(): InputPin? = null
    override fun enPin(): OutputPin? = null
    override fun close() {}
}

private class DeadCard : SdModel() {
    override fun readInto(buf: ByteArray) { buf.fill(0xFF.toByte()) }
}

class SdSpiTest {

    @Test
    fun crcHelpers() {
        assertEquals(0x31C3, SdSpiMinimal.crc16("123456789".toByteArray(), 0, 9))
        assertEquals(0x95, (SdSpiMinimal.crc7(byteArrayOf(0x40, 0, 0, 0, 0), 5) shl 1) or 1)
    }

    @Test
    fun initV2Sdhc() {
        val m = SdModel()
        val sd = SdSpiMinimal(m)
        assertEquals(SdSpiMinimal.CardType.SDHC, sd.cardType())
        assertEquals(8192, sd.blockCount())
        assertTrue(m.gotIdleClocks, "80 idle clocks before CMD0")
        assertEquals(12_000_000, m.currentSpeed)
        assertEquals(0, m.csTogglesInCommand)
    }

    @Test
    fun initV1ByteAddressed() {
        val m = SdModel(v2 = false, sdhc = false)
        val sd = SdSpiMinimal(m)
        assertEquals(SdSpiMinimal.CardType.SD_V1, sd.cardType())
        assertEquals(2_097_152L, sd.blockCount())
        assertTrue(m.cmdLog.contains(16), "CMD16 for a byte-addressed card")
    }

    @Test
    fun noCardIsNotReady() {
        val e = assertThrows(BlockDeviceException::class.java) { SdSpiMinimal(DeadCard()) }
        assertEquals(Kind.NOT_READY, e.kind())
    }

    @Test
    fun minimalBlockRoundtrip() {
        val sd = SdSpiMinimal(SdModel())
        val w = ByteArray(512) { (it * 7 + 3).toByte() }
        sd.writeBlock(5, w)
        assertArrayEquals(w, sd.readBlock(5))
        assertEquals(Kind.OUT_OF_RANGE, assertThrows(BlockDeviceException::class.java) { sd.readBlock(8192) }.kind())
    }

    @Test
    fun fullMultiBlockAndInfo() {
        val sd = SdSpiFull(SdModel())
        assertEquals(512, sd.blockSize())
        assertFalse(sd.needsErase())
        assertEquals("TEST1", sd.info().name())
        val w = ByteArray(4 * 512) { (it xor (it shr 8)).toByte() }
        sd.write(100, w)
        sd.sync()
        assertArrayEquals(w, sd.read(100, 4))
        sd.erase(100, 4)
        assertEquals(Kind.OUT_OF_RANGE, assertThrows(BlockDeviceException::class.java) { sd.write(8190, w) }.kind())
        assertTrue((sd.ocr() and 0x40000000) != 0)
        assertTrue(sd.isPresent())
    }

    @Test
    fun crcErrorIsReported() {
        val m = SdModel()
        val sd = SdSpiFull(m)
        m.corruptReadCrc = true
        assertEquals(Kind.CRC, assertThrows(BlockDeviceException::class.java) { sd.readBlock(1) }.kind())
        sd.setCrc(false)
        assertEquals(512, sd.readBlock(1).size)
    }

    @Test
    fun blockDeviceContractSharedWithRam() {
        for (dev in listOf<BlockDevice>(SdSpiFull(SdModel()), RamBlockDevice(512, 64, 1, false))) {
            val w = ByteArray(512) { 0xA5.toByte() }
            dev.write(3, w)
            assertArrayEquals(w, dev.read(3, 1))
            assertEquals(Kind.OUT_OF_RANGE, assertThrows(BlockDeviceException::class.java) { dev.read(dev.blockCount(), 1) }.kind())
        }
    }
}
