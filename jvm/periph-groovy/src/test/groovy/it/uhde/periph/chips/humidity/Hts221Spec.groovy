package it.uhde.periph.chips.humidity

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

import java.util.concurrent.atomic.AtomicInteger

class Hts221Spec extends Specification {

    /** Mock that clears CTRL_REG2.BOOT like the real chip and can expose an INT pin. */
    static class ChipMock extends MockConnection {
        boolean stuckBoot = false
        InputPin pin = null

        @Override
        void write(byte[] data) throws IOException {
            super.write(data)
            if (!stuckBoot && data.length == 2 && (data[0] & 0xFF) == 0x21) {
                registers().put(0x21, (data[1] & 0xFF) & ~0x80)
            }
        }

        @Override
        InputPin intPin() { return pin }
    }

    static class FakePin implements InputPin {
        EdgeHandler handler
        EdgeTrigger trigger
        @Override void onEdge(EdgeHandler h, EdgeTrigger t) { handler = h; trigger = t }
        @Override void offEdge(EdgeHandler h) { if (handler == h) handler = null }
        @Override boolean isHigh() { return false }
        @Override void close() {}
    }

    ChipMock mock

    def setup() {
        mock = new ChipMock()
        // Calibration block at 0xB0 (sub-address with auto-increment):
        // H0_rH_x2=40, H1_rH_x2=80, T0_x8=80, T1_x8=160, msb=0,
        // H0_T0_OUT=0x4000, H1_T0_OUT=0x6000, T0_OUT=300, T1_OUT=500
        mock.setRegister(0xB0, 40, 80, 80, 160, 0, 0, 0x00, 0x40, 0, 0, 0x00, 0x60, 0x2C, 0x01, 0xF4, 0x01)
        mock.setRegister(0x0F, 0xBC)
        mock.setRegister(0x27, 0x03)
        // Burst 0xA8: H_OUT = 0x5000 (30 %rH), T_OUT = 400 (15.0 degC)
        mock.setRegister(0xA8, 0x00, 0x50, 0x90, 0x01)
    }

    private boolean wrote(int reg, int value) {
        return mock.writes().any { byte[] w -> w.length == 2 && (w[0] & 0xFF) == reg && (w[1] & 0xFF) == value }
    }

    def "constructor reads the calibration and starts one-shot mode"() {
        when:
        new Hts221Minimal(mock)
        then:
        wrote(0x20, 0x84)
        (mock.writes()[0][0] & 0xFF) == 0xB0
    }

    def "read converts with the calibration"() {
        when:
        double[] v = new Hts221Minimal(mock).read()
        then:
        Math.abs(v[0] - 15.0d) < 1e-3
        Math.abs(v[1] - 30.0d) < 1e-3
        wrote(0x21, 0x01)
    }

    def "humidity is clamped to 0..100"() {
        given:
        mock.setRegister(0xB6, 0x00, 0x00, 0, 0, 0x00, 0x10) // H0_T0_OUT=0, H1_T0_OUT=0x1000
        def s = new Hts221Minimal(mock)
        mock.setRegister(0xA8, 0xFF, 0x7F, 0, 0)
        expect:
        s.read()[1] == 100.0d
        when:
        mock.setRegister(0xB6, 0x00, 0x40, 0, 0, 0x00, 0x60)
        def s2 = new Hts221Minimal(mock)
        mock.setRegister(0xA8, 0x00, 0x80, 0, 0)
        then:
        s2.read()[1] == 0.0d
    }

    def "read times out when never ready"() {
        given:
        mock.setRegister(0x27, 0x00)
        def s = new Hts221Minimal(mock)
        when:
        s.read()
        then:
        thrown(IOException)
    }

    def "calibration decodes the temperature MSB bits"() {
        given:
        mock.setRegister(0xB5, 0x0D) // T0 bits 9:8 = 01, T1 bits 9:8 = 11
        when:
        double[] cal = new Hts221Full(mock).readCalibration()
        then:
        cal[2] == (256 + 80) / 8.0d
        cal[3] == (768 + 160) / 8.0d
    }

    def "single values, raw outputs and continuous read"() {
        given:
        def f = new Hts221Full(mock)
        expect:
        Math.abs(f.readHumidity() - 30.0d) < 1e-3
        Math.abs(f.readTemperature() - 15.0d) < 1e-3
        f.readRawHumidity() == 0x5000
        f.readRawTemperature() == 400
        when:
        mock.setRegister(0xA8, 0x00, 0xF0, 0, 0)
        then:
        f.readRawHumidity() == -4096
        when:
        mock.setRegister(0xA8, 0x00, 0x50, 0x90, 0x01)
        double[] c = f.readContinuous()
        then:
        Math.abs(c[0] - 15.0d) < 1e-3
        Math.abs(c[1] - 30.0d) < 1e-3
    }

    def "calibration values"() {
        expect:
        Arrays.equals(new Hts221Full(mock).readCalibration(),
                [20.0d, 40.0d, 10.0d, 20.0d, (double) 0x4000, (double) 0x6000, 300.0d, 500.0d] as double[])
    }

    def "output data rate"() {
        given:
        def f = new Hts221Full(mock)
        when:
        f.setOutputDataRate(hz)
        then:
        mock.registers().get(0x20) == ctrl
        f.getOutputDataRate() == hz
        where:
        hz     | ctrl
        12.5d  | 0x87
        7.0d   | 0x86
        1.0d   | 0x85
        0.0d   | 0x84
    }

    def "invalid output data rate is rejected"() {
        when:
        new Hts221Full(mock).setOutputDataRate(5.0d)
        then:
        thrown(IllegalArgumentException)
    }

    def "averaging"() {
        given:
        def f = new Hts221Full(mock)
        when:
        f.setAveraging(t, h)
        then:
        mock.registers().get(0x10) == reg
        Arrays.equals(f.getAveraging(), [t, h] as int[])
        where:
        t   | h   | reg
        256 | 512 | 0x3F
        2   | 4   | 0x00
        16  | 32  | 0x1B
    }

    def "invalid averaging is rejected"() {
        when:
        new Hts221Full(mock).setAveraging(t, h)
        then:
        thrown(IllegalArgumentException)
        where:
        t  | h
        3  | 32
        16 | 2
        1  | 32
        16 | 1024
    }

    def "block data update, power, heater and DRDY"() {
        given:
        def f = new Hts221Full(mock)
        when:
        f.setBlockDataUpdate(false)
        then:
        mock.registers().get(0x20) == 0x80
        when:
        f.setBlockDataUpdate(true)
        f.powerDown()
        then:
        mock.registers().get(0x20) == 0x04
        when:
        f.powerUp()
        then:
        mock.registers().get(0x20) == 0x84
        when:
        f.setHeater(true)
        then:
        f.isHeaterOn()
        when:
        f.setHeater(false)
        then:
        !f.isHeaterOn()
        when:
        f.configureDrdy(true, true, false)
        then:
        mock.registers().get(0x22) == 0x04
        when:
        f.configureDrdy(true, false, true)
        then:
        mock.registers().get(0x22) == 0xC4
        when:
        f.configureDrdy(false, true, false)
        then:
        mock.registers().get(0x22) == 0x00
    }

    def "reboot re-reads the calibration and times out when BOOT is stuck"() {
        given:
        def f = new Hts221Full(mock)
        mock.setRegister(0xB0, 60)
        when:
        f.reboot()
        then:
        f.readCalibration()[0] == 30.0d
        when:
        mock.stuckBoot = true
        f.reboot()
        then:
        thrown(IOException)
    }

    def "identity, status and dew point"() {
        given:
        def f = new Hts221Full(mock)
        double g = Math.log(0.30d) + 17.62d * 15.0d / (243.12d + 15.0d)
        expect:
        f.whoAmI() == 0xBC
        f.status() == 0x03
        f.dataReady()
        f.pollInterrupt() == 0x03
        Math.abs(f.dewPoint() - 243.12d * g / (17.62d - g)) < 1e-9
    }

    def "interrupt through the INT pin"() {
        given:
        def pin = new FakePin()
        mock.pin = pin
        def f = new Hts221Full(mock)
        def got = new AtomicInteger(-1)
        when:
        f.onInterrupt({ int s -> got.set(s) } as java.util.function.IntConsumer)
        then:
        mock.registers().get(0x22) == 0x04
        pin.trigger == EdgeTrigger.RISING
        when:
        pin.handler.onEdge()
        then:
        got.get() == 0x03
        when:
        f.offInterrupt()
        then:
        pin.handler == null
        mock.registers().get(0x22) == 0x00
    }

    def "interrupt polling fallback"() {
        given:
        def f = new Hts221Full(mock)
        def got = new AtomicInteger(-1)
        when:
        f.onInterrupt({ int s -> got.set(s) } as java.util.function.IntConsumer)
        long end = System.currentTimeMillis() + 1000
        while (got.get() < 0 && System.currentTimeMillis() < end) Thread.sleep(10)
        f.offInterrupt()
        then:
        got.get() == 0x03
    }
}
