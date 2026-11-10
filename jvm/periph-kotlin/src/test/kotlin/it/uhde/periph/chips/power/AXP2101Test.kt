package it.uhde.periph.chips.power

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class AXP2101Test {

    private fun connection(vararg regs: Int): MockConnection {
        val c = MockConnection()
        c.setRegister(0x30, 0x03)   // ADC_ENABLE reset
        c.setRegister(0x18, 0x0A)   // MODULE_ENABLE reset
        var i = 0
        while (i < regs.size) {
            c.setRegister(regs[i], regs[i + 1])
            i += 2
        }
        return c
    }

    private fun lastWriteTo(connection: MockConnection, reg: Int): Int {
        val writes = connection.writes()
        for (i in writes.indices.reversed()) {
            val w = writes[i]
            if (w.size == 2 && (w[0].toInt() and 0xFF) == reg) return w[1].toInt() and 0xFF
        }
        return -1
    }

    /** Preload registers, run the action on a fresh Full driver, check the last write per register. */
    private fun expect(regs: IntArray, expected: IntArray, action: (AXP2101Full) -> Unit) {
        val c = connection(*regs)
        val f = AXP2101Full(c)
        c.writes().clear()
        action(f)
        var i = 0
        while (i < expected.size) {
            assertEquals(expected[i + 1], lastWriteTo(c, expected[i]), "register 0x%02X".format(expected[i]))
            i += 2
        }
    }

    private fun r(vararg v: Int): IntArray = v

    private fun rejects(action: (AXP2101Full) -> Unit) {
        val f = AXP2101Full(connection(0x64, 3))
        assertThrows(IllegalArgumentException::class.java) { action(f) }
    }

    @Test
    fun minimalInitAndReads() {
        val c = connection(0x34, 0x0F, 0x35, 0xA0, 0xA4, 87, 0x00, 0x20, 0x01, 0x22)
        val m = AXP2101Minimal(c)
        assertEquals(0x1F, lastWriteTo(c, 0x30))
        assertEquals(0x0A, lastWriteTo(c, 0x18))
        assertEquals(4.0, m.batteryVoltage(), 1e-9)
        assertEquals(87, m.batteryPercent())
        assertEquals(AXP2101Minimal.CHARGE_CC, m.chargeState())
        assertTrue(m.vbusPresent())
        c.setRegister(0x00, 0x00)
        assertEquals(false, m.vbusPresent())
    }

    @Test
    fun initPreservesGaugeBitsAndMasksDebugBits() {
        val c = connection(0x18, 0x02, 0x34, 0xCF, 0x35, 0xA0)
        val m = AXP2101Minimal(c)
        assertEquals(0x0A, lastWriteTo(c, 0x18))
        assertEquals(4.0, m.batteryVoltage(), 1e-9)
    }

    @Test
    fun busFailureThrows() {
        val dead = object : MockConnection() {
            override fun writeRead(data: ByteArray, n: Int): ByteArray = throw IOException("nack")
        }
        assertThrows(IOException::class.java) { AXP2101Minimal(dead) }
    }

    @Test
    fun fullMeasurementsAndStatus() {
        val c = connection(0x34, 0x0F, 0x35, 0xA0, 0x36, 0x03, 0x37, 0xE8, 0x38, 0x13, 0x39, 0x88,
            0x3A, 0x10, 0x3B, 0x68, 0x3C, 0x1C, 0x3D, 0x6A, 0xA4, 50, 0x00, 0x3F, 0x01, 0x4B)
        val f = AXP2101Full(c)
        assertEquals(5.0, f.vbusVoltage(), 1e-9)
        assertEquals(4.2, f.systemVoltage(), 1e-9)
        assertEquals(0.5, f.tsVoltage(), 1e-9)
        assertEquals(22.0, f.dieTemperature(), 1e-9)
        val all = f.readAll()
        assertEquals(50, all.batteryPercent)
        assertEquals(4.0, all.batteryVoltage, 1e-9)
        val s = f.status()
        assertTrue(s.vbusGood && s.batfetOpen && s.batteryPresent && s.batteryActive &&
            s.thermalRegulation && s.currentLimit)
        assertEquals("discharge", s.batteryDirection)
        assertTrue(!s.systemOn && s.vindpm)
        assertEquals(3, s.chargeState)
    }

    @Test
    fun getRail() {
        val f = AXP2101Full(connection(0x80, 0x0F, 0x83, 0x80 or 18))
        val rail = f.getRail("dcdc2")
        assertTrue(rail.enabled)
        assertEquals(0.68, rail.volts, 1e-9)
        assertThrows(IllegalArgumentException::class.java) { f.getRail("nope") }
    }

    @Test
    fun chargerSetters() {
        expect(r(), r(0x30, 0x25)) { it.setAdcChannels(true, false, true, false, false, true) }
        expect(r(0x36, 0), r(0x36, 0x80)) { it.setAdcLowFrequency(true) }
        expect(r(0x18, 0x0A), r(0x18, 0x08)) { it.setCharging(false) }
        expect(r(), r(0x64, 3)) { it.setChargeVoltage(4.2) }
        expect(r(), r(0x64, 5)) { it.setChargeVoltage(4.4) }
        expect(r(0x64, 3), r(0x62, 11)) { it.setChargeCurrent(0.5) }
        expect(r(0x64, 3), r(0x62, 4)) { it.setChargeCurrent(0.1) }
        expect(r(0x64, 3), r(0x62, 20)) { it.setChargeCurrent(1.4) }
        expect(r(0x64, 1), r(0x62, 21)) { it.setChargeCurrent(1.5) }
        expect(r(0x61, 0xF0), r(0x61, 0xF5)) { it.setPrechargeCurrent(0.125) }
        expect(r(0x63, 0xE0), r(0x63, 0xF2)) { it.setTermination(0.05, true) }
        expect(r(0x63, 0xE0), r(0x63, 0xE2)) { it.setTermination(0.05, false) }
        expect(r(), r(0x16, 5)) { it.setInputCurrentLimit(2.0) }
        expect(r(), r(0x15, 6)) { it.setInputVoltageLimit(4.36) }
        expect(r(), r(0x14, 5)) { it.setMinSystemVoltage(3.7) }
        expect(r(0x67, 0x08), r(0x67, 0xEE)) { it.setChargeTimers(60, 12, true, true, true) }
        expect(r(0x67, 0x08), r(0x67, 0x3B)) { it.setChargeTimers(70, 20, false, false, false) }
        expect(r(), r(0x65, 3)) { it.setThermalRegulation(120) }
        expect(r(), r(0x13, 0x04)) { it.setDieProtection(135, false) }
        expect(r(0x68, 1), r(0x68, 0)) { it.setBatteryDetection(false) }
        expect(r(0x6A, 0xF8), r(0x6A, 0xFF, 0x18, 0x0E)) { it.setBackupCharging(true, 3.3) }
        expect(r(0x69, 0xC0), r(0x69, 0xE5)) { it.setChgled("manual", "blink_4hz") }
        expect(r(0x69, 0xC0), r(0x69, 0xC3)) { it.setChgled("type_b") }
        expect(r(0x69, 0xC1), r(0x69, 0xC0)) { it.setChgled("off") }
    }

    @Test
    fun temperatureSetters() {
        expect(r(0x50, 0x20), r(0x50, 0x3F)) { it.configureTs(false, 60, "always") }
        expect(r(), r(0x54, 41, 0x55, 88, 0x56, 62, 0x57, 76)) { it.setTsLimits(1.312, 0.176, 1.984, 0.152) }
        expect(r(), r(0x52, 2, 0x53, 1)) { it.setTsHysteresis(0.032, 0.004) }
        expect(r(0x59, 0x80), r(0x59, 0x94, 0x5A, 50, 0x5B, 30, 0x58, 1)) { it.configureJeita(true, 0.8, 0.24) }
    }

    @Test
    fun railSetters() {
        expect(r(0x83, 0x80), r(0x83, 0x80 or 85)) { it.setRailVoltage("dcdc2", 1.5) }
        expect(r(0x83, 0x80), r(0x83, 0x80 or 70)) { it.setRailVoltage("dcdc2", 1.2) }
        expect(r(), r(0x82, 18)) { it.setRailVoltage("dcdc1", 3.3) }
        expect(r(), r(0x85, 102)) { it.setRailVoltage("dcdc4", 1.84) }
        expect(r(), r(0x93, 28)) { it.setRailVoltage("aldo2", 3.3) }
        expect(r(), r(0x98, 10)) { it.setRailVoltage("cpusldo", 1.0) }
        expect(r(), r(0x99, 28)) { it.setRailVoltage("dldo1", 3.3) }
        expect(r(), r(0x9A, 18)) { it.setRailVoltage("dldo2", 1.4) }
        expect(r(0x90, 0), r(0x90, 0x20)) { it.setRailEnabled("bldo2", true) }
        expect(r(0x90, 0x20), r(0x90, 0xA0)) { it.setRailEnabled("dldo1", true) }
        expect(r(0x90, 0xA0), r(0x90, 0x80)) { it.setRailEnabled("bldo2", false) }
        expect(r(0x91, 0), r(0x91, 0x01)) { it.setRailEnabled("dldo2", true) }
        expect(r(0x81, 0), r(0x81, 0x10, 0x84, 0x80)) { it.configureDcdc("dcdc3", true, true) }
        expect(r(0x80, 0x0F, 0x81, 0x10), r(0x80, 0x6F, 0x81, 0xD3)) { it.configureDcdcGlobal(true, true, true, true, 240) }
    }

    @Test
    fun systemSetters() {
        expect(r(0x10, 0x30), r(0x10, 0x10)) { it.setOffDischarge(false) }
        expect(r(0x10, 0x30), r(0x10, 0x31)) { it.powerOff() }
        expect(r(0x10, 0x30), r(0x10, 0x32)) { it.restart() }
        expect(r(0x26, 0x08), r(0x26, 0x09)) { it.sleep() }
        expect(r(0x26, 0x08), r(0x26, 0x0A)) { it.wakeup() }
        expect(r(0x26, 0x03), r(0x26, 0x17)) { it.configureSleep(true, false, true) }
        expect(r(0x27, 0xC0, 0x22, 0x04, 0x10, 0x30), r(0x27, 0xFC, 0x22, 0x07, 0x10, 0x34)) {
            it.configurePowerKey(128, 10.0, 2.5, true, true, true)
        }
        expect(r(0x22, 0x03, 0x23, 0), r(0x22, 0x03, 0x24, 7, 0x23, 0x25)) { it.configurePowerOff(false, 3.3, true, 0x05) }
        expect(r(0x25, 0x20, 0x10, 0x30), r(0x25, 0x3F, 0x10, 0x38)) { it.configurePwrok(true, 64, true, true, true) }
        expect(r(0x12, 0), r(0x12, 0x08)) { it.setBatfetOffEnable(true) }
        expect(r(0x28, 0xFF, 0x2B, 0x0F), r(0x28, 0xFD, 0x2B, 0x8B)) {
            it.configureFastPoweron(true, false, mapOf("dcdc1" to 1, "dcdc4" to null, "dldo2" to 2))
        }
        expect(r(0x19, 0, 0x18, 0x0A), r(0x19, 0x37, 0x18, 0x0B)) { it.configureWatchdog(true, 128, "power_cycle") }
        expect(r(0x19, 0x06), r(0x19, 0x0E)) { it.feedWatchdog() }
        expect(r(), r(0x1A, 0xA5)) { it.configureBatteryWarning(15, 5) }
        expect(r(0x18, 0x02), r(0x18, 0x0A)) { it.setGaugeEnabled(true) }
        expect(r(0x17, 0), r(0x17, 0x08)) { it.resetGauge() }
        expect(r(0x17, 0), r(0x17, 0x04)) { it.resetGauge(true) }
        expect(r(0x1B, 0), r(0x1B, 0x04)) { it.setGpio1(true) }
        expect(r(0x1B, 0x04), r(0x1B, 0x00)) { it.setGpio1(false) }
        expect(r(), r(0x06, 0xAB)) { it.writeDataBuffer(2, 0xAB) }
    }

    @Test
    fun invalidArgumentsAreRejected() {
        rejects { it.setChargeVoltage(4.25) }
        rejects { it.setChargeCurrent(1.5) }
        rejects { it.setChargeCurrent(-0.1) }
        rejects { it.setPrechargeCurrent(0.3) }
        rejects { it.setInputCurrentLimit(1.2) }
        rejects { it.configureJeita(true, null, null, true, false, 3, 0) }
        rejects { it.setRailVoltage("dcdc3", 1.84) }
        rejects { it.setRailVoltage("dldo1", 3.5) }
        rejects { it.setRailVoltage("foo", 1.0) }
        rejects { it.setRailVoltage("aldo1", 0.3) }
        rejects { it.configureDcdc("dcdc1", false, true) }
        rejects { it.configurePowerKey(100, 6.0, 1.5, true, false, false) }
        rejects { it.configureFastPoweron(true, true, mapOf("x" to 1)) }
        rejects { it.configureWatchdog(true, 3) }
        rejects { it.configureBatteryWarning(25, 1) }
        rejects { it.readDataBuffer(4) }
    }

    @Test
    fun dataBufferAndSources() {
        val f = AXP2101Full(connection(0x20, 0x21, 0x21, 0x81))
        f.writeDataBuffer(2, 0xAB)
        assertEquals(0xAB, f.readDataBuffer(2))
        assertEquals(0x21, f.powerOnSource())
        assertEquals(0x81, f.powerOffSource())
    }

    @Test
    fun interrupts() {
        val c = connection(0x40, 0xFF, 0x41, 0xFC, 0x42, 0x5F)
        val f = AXP2101Full(c)
        f.disableInterrupt(AXP2101Full.SOURCE_VBUS_INSERT or AXP2101Full.SOURCE_CHARGE_DONE)
        assertEquals(0x7C, lastWriteTo(c, 0x41))
        assertEquals(0x4F, lastWriteTo(c, 0x42))
        f.enableInterrupt(AXP2101Full.SOURCE_VBUS_INSERT or AXP2101Full.SOURCE_PWRON_SHORT)
        assertEquals(0xFC, lastWriteTo(c, 0x41))

        c.setRegister(0x48, 0x10, 0x88, 0x01)
        c.writes().clear()
        assertEquals(0x018810, f.pollInterrupt())
        assertEquals(0x10, lastWriteTo(c, 0x48))
        assertEquals(0x88, lastWriteTo(c, 0x49))
        assertEquals(0x01, lastWriteTo(c, 0x4A))
        c.setRegister(0x48, 0, 0, 0)
        c.writes().clear()
        assertEquals(0, f.pollInterrupt())
        assertEquals(0L, c.writes().count { it.size == 2 }.toLong())
    }

    @Test
    fun pollingFallbackDeliversEvent() {
        val f = AXP2101Full(connection(0x49, 0x80))
        val got = java.util.concurrent.CompletableFuture<Int>()
        f.onInterrupt { got.complete(it) }
        assertEquals(AXP2101Full.SOURCE_VBUS_INSERT, got.get(2, java.util.concurrent.TimeUnit.SECONDS))
        f.offInterrupt()
    }
}
