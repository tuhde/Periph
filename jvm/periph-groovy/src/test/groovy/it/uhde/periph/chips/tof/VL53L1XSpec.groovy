package it.uhde.periph.chips.tof

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class VL53L1XSpec extends Specification {

    /**
     * VL53L1X simulator on top of {@link MockConnection} with explicit 16-bit register indices.
     * GPIO__TIO_HV_STATUS (0x0031) is computed: bit 0 is 0 (active-low line asserted) while a
     * result is pending. Starting a single-shot or timed ranging makes a result pending; the
     * interrupt clear drops it unless timed ranging is running.
     */
    static class Sim extends MockConnection {
        final Map<Integer, Integer> regs = new ConcurrentHashMap<>()
        final List<List<Integer>> log = new CopyOnWriteArrayList<>()
        volatile boolean pending = false
        volatile boolean ranging = false

        Sim() {
            set(0x00E5, 0x01)
            set(0x010F, 0xEA, 0xCC, 0x10)
            set(0x013E, 0x91)
            set(0x00DE, 0x00, 0x50)
            // Result block: raw status 9 (valid), 10.0 SPADs, 0.5 MCPS ambient, 250 mm, 5.0 MCPS signal.
            set(0x0089, 9, 0, 0, 0x0A, 0x00, 0, 0, 0x00, 0x40, 0, 0, 0, 0, 0x00, 0xFA, 0x02, 0x80)
        }

        void set(int reg, int... values) {
            values.eachWithIndex { int v, int i -> regs[reg + i] = v }
        }

        int reg(int r) {
            return regs.getOrDefault(r, 0)
        }

        int reg16(int r) {
            return (reg(r) << 8) | reg(r + 1)
        }

        List<Integer> writesTo(int r) {
            log.findAll { it.size() == 3 && ((it[0] << 8) | it[1]) == r }.collect { it[2] }
        }

        @Override
        synchronized void write(byte[] data) throws IOException {
            List<Integer> w = data.collect { (it as int) & 0xFF }
            log << w
            int start = (w[0] << 8) | w[1]
            for (int i = 2; i < w.size(); i++) regs[start + i - 2] = w[i]
            if (start == 0x0087 && w.size() == 3) {
                if (w[2] == 0x10 || w[2] == 0x40) {
                    pending = true
                    ranging = w[2] == 0x40
                } else {
                    ranging = false
                }
            } else if (start == 0x0086 && w[2] == 0x01) {
                pending = ranging
            }
        }

        @Override
        synchronized byte[] writeRead(byte[] data, int n) throws IOException {
            int start = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF)
            byte[] out = new byte[n]
            for (int i = 0; i < n; i++) {
                int r = start + i
                out[i] = (byte) (r == 0x0031 ? (pending ? 0x00 : 0x01) : reg(r))
            }
            return out
        }
    }

    /** InputPin whose single handler the test triggers by hand. */
    static class FakePin implements InputPin {
        EdgeHandler handler

        @Override
        void onEdge(EdgeHandler handler, EdgeTrigger trigger) { this.handler = handler }

        @Override
        void offEdge(EdgeHandler handler) { this.handler = null }

        @Override
        void close() {}
    }

    def "rejects a wrong sensor ID and a firmware that never boots"() {
        when:
        def bad = new Sim()
        bad.set(0x0110, 0xCD)
        new VL53L1XMinimal(bad)

        then:
        thrown(IOException)

        when:
        def unbooted = new Sim()
        unbooted.set(0x00E5, 0x00)
        new VL53L1XMinimal(unbooted)

        then:
        thrown(IOException)
    }

    def "init writes the default configuration and the family overrides"() {
        given:
        def sim = new Sim()

        when:
        new VL53L1XMinimal(sim)

        then:
        (0..<91).every { int i -> sim.log[i].size() == 3 && ((sim.log[i][0] << 8) | sim.log[i][1]) == 0x2D + i }
        sim.writesTo(0x0046)[0] == 0x20
        sim.writesTo(0x0081)[0] == 0x9B
        [sim.reg(0x002E), sim.reg(0x002F), sim.reg(0x0030)] == [0x01, 0x01, 0x11]
        sim.writesTo(0x0087).takeRight(2) == [0x40, 0x00]
        sim.reg(0x0008) == 0x09
        sim.reg(0x000B) == 0x00
        !sim.ranging
    }

    def "single shot clears, starts and decodes"() {
        given:
        def sim = new Sim()
        def sensor = new VL53L1XMinimal(sim)
        sim.log.clear()

        expect:
        sensor.distance() == 250
        sensor.rangeValid()
        sim.log[0] == [0x00, 0x86, 0x01]
        sim.log[1] == [0x00, 0x87, 0x10]
        sim.log[-1] == [0x00, 0x86, 0x01]

        when:
        sim.set(0x0089, 4)

        then:
        sensor.distance() == 250
        !sensor.rangeValid()
    }

    def "measurement record, timing budget and distance mode"() {
        given:
        def sim = new Sim()
        def full = new VL53L1XFull(sim)
        full.distance()

        expect:
        full.readMeasurement() == new VL53L1XFull.Measurement(250, 0, 5.0d, 0.5d, 10.0d)
        full.rangeStatus() == 0
        full.timingBudget() == 100000
        full.distanceMode() == VL53L1XFull.DistanceMode.LONG

        when:
        sim.set(0x0089, 0x1F)
        def m = full.readMeasurement()
        sim.set(0x0089, 9)
        full.setTimingBudget(33000)

        then:
        m.rangeStatus == 255
        sim.reg16(0x005E) == 0x0060
        sim.reg16(0x0061) == 0x006E
        full.timingBudget() == 33000

        when:
        full.setTimingBudget(15000)

        then:
        thrown(IllegalArgumentException)

        when:
        full.setDistanceMode(VL53L1XFull.DistanceMode.SHORT)

        then:
        [sim.reg(0x004B), sim.reg(0x0060), sim.reg(0x0063), sim.reg(0x0069), sim.reg16(0x0078), sim.reg16(0x007A),
         sim.reg16(0x005E)] == [0x14, 0x07, 0x05, 0x38, 0x0705, 0x0606, 0x00D6]

        when:
        full.setTimingBudget(15000)
        full.setDistanceMode(VL53L1XFull.DistanceMode.LONG)

        then:
        sim.reg16(0x005E) == 0x001D
        thrown(IllegalArgumentException)

        when:
        full.setTimingBudget(100000)
        full.setDistanceMode(VL53L1XFull.DistanceMode.LONG)

        then:
        [sim.reg(0x004B), sim.reg16(0x0078), sim.reg16(0x005E), sim.reg16(0x0061)] == [0x0A, 0x0F0D, 0x01CC, 0x01EA]
    }

    def "continuous ranging and thresholds"() {
        given:
        def sim = new Sim()
        def full = new VL53L1XFull(sim)

        when:
        full.setInterMeasurement(200)

        then:
        ((sim.reg16(0x006C) << 16) | sim.reg16(0x006E)) == (0x50 * 200 * 1075).intdiv(1000)
        full.interMeasurement() == 200

        when:
        full.startContinuous()

        then:
        full.interMeasurement() == 100
        sim.log[-1] == [0x00, 0x87, 0x40]
        full.dataReady()
        full.readContinuous() == 250
        full.dataReady()

        when:
        full.stopContinuous()
        def stopWrite = sim.log[-1]
        full.startContinuous(50)
        def clamped = full.interMeasurement()
        full.stopContinuous()
        full.startContinuous(500)
        def kept = full.interMeasurement()
        full.stopContinuous()
        full.setInterruptThresholds(100, 801)

        then:
        stopWrite == [0x00, 0x87, 0x00]
        clamped == 100
        kept == 500
        sim.reg16(0x0074) == 100
        sim.reg16(0x0072) == 801
        full.interruptThresholds() == [100, 801] as int[]

        when:
        full.setInterruptThresholds(500, 100)

        then:
        thrown(IllegalArgumentException)
    }

    def "signal, sigma, ROI, offset and crosstalk"() {
        given:
        def sim = new Sim()
        def full = new VL53L1XFull(sim)

        expect:
        full.signalRateLimit() == 1.0d
        full.sigmaThreshold() == 90
        full.roi() == [16, 16] as int[]
        full.roiCenter() == 199
        full.opticalCenter() == 0x91

        when:
        full.setSignalRateLimit(0.25d)
        full.setSigmaThreshold(45)
        full.setRoiCenter(167)
        full.setRoi(8, 8)
        def smallRoi = sim.reg(0x0080)
        def keptCentre = full.roiCenter()
        full.setRoi(8, 16)
        full.setOffset(-10.25d)
        def negOffset = full.offset()
        def negRaw = sim.reg16(0x001E)
        full.setOffset(700.5d)
        full.setCrosstalkCompensation(0.01d)

        then:
        sim.reg16(0x0066) == 32
        sim.reg16(0x0064) == 180
        smallRoi == 0x77
        keptCentre == 167
        full.roi() == [8, 16] as int[]
        full.roiCenter() == 199
        negOffset == -10.25d
        negRaw == (-41 & 0x1FFF)
        full.offset() == 700.5d
        sim.reg16(0x0016) == 5120
        full.crosstalkCompensation() == 0.01d

        when:
        full.setOffset(1024.0d)

        then:
        thrown(IllegalArgumentException)
    }

    def "calibration helpers and temperature update"() {
        given:
        def sim = new Sim()
        def full = new VL53L1XFull(sim)

        when:
        def off = full.calibrateOffset(260)
        def clampedXtalk = full.calibrateCrosstalk(500)
        sim.set(0x0098, 0x00, 0x20)
        def xtalk = full.calibrateCrosstalk(500)
        sim.log.clear()
        full.recalibrate()

        then:
        off == 10.0d
        full.offset() == 10.0d
        !sim.ranging
        clampedXtalk == 0.127d
        Math.abs(xtalk - 0.0125d) < 1e-9
        sim.reg16(0x0016) == 6400
        sim.writesTo(0x0008) == [0x81, 0x09]
        sim.writesTo(0x000B) == [0x92, 0x00]
        sim.writesTo(0x0087) == [0x40, 0x00]
    }

    def "address, identification and interrupts"() {
        given:
        def sim = new Sim()
        def full = new VL53L1XFull(sim)
        def pin = new FakePin()
        def got = []

        when:
        full.setAddress(0x30)
        full.enableInterrupt(VL53L1XFull.SOURCE_IN_WINDOW)
        full.disableInterrupt(VL53L1XFull.SOURCE_LEVEL_LOW)
        def keptSource = sim.reg(0x0046)
        full.disableInterrupt(VL53L1XFull.SOURCE_IN_WINDOW)
        full.disableInterrupt(VL53L1XFull.SOURCE_NEW_SAMPLE_READY)
        def reverted = sim.reg(0x0046)
        sim.pending = false
        def none = full.pollInterrupt()
        full.enableInterrupt(VL53L1XFull.SOURCE_OUT_OF_WINDOW)
        sim.pending = true
        def fired = full.pollInterrupt()
        def cleared = !sim.pending
        full.enableInterrupt(VL53L1XFull.SOURCE_NEW_SAMPLE_READY)
        sim.pending = true
        full.onInterrupt({ int s -> got << s } as java.util.function.IntConsumer, pin)
        pin.handler.onEdge()
        full.offInterrupt()

        then:
        sim.reg(0x0001) == 0x30
        full.modelId() == 0xEA
        full.moduleType() == 0xCC
        full.revisionId() == 0x10
        keptSource == 0x03
        reverted == 0x20
        none == 0
        fired == VL53L1XFull.SOURCE_OUT_OF_WINDOW
        cleared
        got == [VL53L1XFull.SOURCE_NEW_SAMPLE_READY]
        pin.handler == null

        when:
        full.enableInterrupt(6)

        then:
        thrown(IllegalArgumentException)
    }
}
