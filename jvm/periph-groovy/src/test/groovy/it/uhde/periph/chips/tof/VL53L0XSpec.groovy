package it.uhde.periph.chips.tof

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class VL53L0XSpec extends Specification {

    /**
     * Page-aware VL53L0X simulator on top of {@link MockConnection}. Registers
     * written while 0xFF != 0 go to a separate per-page store, so the
     * private-bank tuning writes don't clobber page-0 registers. Starting a
     * ranging (or calibration) raises RESULT_INTERRUPT_STATUS; the interrupt
     * clear drops it unless continuous mode is active. The SPAD-info handshake
     * (page 7, 0x83) completes immediately.
     */
    static class Sim extends MockConnection {
        final Map<Integer, Integer> regs = new ConcurrentHashMap<>()
        final Map<String, Integer> pages = new ConcurrentHashMap<>()
        final List<List<Integer>> log = new CopyOnWriteArrayList<>()   // [page, bytes...]
        volatile int page = 0
        volatile boolean continuous = false

        Sim() {
            set(0xC0, 0xEE, 0xAA, 0x10)
            set(0x84, 0x11)
            set(0xB0, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF)
            set(0xF8, 0x00, 0x10)
            // Result block: status 11, 10.0 SPADs, 5.0 MCPS signal, 0.5 MCPS ambient, 250 mm.
            set(0x14, 11 << 3, 0x00, 0x0A, 0x00, 0x00, 0x00, 0x02, 0x80, 0x00, 0x40, 0x00, 0xFA)
            pages["1:${0x91}".toString()] = 0x3C
            pages["7:${0x92}".toString()] = 0x85
        }

        void set(int reg, int... values) {
            values.eachWithIndex { int v, int i -> regs[reg + i] = v }
        }

        int reg(int r) { regs.getOrDefault(r, 0) }

        int reg16(int r) { (reg(r) << 8) | reg(r + 1) }

        int pageReg(int p, int r) { pages.getOrDefault("$p:$r".toString(), 0) }

        List<List<Integer>> page0Writes(int r) {
            log.findAll { it[0] == 0 && it.size() >= 3 && it[1] == r }.collect { it.drop(1) }
        }

        boolean logged(List<Integer> bytes) { log.any { it.drop(1) == bytes } }

        List<Integer> lastWrite(int back) { log[log.size() - 1 - back].drop(1) }

        @Override
        synchronized void write(byte[] data) throws IOException {
            List<Integer> d = data.collect { it & 0xFF }
            int reg = d[0]
            if (reg == 0xFF) page = d[1]
            log.add([page] + d)
            if (page != 0 && reg != 0xFF) {
                for (int i = 1; i < d.size(); i++) pages["$page:${reg + i - 1}".toString()] = d[i]
                if (page == 7 && reg == 0x83 && d[1] == 0x00) pages["7:${0x83}".toString()] = 0x01
                return
            }
            for (int i = 1; i < d.size(); i++) regs[reg + i - 1] = d[i]
            if (reg == 0x00 && d.size() == 2) {
                int v = d[1]
                if ((v & 0x06) != 0) {
                    continuous = true
                    regs[0x13] = 0x04
                } else if ((v & 0x01) != 0) {
                    if (continuous) continuous = false
                    else regs[0x13] = 0x04
                }
                regs[0x00] = 0x00
            } else if (reg == 0x0B && d[1] == 0x01 && !continuous) {
                regs[0x13] = 0x00
            }
        }

        @Override
        synchronized byte[] writeRead(byte[] data, int n) throws IOException {
            (0..<n).collect { int i ->
                int r = (data[0] & 0xFF) + i
                (byte) (page != 0 ? pageReg(page, r) : reg(r))
            } as byte[]
        }
    }

    static List<Integer> tail(List<List<Integer>> ws, int n) { ws.takeRight(n).collect { it[1] } }

    def "rejects a wrong model ID"() {
        given:
        def sim = new Sim()
        sim.set(0xC0, 0xEF)

        when:
        new VL53L0XMinimal(sim)

        then:
        thrown(IOException)
    }

    def "init runs the full sequence"() {
        given:
        def sim = new Sim()

        when:
        new VL53L0XMinimal(sim)

        then:
        (sim.reg(0x89) & 0x01) == 0x01
        sim.reg(0x88) == 0x00
        sim.page0Writes(0x60)[0] == [0x60, 0x12]
        sim.page0Writes(0x44)[0] == [0x44, 0x00, 0x20]
        (0..<6).collect { sim.reg(0xB0 + it) } == [0x00, 0xF0, 0x01, 0, 0, 0]
        sim.reg(0xB6) == 0xB4
        sim.pageReg(1, 0x4E) == 0x2C
        sim.reg(0x46) == 0x25
        sim.pageReg(1, 0x46) == 0x05
        sim.reg(0x0A) == 0x04
        sim.reg(0x84) == 0x01
        sim.reg(0x01) == 0xE8
        tail(sim.page0Writes(0x00), 4) == [0x41, 0x00, 0x01, 0x00]
        tail(sim.page0Writes(0x01), 4) == [0xE8, 0x01, 0x02, 0xE8]
        sim.page == 0
    }

    def "single shot writes the stop-variable preamble and decodes the range"() {
        given:
        def sim = new Sim()
        def sensor = new VL53L0XMinimal(sim)
        sim.log.clear()

        expect:
        sensor.distance() == 250
        sensor.rangeValid()
        sim.log.take(8).collect { it.drop(1) } == [[0x80, 0x01], [0xFF, 0x01], [0x00, 0x00], [0x91, 0x3C],
                                                   [0x00, 0x01], [0xFF, 0x00], [0x80, 0x00], [0x00, 0x01]]
        sim.lastWrite(0) == [0x0B, 0x01]

        when:
        sim.set(0x14, 4 << 3)
        sim.set(0x1E, 0x1F, 0xFF)

        then:
        sensor.distance() == 8191
        !sensor.rangeValid()
    }

    def "measurement record and continuous ranging"() {
        given:
        def sim = new Sim()
        def full = new VL53L0XFull(sim)
        full.distance()

        expect:
        full.readMeasurement() == new VL53L0XFull.Measurement(250, 11, 5.0d, 0.5d, 10.0d)
        full.rangeStatus() == 11

        when:
        sim.log.clear()
        full.startContinuous()

        then:
        sim.lastWrite(0) == [0x00, 0x02]
        sim.logged([0x91, 0x3C])
        full.dataReady()
        full.readContinuous() == 250

        when:
        full.stopContinuous()

        then:
        sim.log.takeRight(6).collect { it.drop(1) } == [[0x00, 0x01], [0xFF, 0x01], [0x00, 0x00], [0x91, 0x00],
                                                        [0x00, 0x01], [0xFF, 0x00]]

        when:
        full.startContinuous(100)

        then:
        (0..<4).collect { sim.reg(0x04 + it) } == [0x00, 0x00, 0x06, 0x40]
        sim.lastWrite(0) == [0x00, 0x04]
    }

    def "timing budget, signal rate, VCSEL periods and profiles"() {
        given:
        def sim = new Sim()
        def full = new VL53L0XFull(sim)

        expect:
        full.timingBudget() in 32000..34000

        when:
        full.setTimingBudget(50000)
        full.setSignalRateLimit(0.1d)

        then:
        Math.abs(full.timingBudget() - 50000) < 50
        sim.reg16(0x44) == 13
        full.signalRateLimit() == 13 / 128.0d

        when:
        full.setTimingBudget(19999)

        then:
        thrown(IllegalArgumentException)

        when:
        full.setVcselPulsePeriod(VL53L0XFull.VcselPeriodType.PRE_RANGE, 18)
        full.setVcselPulsePeriod(VL53L0XFull.VcselPeriodType.FINAL_RANGE, 14)

        then:
        full.vcselPulsePeriod(VL53L0XFull.VcselPeriodType.PRE_RANGE) == 18
        full.vcselPulsePeriod(VL53L0XFull.VcselPeriodType.FINAL_RANGE) == 14
        sim.reg(0x57) == 0x50
        [sim.reg(0x48), sim.reg(0x30), sim.pageReg(1, 0x30), sim.reg(0x01)] == [0x48, 0x07, 0x20, 0xE8]
        Math.abs(full.timingBudget() - 50000) < 300

        when:
        full.setVcselPulsePeriod(VL53L0XFull.VcselPeriodType.PRE_RANGE, 13)

        then:
        thrown(IllegalArgumentException)

        when:
        full.setProfile(VL53L0XFull.Profile.HIGH_SPEED)

        then:
        full.vcselPulsePeriod(VL53L0XFull.VcselPeriodType.PRE_RANGE) == 14
        Math.abs(full.timingBudget() - 20000) < 50
        sim.reg16(0x44) == 32

        when:
        full.setProfile(VL53L0XFull.Profile.LONG_RANGE)

        then:
        full.vcselPulsePeriod(VL53L0XFull.VcselPeriodType.PRE_RANGE) == 18
        sim.reg16(0x44) == 13
    }

    def "offset, crosstalk, recalibration, thresholds, address and interrupts"() {
        given:
        def sim = new Sim()
        def full = new VL53L0XFull(sim)

        when:
        full.setOffset(-10.25d)

        then:
        sim.reg16(0x28) == (-41 & 0x0FFF)
        full.offset() == -10.25d

        when:
        full.setOffset(12.5d)
        full.setCrosstalkCompensation(0.5d)
        sim.log.clear()
        full.recalibrate()
        full.setInterruptThresholds(100, 801)
        full.setAddress(0x30)

        then:
        full.offset() == 12.5d
        sim.reg16(0x20) == 4096
        sim.logged([0x00, 0x41]) && sim.logged([0x01, 0x02])
        sim.reg(0x01) == 0xE8
        sim.reg16(0x0E) == 50
        sim.reg16(0x0C) == 400
        full.interruptThresholds() == [100, 800] as int[]
        sim.reg(0x8A) == 0x30
        full.modelId() == 0xEE
        full.revisionId() == 0x10

        when:
        full.setAddress(0x78)

        then:
        thrown(IllegalArgumentException)

        when:
        full.enableInterrupt(VL53L0XFull.SOURCE_OUT_OF_WINDOW)
        full.disableInterrupt(VL53L0XFull.SOURCE_LEVEL_LOW)
        int kept = sim.reg(0x0A)
        full.disableInterrupt(VL53L0XFull.SOURCE_OUT_OF_WINDOW)
        sim.set(0x13, 0x03 | 0x08)

        then:
        kept == 0x03
        sim.reg(0x0A) == 0x00
        full.pollInterrupt() == VL53L0XFull.SOURCE_OUT_OF_WINDOW
        sim.reg(0x13) == 0
        full.pollInterrupt() == 0

        when: "the polling fallback delivers a pending status (no intPin on the mock)"
        def got = new CopyOnWriteArrayList<Integer>()
        sim.set(0x13, 0x04)
        full.onInterrupt({ int s -> got.add(s) } as java.util.function.IntConsumer)
        for (int i = 0; i < 200 && got.isEmpty(); i++) Thread.sleep(5)
        full.offInterrupt()

        then:
        got == [VL53L0XFull.SOURCE_NEW_SAMPLE_READY]
    }

    def "a data-ready poll that never completes times out"() {
        given:
        def sim = new Sim()
        def full = new VL53L0XFull(sim)
        full.startContinuous()
        sim.continuous = false
        sim.set(0x13, 0x00)

        when:
        full.readContinuous()

        then:
        thrown(IOException)
    }
}
