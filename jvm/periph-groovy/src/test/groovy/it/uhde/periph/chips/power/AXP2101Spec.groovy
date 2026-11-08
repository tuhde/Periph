package it.uhde.periph.chips.power

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class AXP2101Spec extends Specification {

    MockConnection connection(List<Integer> regValuePairs = []) {
        def c = new MockConnection()
        c.setRegister(0x30, 0x03)   // ADC_ENABLE reset
        c.setRegister(0x18, 0x0A)   // MODULE_ENABLE reset
        for (int i = 0; i < regValuePairs.size(); i += 2) {
            c.setRegister(regValuePairs[i], regValuePairs[i + 1])
        }
        return c
    }

    int lastWriteTo(MockConnection connection, int reg) {
        def writes = connection.writes()
        for (int i = writes.size() - 1; i >= 0; i--) {
            def w = writes.get(i)
            if (w.length == 2 && (w[0] & 0xFF) == reg) return w[1] & 0xFF
        }
        return -1
    }

    /** Preload registers, run the action on a fresh Full driver and return the last write per expected register. */
    boolean expectWrites(List<Integer> regs, List<Integer> expected, Closure action) {
        def c = connection(regs)
        def f = new AXP2101Full(c)
        c.writes().clear()
        action.call(f)
        boolean ok = true
        for (int i = 0; i < expected.size(); i += 2) {
            if (lastWriteTo(c, expected[i]) != expected[i + 1]) {
                println "register 0x${Integer.toHexString(expected[i])}: expected ${expected[i + 1]} got ${lastWriteTo(c, expected[i])}"
                ok = false
            }
        }
        return ok
    }

    boolean rejects(Closure action) {
        def f = new AXP2101Full(connection([0x64, 3]))
        try {
            action.call(f)
        } catch (IllegalArgumentException ignored) {
            return true
        }
        return false
    }

    def "minimal init and reads"() {
        given:
        def c = connection([0x34, 0x0F, 0x35, 0xA0, 0xA4, 87, 0x00, 0x20, 0x01, 0x22])
        def m = new AXP2101Minimal(c)

        expect:
        lastWriteTo(c, 0x30) == 0x1F
        lastWriteTo(c, 0x18) == 0x0A
        Math.abs(m.batteryVoltage() - 4.0d) < 1e-9d
        m.batteryPercent() == 87
        m.chargeState() == AXP2101Minimal.CHARGE_CC
        m.vbusPresent()

        when:
        c.setRegister(0x00, 0x00)

        then:
        !m.vbusPresent()
    }

    def "init preserves gauge bits and masks debug bits"() {
        given:
        def c = connection([0x18, 0x02, 0x34, 0xCF, 0x35, 0xA0])
        def m = new AXP2101Minimal(c)

        expect:
        lastWriteTo(c, 0x18) == 0x0A
        Math.abs(m.batteryVoltage() - 4.0d) < 1e-9d
    }

    def "bus failure throws"() {
        given:
        def dead = new MockConnection() {
            @Override
            byte[] writeRead(byte[] data, int n) throws IOException {
                throw new IOException('nack')
            }
        }

        when:
        new AXP2101Minimal(dead)

        then:
        thrown(IOException)
    }

    def "full measurements and status"() {
        given:
        def c = connection([0x34, 0x0F, 0x35, 0xA0, 0x36, 0x03, 0x37, 0xE8, 0x38, 0x13, 0x39, 0x88,
                            0x3A, 0x10, 0x3B, 0x68, 0x3C, 0x1C, 0x3D, 0x6A, 0xA4, 50, 0x00, 0x3F, 0x01, 0x4B])
        def f = new AXP2101Full(c)

        when:
        def all = f.readAll()
        def s = f.status()

        then:
        Math.abs(f.vbusVoltage() - 5.0d) < 1e-9d
        Math.abs(f.systemVoltage() - 4.2d) < 1e-9d
        Math.abs(f.tsVoltage() - 0.5d) < 1e-9d
        Math.abs(f.dieTemperature() - 22.0d) < 1e-9d
        all.batteryPercent == 50
        Math.abs(all.batteryVoltage - 4.0d) < 1e-9d
        s.vbusGood && s.batfetOpen && s.batteryPresent && s.batteryActive && s.thermalRegulation && s.currentLimit
        s.batteryDirection == 'discharge'
        !s.systemOn && s.vindpm
        s.chargeState == 3
    }

    def "get rail"() {
        given:
        def f = new AXP2101Full(connection([0x80, 0x0F, 0x83, 0x80 | 18]))

        when:
        def rail = f.getRail('dcdc2')

        then:
        rail.enabled
        Math.abs(rail.volts - 0.68d) < 1e-9d

        when:
        f.getRail('nope')

        then:
        thrown(IllegalArgumentException)
    }

    def "charger setters"() {
        expect:
        expectWrites([], [0x30, 0x25]) { f -> f.setAdcChannels(true, false, true, false, false, true) }
        expectWrites([0x36, 0], [0x36, 0x80]) { f -> f.setAdcLowFrequency(true) }
        expectWrites([0x18, 0x0A], [0x18, 0x08]) { f -> f.setCharging(false) }
        expectWrites([], [0x64, 3]) { f -> f.setChargeVoltage(4.2d) }
        expectWrites([], [0x64, 5]) { f -> f.setChargeVoltage(4.4d) }
        expectWrites([0x64, 3], [0x62, 11]) { f -> f.setChargeCurrent(0.5d) }
        expectWrites([0x64, 3], [0x62, 4]) { f -> f.setChargeCurrent(0.1d) }
        expectWrites([0x64, 3], [0x62, 20]) { f -> f.setChargeCurrent(1.4d) }
        expectWrites([0x64, 1], [0x62, 21]) { f -> f.setChargeCurrent(1.5d) }
        expectWrites([0x61, 0xF0], [0x61, 0xF5]) { f -> f.setPrechargeCurrent(0.125d) }
        expectWrites([0x63, 0xE0], [0x63, 0xF2]) { f -> f.setTermination(0.05d, true) }
        expectWrites([0x63, 0xE0], [0x63, 0xE2]) { f -> f.setTermination(0.05d, false) }
        expectWrites([], [0x16, 5]) { f -> f.setInputCurrentLimit(2.0d) }
        expectWrites([], [0x15, 6]) { f -> f.setInputVoltageLimit(4.36d) }
        expectWrites([], [0x14, 5]) { f -> f.setMinSystemVoltage(3.7d) }
        expectWrites([0x67, 0x08], [0x67, 0xEE]) { f -> f.setChargeTimers(60, 12, true, true, true) }
        expectWrites([0x67, 0x08], [0x67, 0x3B]) { f -> f.setChargeTimers(70, 20, false, false, false) }
        expectWrites([], [0x65, 3]) { f -> f.setThermalRegulation(120) }
        expectWrites([], [0x13, 0x04]) { f -> f.setDieProtection(135, false) }
        expectWrites([0x68, 1], [0x68, 0]) { f -> f.setBatteryDetection(false) }
        expectWrites([0x6A, 0xF8], [0x6A, 0xFF, 0x18, 0x0E]) { f -> f.setBackupCharging(true, 3.3d) }
        expectWrites([0x69, 0xC0], [0x69, 0xE5]) { f -> f.setChgled('manual', 'blink_4hz') }
        expectWrites([0x69, 0xC0], [0x69, 0xC3]) { f -> f.setChgled('type_b', 'hiz') }
        expectWrites([0x69, 0xC1], [0x69, 0xC0]) { f -> f.setChgled('off', 'hiz') }
    }

    def "temperature setters"() {
        expect:
        expectWrites([0x50, 0x20], [0x50, 0x3F]) { f -> f.configureTs(false, 60, 'always') }
        expectWrites([], [0x54, 41, 0x55, 88, 0x56, 62, 0x57, 76]) { f -> f.setTsLimits(1.312d, 0.176d, 1.984d, 0.152d) }
        expectWrites([], [0x52, 2, 0x53, 1]) { f -> f.setTsHysteresis(0.032d, 0.004d) }
        expectWrites([0x59, 0x80], [0x59, 0x94, 0x5A, 50, 0x5B, 30, 0x58, 1]) { f ->
            f.configureJeita(true, 0.8d, 0.24d, true, false, 0, 1)
        }
    }

    def "rail setters"() {
        expect:
        expectWrites([0x83, 0x80], [0x83, 0x80 | 85]) { f -> f.setRailVoltage('dcdc2', 1.5d) }
        expectWrites([0x83, 0x80], [0x83, 0x80 | 70]) { f -> f.setRailVoltage('dcdc2', 1.2d) }
        expectWrites([], [0x82, 18]) { f -> f.setRailVoltage('dcdc1', 3.3d) }
        expectWrites([], [0x85, 102]) { f -> f.setRailVoltage('dcdc4', 1.84d) }
        expectWrites([], [0x93, 28]) { f -> f.setRailVoltage('aldo2', 3.3d) }
        expectWrites([], [0x98, 10]) { f -> f.setRailVoltage('cpusldo', 1.0d) }
        expectWrites([], [0x99, 28]) { f -> f.setRailVoltage('dldo1', 3.3d) }
        expectWrites([], [0x9A, 18]) { f -> f.setRailVoltage('dldo2', 1.4d) }
        expectWrites([0x90, 0], [0x90, 0x20]) { f -> f.setRailEnabled('bldo2', true) }
        expectWrites([0x90, 0x20], [0x90, 0xA0]) { f -> f.setRailEnabled('dldo1', true) }
        expectWrites([0x90, 0xA0], [0x90, 0x80]) { f -> f.setRailEnabled('bldo2', false) }
        expectWrites([0x91, 0], [0x91, 0x01]) { f -> f.setRailEnabled('dldo2', true) }
        expectWrites([0x81, 0], [0x81, 0x10, 0x84, 0x80]) { f -> f.configureDcdc('dcdc3', true, Boolean.TRUE) }
        expectWrites([0x80, 0x0F, 0x81, 0x10], [0x80, 0x6F, 0x81, 0xD3]) { f ->
            f.configureDcdcGlobal(true, true, true, true, 240)
        }
    }

    def "system setters"() {
        expect:
        expectWrites([0x10, 0x30], [0x10, 0x10]) { f -> f.setOffDischarge(false) }
        expectWrites([0x10, 0x30], [0x10, 0x31]) { f -> f.powerOff() }
        expectWrites([0x10, 0x30], [0x10, 0x32]) { f -> f.restart() }
        expectWrites([0x26, 0x08], [0x26, 0x09]) { f -> f.sleep() }
        expectWrites([0x26, 0x08], [0x26, 0x0A]) { f -> f.wakeup() }
        expectWrites([0x26, 0x03], [0x26, 0x17]) { f -> f.configureSleep(true, false, true) }
        expectWrites([0x27, 0xC0, 0x22, 0x04, 0x10, 0x30], [0x27, 0xFC, 0x22, 0x07, 0x10, 0x34]) { f ->
            f.configurePowerKey(128, 10d, 2.5d, true, true, true)
        }
        expectWrites([0x22, 0x03, 0x23, 0], [0x22, 0x03, 0x24, 7, 0x23, 0x25]) { f ->
            f.configurePowerOff(false, 3.3d, true, 0x05)
        }
        expectWrites([0x25, 0x20, 0x10, 0x30], [0x25, 0x3F, 0x10, 0x38]) { f -> f.configurePwrok(true, 64, true, true, true) }
        expectWrites([0x12, 0], [0x12, 0x08]) { f -> f.setBatfetOffEnable(true) }
        expectWrites([0x28, 0xFF, 0x2B, 0x0F], [0x28, 0xFD, 0x2B, 0x8B]) { f ->
            Map<String, Integer> seq = new LinkedHashMap<String, Integer>()
            seq.put('dcdc1', 1)
            seq.put('dcdc4', null)
            seq.put('dldo2', 2)
            f.configureFastPoweron(true, false, seq)
        }
        expectWrites([0x19, 0, 0x18, 0x0A], [0x19, 0x37, 0x18, 0x0B]) { f -> f.configureWatchdog(true, 128, 'power_cycle') }
        expectWrites([0x19, 0x06], [0x19, 0x0E]) { f -> f.feedWatchdog() }
        expectWrites([], [0x1A, 0xA5]) { f -> f.configureBatteryWarning(15, 5) }
        expectWrites([0x18, 0x02], [0x18, 0x0A]) { f -> f.setGaugeEnabled(true) }
        expectWrites([0x17, 0], [0x17, 0x08]) { f -> f.resetGauge(false) }
        expectWrites([0x17, 0], [0x17, 0x04]) { f -> f.resetGauge(true) }
        expectWrites([0x1B, 0], [0x1B, 0x04]) { f -> f.setGpio1(true) }
        expectWrites([0x1B, 0x04], [0x1B, 0x00]) { f -> f.setGpio1(false) }
        expectWrites([], [0x06, 0xAB]) { f -> f.writeDataBuffer(2, 0xAB) }
    }

    def "invalid arguments are rejected"() {
        expect:
        rejects { f -> f.setChargeVoltage(4.25d) }
        rejects { f -> f.setChargeCurrent(1.5d) }
        rejects { f -> f.setChargeCurrent(-0.1d) }
        rejects { f -> f.setPrechargeCurrent(0.3d) }
        rejects { f -> f.setInputCurrentLimit(1.2d) }
        rejects { f -> f.configureJeita(true, null, null, true, false, 3, 0) }
        rejects { f -> f.setRailVoltage('dcdc3', 1.84d) }
        rejects { f -> f.setRailVoltage('dldo1', 3.5d) }
        rejects { f -> f.setRailVoltage('foo', 1.0d) }
        rejects { f -> f.setRailVoltage('aldo1', 0.3d) }
        rejects { f -> f.configureDcdc('dcdc1', false, Boolean.TRUE) }
        rejects { f -> f.configurePowerKey(100, 6d, 1.5d, true, false, false) }
        rejects { f -> f.configureWatchdog(true, 3, 'irq') }
        rejects { f -> f.configureBatteryWarning(25, 1) }
        rejects { f -> f.readDataBuffer(4) }
    }

    def "data buffer and sources"() {
        given:
        def f = new AXP2101Full(connection([0x20, 0x21, 0x21, 0x81]))

        when:
        f.writeDataBuffer(2, 0xAB)

        then:
        f.readDataBuffer(2) == 0xAB
        f.powerOnSource() == 0x21
        f.powerOffSource() == 0x81
    }

    def "interrupt enables and status"() {
        given:
        def c = connection([0x40, 0xFF, 0x41, 0xFC, 0x42, 0x5F])
        def f = new AXP2101Full(c)

        when:
        f.disableInterrupt(AXP2101Full.SOURCE_VBUS_INSERT | AXP2101Full.SOURCE_CHARGE_DONE)

        then:
        lastWriteTo(c, 0x41) == 0x7C
        lastWriteTo(c, 0x42) == 0x4F

        when:
        f.enableInterrupt(AXP2101Full.SOURCE_VBUS_INSERT | AXP2101Full.SOURCE_PWRON_SHORT)

        then:
        lastWriteTo(c, 0x41) == 0xFC

        when:
        c.setRegister(0x48, 0x10, 0x88, 0x01)
        c.writes().clear()
        int status = f.pollInterrupt()

        then:
        status == 0x018810
        lastWriteTo(c, 0x48) == 0x10
        lastWriteTo(c, 0x49) == 0x88
        lastWriteTo(c, 0x4A) == 0x01

        when:
        c.setRegister(0x48, 0, 0, 0)
        c.writes().clear()
        int idle = f.pollInterrupt()

        then:
        idle == 0
        c.writes().count { it.length == 2 } == 0
    }

    def "polling fallback delivers an event"() {
        given:
        def f = new AXP2101Full(connection([0x49, 0x80]))
        def got = new java.util.concurrent.CompletableFuture<Integer>()

        when:
        f.onInterrupt({ int s -> got.complete(s) } as java.util.function.IntConsumer)
        int value = got.get(2, java.util.concurrent.TimeUnit.SECONDS)
        f.offInterrupt()

        then:
        value == AXP2101Full.SOURCE_VBUS_INSERT
    }
}
