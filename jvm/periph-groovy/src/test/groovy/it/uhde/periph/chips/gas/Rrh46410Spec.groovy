package it.uhde.periph.chips.gas

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Rrh46410Spec extends Specification {

    static byte[] b(int... v) {
        byte[] r = new byte[v.length]
        for (int i = 0; i < v.length; i++) r[i] = (byte) v[i]
        r
    }

    static int cs(byte[] d, int len) {
        int s = 0
        for (int i = 0; i < len; i++) s += d[i] & 0xFF
        (~s) & 0xFF
    }

    /** Simulates the RRH46410 request/response protocol with checksums. */
    static class Sim extends MockConnection {
        List<byte[]> written = []
        byte[] response
        int mode = 0, counter = 0, gpio = 0x0C, gpioDir = 0, humidity = -1, pid = 0x2310, failWrites = 0
        int statusOverride = -1
        boolean hasResult = false, badChecksum = false, requestsOk = true
        InputPin pin

        synchronized void sample(int n) {
            hasResult = true
            counter = (counter + n) & 0xFF
        }

        @Override
        synchronized void write(byte[] data) throws IOException {
            written << data.clone()
            if (failWrites > 0) {
                failWrites--
                throw new IOException('nack')
            }
            if (cs(data, data.length - 1) != (data[data.length - 1] & 0xFF)) requestsOk = false
            int cmd = data[0] & 0xFF
            byte[] p = Arrays.copyOfRange(data, 1, data.length - 1)
            int status = 0
            byte[] out = new byte[0]
            switch (cmd) {
                case 0x00: out = b(pid >> 8, pid & 0xFF, 1, 2, 3); break
                case 0x01: out = b(1, 2, 3, 4, 5, 6); break
                case 0x10: out = b(mode); break
                case 0x11: mode = p[0] & 0xFF; counter = 0; hasResult = false; break
                case 0x12: humidity = p[0] & 0xFF; break
                case 0x18:
                    if (!hasResult) status = 0x10
                    out = mode == 0x05 ? b(counter, 0x01, 0xF4, 0x0B, 0xB8) : b(counter, 25, 0x00, 0x32, 0x01, 0x2C, 0x02, 0x58, 3)
                    break
                case 0x20: gpioDir = p[0] & 0xFF; break
                case 0x21: out = b(gpio); break
                case 0x22: gpio |= p[0]; break
                case 0x23: gpio &= ~p[0]; break
                case 0x8F: mode = 0; response = null; return
                default: status = 0x80
            }
            if (statusOverride >= 0) status = statusOverride
            byte[] r = new byte[out.length + 2]
            r[0] = (byte) status
            System.arraycopy(out, 0, r, 1, out.length)
            int c = cs(r, out.length + 1)
            if (badChecksum) c ^= 1
            r[out.length + 1] = (byte) c
            response = r
        }

        @Override
        synchronized byte[] read(int n) {
            byte[] out = new byte[n]
            Arrays.fill(out, (byte) 0xFF)
            if (response != null) System.arraycopy(response, 0, out, 0, Math.min(n, response.length))
            out
        }

        @Override
        InputPin intPin() { pin }
    }

    static class Pin implements InputPin {
        EdgeHandler handler
        EdgeTrigger trigger
        boolean high
        @Override void onEdge(EdgeHandler h, EdgeTrigger t) { handler = h; trigger = t }
        @Override void offEdge(EdgeHandler h) { if (handler.is(h)) handler = null }
        @Override boolean isHigh() { high }
        @Override void close() {}
    }

    def "init sends checksummed requests and selects IAQ 2nd Gen"() {
        given:
        def sim = new Sim()
        when:
        new Rrh46410Minimal(sim).init()
        then:
        sim.written[0] == b(0x00, 0xFF)
        sim.written[1] == b(0x11, 0x01, 0xED)
        sim.requestsOk
        sim.mode == 1
    }

    def "read decodes the IAQ 2nd Gen result"() {
        given:
        def sim = new Sim()
        def m = new Rrh46410Minimal(sim)
        m.init()
        sim.sample(5)
        when:
        def r = m.read()
        then:
        Math.abs(r.iaq() - 2.5d) < 1e-9
        Math.abs(r.tvocMgM3() - 0.5d) < 1e-9
        Math.abs(r.etohPpm() - 3.0d) < 1e-9
        r.eco2Ppm() == 600
        r.relativeIaq() == 30
        r.sample() == 5
        !r.warmedUp()
        sim.written[-1] == b(0x18, 0xE7)
    }

    def "warm-up counts samples across the counter wrap"() {
        given:
        def sim = new Sim()
        def m = new Rrh46410Minimal(sim)
        m.init()
        when:
        sim.sample(100)
        def r1 = m.read()
        sim.sample(200)
        def r2 = m.read()
        then:
        r1.warmedUp() && r1.sample() == 100
        r2.warmedUp() && r2.sample() == 44
    }

    def "a wrong product ID is rejected"() {
        given:
        def sim = new Sim(pid: 0x1234)
        when:
        new Rrh46410Minimal(sim).init()
        then:
        thrown(Rrh46410Minimal.Rrh46410Exception)
    }

    def "init retries until the module answers"() {
        given:
        def sim = new Sim(failWrites: 3)
        when:
        new Rrh46410Minimal(sim).init()
        then:
        sim.mode == 1
    }

    def "checksum and status errors are reported"() {
        given:
        def sim = new Sim()
        def m = new Rrh46410Minimal(sim)
        m.init()
        sim.sample(1)
        when:
        sim.badChecksum = true
        m.read()
        then:
        thrown(Rrh46410Minimal.Rrh46410Exception)
        when:
        sim.badChecksum = false
        sim.statusOverride = 0x0A
        m.read()
        then:
        def e = thrown(Rrh46410Minimal.Rrh46410Exception)
        e.status() == 0x0A
    }

    def "identity, mode and results"() {
        given:
        def sim = new Sim()
        def f = new Rrh46410Full(sim)
        f.pollMs = 1
        f.init()
        expect:
        f.productId() == new Rrh46410Full.ProductId(0x2310, 1, 2, 3)
        f.trackingNumber() == b(1, 2, 3, 4, 5, 6)
        f.getMode() == Rrh46410Full.MODE_IAQ_2ND_GEN
        f.readResults() == null
        f.status().notComplete()
        when:
        sim.sample(3)
        then:
        f.readResults().sample() == 3
        !f.waitForSample(0.005d)
        when:
        sim.sample(1)
        then:
        f.pollInterrupt()
        f.waitForSample(0.1d)
        f.lastResult().sample() == 4
        !f.pollInterrupt()
    }

    def "humidity, modes and timing"() {
        given:
        def sim = new Sim()
        def f = new Rrh46410Full(sim)
        f.init()
        when:
        f.setHumidity(50)
        then:
        sim.humidity == 128
        when:
        f.setHumidity(101)
        then:
        thrown(IllegalArgumentException)
        when:
        f.setMode(Rrh46410Full.MODE_PBAQ)
        sim.sample(2)
        def p = f.readResults()
        then:
        sim.mode == 5
        f.samplePeriod() == 5.0d
        f.warmupSamples() == 60
        f.samplePeriod(Rrh46410Full.MODE_ULP) == 90.0d
        f.warmupSamples(Rrh46410Full.MODE_ULP) == 10
        f.warmupSamples(Rrh46410Full.MODE_IAQ_2ND_GEN) == 100
        p.pbaq()
        Math.abs(p.tvocMgM3() - 0.5d) < 1e-9
        when:
        f.setMode(0x04)
        then:
        thrown(IllegalArgumentException)
    }

    def "air quality label #iaq is #label"() {
        expect:
        Rrh46410Full.airQualityLabel(iaq) == label
        where:
        iaq  | label
        1.5d | 'very good'
        2.0d | 'good'
        3.9d | 'medium'
        4.0d | 'poor'
        5.0d | 'bad'
    }

    def "GPIO, suspend, cleaning guard, raw command and reset"() {
        given:
        def sim = new Sim()
        def f = new Rrh46410Full(sim)
        f.init()
        when:
        f.configGpio(3)
        f.setGpio(1)
        def high = f.getGpio() & 1
        f.clearGpio(1)
        def low = f.getGpio() & 1
        then:
        sim.gpioDir == 3
        high == 1
        low == 0
        when:
        f.setGpio(4)
        then:
        thrown(IllegalArgumentException)
        when:
        f.suspend()
        then:
        sim.mode == 0
        when:
        f.cleanSensor(false)
        then:
        thrown(IllegalArgumentException)
        expect:
        f.sendCommand(0x21, new byte[0], 1) == b(sim.gpio)
        when:
        f.sendCommand(0x55, new byte[0], 0)
        then:
        f.status().invalidCommand()
        when:
        f.reset()
        then:
        sim.written.any { Arrays.equals(it, b(0x8F, 0x70)) }
        f.samplePeriod() == 0.0d
    }

    def "interrupt delivers decoded samples"() {
        given:
        def sim = new Sim()
        def pin = new Pin()
        def f = new Rrh46410Full(sim)
        f.init()
        def hits = []
        when:
        f.onInterrupt({ hits << it } as java.util.function.Consumer, pin)
        sim.sample(1)
        pin.handler.onEdge()
        pin.handler.onEdge()
        then:
        pin.trigger == EdgeTrigger.RISING
        hits.size() == 1
        when:
        f.offInterrupt()
        then:
        pin.handler == null
        when:
        f.pollMs = 2
        def polled = Collections.synchronizedList([])
        f.onInterrupt({ polled << it } as java.util.function.Consumer, null)
        sim.sample(1)
        for (int i = 0; i < 200 && polled.isEmpty(); i++) Thread.sleep(5)
        f.offInterrupt()
        then:
        polled[0].sample() == 2
        when:
        sim.pin = pin
        def low = f.pollInterrupt()
        pin.high = true
        then:
        !low
        f.pollInterrupt()
    }
}
