package it.uhde.periph.chips.tof

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class VL6180XSpec extends Specification {

    /**
     * VL6180X simulator on top of {@link MockConnection} with explicit 16-bit register indices:
     * starting a measurement makes a result pending, SYSTEM__INTERRUPT_CLEAR drops it.
     */
    static class Sim extends MockConnection {
        final Map<Integer, Integer> regs = new ConcurrentHashMap<>()
        final List<List<Integer>> log = new CopyOnWriteArrayList<>()

        Sim(int fresh = 1) {
            setAddressWidth(2)
            set(0x000, 0xB4, 0x01, 0x03)
            set(0x016, fresh)
            set(0x062, 42)
            set(0x050, 0x01, 0x90)   // ALS 400 counts
        }

        void set(int reg, int... values) {
            values.eachWithIndex { int v, int i -> regs[reg + i] = v }
        }

        int reg(int r) {
            return regs.getOrDefault(r, 0)
        }

        List<Integer> writesTo(int r) {
            log.findAll { it.size() == 3 && ((it[0] << 8) | it[1]) == r }.collect { it[2] }
        }

        int last(int r) {
            List<Integer> w = writesTo(r)
            return w.isEmpty() ? -1 : w.last()
        }

        @Override
        synchronized void write(byte[] data) throws IOException {
            List<Integer> w = data.collect { (it as int) & 0xFF }
            log << w
            int start = (w[0] << 8) | w[1]
            for (int i = 2; i < w.size(); i++) regs[start + i - 2] = w[i]
            if (w.size() == 3) {
                if (start == 0x018 && (w[2] & 1) != 0) regs[0x04F] = (reg(0x04F) & ~0x07) | 4
                else if (start == 0x038 && (w[2] & 1) != 0) regs[0x04F] = (reg(0x04F) & ~0x38) | (4 << 3)
                else if (start == 0x015) regs[0x04F] = 0
                else if (start == 0x02E) regs[0x02E] = 0
            }
        }

        @Override
        synchronized byte[] writeRead(byte[] data, int n) throws IOException {
            int start = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF)
            byte[] out = new byte[n]
            for (int i = 0; i < n; i++) out[i] = (byte) reg(start + i)
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
        boolean isHigh() { return false }

        @Override
        void close() {}
    }

    def "init applies the private and recommended settings"() {
        given:
        def sim = new Sim(1)

        when:
        new VL6180XMinimal(sim)

        then:
        sim.writesTo(0x0207) == [0x01]
        sim.last(0x00DB) == 0xCE
        sim.last(0x016) == 0
        sim.last(0x011) == 0x10
        sim.last(0x10A) == 0x30
        sim.last(0x03F) == 0x46
        sim.last(0x031) == 0xFF
        sim.last(0x014) == 0x24
        sim.last(0x01B) == 0x09
        sim.last(0x03E) == 0x31
        sim.reg(0x040) == 0x00
        sim.reg(0x041) == 0x63
    }

    def "init skips the private settings when not fresh out of reset"() {
        given:
        def sim = new Sim(0)

        when:
        new VL6180XMinimal(sim)

        then:
        sim.writesTo(0x0207).isEmpty()
        sim.last(0x014) == 0x24
    }

    def "readRange returns mm and rejects range errors"() {
        given:
        def sim = new Sim(1)
        def chip = new VL6180XMinimal(sim)

        expect:
        chip.readRange() == 42
        sim.writesTo(0x018).contains(0x01)
        sim.last(0x015) == 0x07

        when:
        sim.set(0x04D, 0x70)
        chip.readRange()

        then:
        thrown(IOException)
        chip.lastError() == 7
    }

    def "full range status and ALS"() {
        given:
        def sim = new Sim(1)
        def full = new VL6180XFull(sim)

        when:
        sim.set(0x04D, 0x60)
        def status = full.readRangeStatus()
        sim.set(0x04D, 0x00)

        then:
        status == [42, 6] as int[]
        full.readAlsRaw() == 400
        Math.abs(full.readLux() - 0.32 * 400 / 1.01) < 1e-9

        when:
        full.setAlsGain(0)
        full.setAlsIntegrationTime(50)

        then:
        sim.last(0x03F) == 0x40
        sim.reg(0x040) == 0x00
        sim.reg(0x041) == 0x31
        Math.abs(full.readLux() - 0.32 * (400 / 20.0) * 2) < 1e-9
        sim.writesTo(0x017).takeRight(2) == [1, 0]
    }

    def "argument validation"() {
        given:
        def full = new VL6180XFull(new Sim(1))

        when:
        call.call(full)

        then:
        thrown(IllegalArgumentException)

        where:
        call << [
            { VL6180XFull f -> f.setAlsGain(8) },
            { VL6180XFull f -> f.setAlsIntegrationTime(513) },
            { VL6180XFull f -> f.setAlsScaler(17) },
            { VL6180XFull f -> f.startContinuousRange(5) },
            { VL6180XFull f -> f.startContinuousAls(2560) },
            { VL6180XFull f -> f.setRangeThresholds(90, 80) },
            { VL6180XFull f -> f.setAlsThresholds(2, 1) },
            { VL6180XFull f -> f.setMaxConvergenceTime(64) },
            { VL6180XFull f -> f.setRangeOffset(128) },
            { VL6180XFull f -> f.setCrosstalkRate(-1.0d) },
            { VL6180XFull f -> f.setAddress(0x80) },
            { VL6180XFull f -> f.enableInterrupt(VL6180XMinimal.SOURCE_ERROR, 1) },
            { VL6180XFull f -> f.enableInterrupt(VL6180XMinimal.SOURCE_RANGE, 5) },
        ]
    }

    def "continuous modes"() {
        given:
        def sim = new Sim(1)
        def full = new VL6180XFull(sim)

        when:
        full.startContinuousRange(50)

        then:
        sim.last(0x01B) == 0x04
        sim.last(0x018) == 0x03

        when:
        sim.regs[0x04F] = 4
        def mm = full.readRangeContinuous()
        full.stopContinuousRange()

        then:
        mm == 42
        sim.last(0x018) == 0x01

        when:
        full.startContinuousAls(100)

        then:
        sim.last(0x03E) == 0x09
        sim.last(0x038) == 0x03

        when:
        full.stopContinuousAls()
        full.setInterleaved(true)

        then:
        sim.last(0x038) == 0x01
        sim.last(0x2A3) == 1

        when:
        full.setInterleaved(false)

        then:
        sim.last(0x2A3) == 0
    }

    def "thresholds, tuning, offset and crosstalk"() {
        given:
        def sim = new Sim(1)
        def full = new VL6180XFull(sim)

        when:
        full.setRangeThresholds(30, 80)
        full.setAlsThresholds(100, 5000)
        full.setMaxConvergenceTime(20)
        full.setAveragingPeriod(100)
        full.setAlsScaler(4)
        full.setRangeOffset(-5)
        full.setCrosstalkRate(0.5d)
        full.recalibrateVhv()

        then:
        sim.last(0x019) == 80
        sim.last(0x01A) == 30
        [sim.reg(0x03A), sim.reg(0x03B), sim.reg(0x03C), sim.reg(0x03D)] == [0x13, 0x88, 0x00, 100]
        sim.last(0x01C) == 20
        sim.last(0x10A) == 100
        sim.last(0x120) == 4
        sim.last(0x024) == 0xFB
        full.getRangeOffset() == -5
        [sim.reg(0x01E), sim.reg(0x01F)] == [0x00, 0x40]
        sim.last(0x02E) == 0x01
    }

    def "address, identification and history"() {
        given:
        def sim = new Sim(1)
        def full = new VL6180XFull(sim)

        when:
        full.setAddress(0x30)
        sim.set(0x052, 0, 1, 0, 2, 0, 3, 0, 4, 0, 5, 0, 6, 0, 7, 1, 0)

        then:
        sim.last(0x212) == 0x30
        full.modelId() == 0xB4
        full.revision() == [1, 3] as int[]
        full.readHistory() == [1, 2, 3, 4, 5, 6, 7, 256] as int[]
    }

    def "interrupt modes and polling"() {
        given:
        def sim = new Sim(1)
        def full = new VL6180XFull(sim)
        sim.regs[0x014] = 0x24

        when:
        full.enableInterrupt(VL6180XMinimal.SOURCE_RANGE, VL6180XMinimal.MODE_OUT_OF_WINDOW)

        then:
        sim.last(0x014) == 0x23

        when:
        full.enableInterrupt(VL6180XMinimal.SOURCE_ALS, VL6180XMinimal.MODE_LEVEL_LOW)

        then:
        sim.last(0x014) == 0x0B

        when:
        full.disableInterrupt(VL6180XMinimal.SOURCE_RANGE)

        then:
        sim.last(0x014) == 0x08

        when:
        sim.regs[0x04F] = 0x04
        def range = full.pollInterrupt()
        def cleared = sim.reg(0x04F)
        sim.regs[0x04F] = 0x24
        def both = full.pollInterrupt()
        sim.regs[0x04F] = 0x40
        def error = full.pollInterrupt()
        def none = full.pollInterrupt()

        then:
        range == VL6180XMinimal.SOURCE_RANGE
        cleared == 0
        both == (VL6180XMinimal.SOURCE_RANGE | VL6180XMinimal.SOURCE_ALS)
        error == VL6180XMinimal.SOURCE_ERROR
        none == 0
    }

    def "onInterrupt delivers through the pin and the polling fallback"() {
        given:
        def sim = new Sim(1)
        def full = new VL6180XFull(sim)
        def pin = new FakePin()
        List<Integer> got = new CopyOnWriteArrayList<>()

        when:
        full.onInterrupt({ int s -> got << s } as java.util.function.IntConsumer, pin)
        sim.regs[0x04F] = 0x04
        pin.handler.onEdge()
        full.offInterrupt()

        then:
        got == [VL6180XMinimal.SOURCE_RANGE]
        pin.handler == null

        when:
        List<Integer> polled = new CopyOnWriteArrayList<>()
        sim.regs[0x04F] = 0x04
        full.onInterrupt({ int s -> polled << s } as java.util.function.IntConsumer, null)
        Thread.sleep(50)
        full.offInterrupt()

        then:
        polled.contains(VL6180XMinimal.SOURCE_RANGE)
    }
}
