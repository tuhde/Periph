package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.MockConnection
import it.uhde.periph.connection.OutputPin
import spock.lang.Specification

class MCP4XXXSpec extends Specification {

    private static byte[] b(int... v) {
        byte[] out = new byte[v.length]
        for (int i = 0; i < v.length; i++) out[i] = (byte) v[i]
        return out
    }

    private static byte[] last(MockConnection c) {
        return c.writes()[c.writes().size() - 1]
    }

    /** Models the MCP4XXX command protocol: sixteen nine-bit cells, inc/dec of the volatile wipers, two-byte reads. */
    static class ChipMock extends MockConnection {
        int[] mem = new int[16]
        int maxCode
        int status = 0x1F0

        ChipMock(int maxCode) {
            this.maxCode = maxCode
            mem[0] = mem[1] = mem[2] = mem[3] = maxCode.intdiv(2)
            mem[4] = 0x1FF
        }

        @Override
        void write(byte[] data) throws IOException {
            super.write(data)
            int i = 0
            while (i < data.length) {
                int cmd = data[i] & 0xFF
                int addr = cmd >> 4
                int op = (cmd >> 2) & 3
                if (op == 0) {
                    mem[addr] = ((cmd & 1) << 8) | (data[i + 1] & 0xFF)
                    i += 2
                } else {
                    if (addr < 2) mem[addr] = op == 1 ? Math.min(maxCode, mem[addr] + 1) : Math.max(0, mem[addr] - 1)
                    i += 1
                }
            }
        }

        @Override
        byte[] writeRead(byte[] data, int n) throws IOException {
            super.writeRead(data, n)
            int addr = (data[0] & 0xFF) >> 4
            int v = addr == 5 ? status : mem[addr]
            return b((v >> 8) & 1, v & 0xFF)
        }
    }

    static class Pin implements OutputPin {
        List<String> log = []
        @Override void set(boolean high) { log << (high ? 'HVC1' : 'HVC0') }
        @Override void close() {}
    }

    private static ChipMock mock(String part) {
        new ChipMock(MCP4XXXMinimal.lookup(part).maxCode)
    }

    def "part table"() {
        expect:
        MCP4XXXMinimal.defaultAddress('mcp4531') == 0x2E
        MCP4XXXMinimal.defaultAddress('MCP4632') == 0x58
        MCP4XXXMinimal.defaultAddress('MCP4661') == 0x28

        when:
        new MCP4XXXMinimal(new MockConnection(), 'MCP9999')
        then:
        thrown(IllegalArgumentException)
    }

    def "init reads only"() {
        given:
        def c = mock('MCP4662')
        def chip = new MCP4XXXFull(c, 'MCP4662')

        when:
        chip.init()

        then:
        c.writes().size() == 4
        c.writes()[0] == b(0x0C)
        c.writes()[1] == b(0x1C)
        c.writes()[2] == b(0x4C)
        c.writes()[3] == b(0x5C)
        chip.channels() == 2
        chip.steps() == 257
        !chip.isPotentiometer()
        chip.isNonvolatile()
    }

    def "wiper formats"() {
        given:
        def c = mock('MCP4531')
        def chip = new MCP4XXXFull(c, 'MCP4531')

        when:
        chip.setWiper(100)
        then:
        last(c) == b(0x00, 100)

        when:
        chip.setWiper(999)
        then:
        last(c) == b(0x00, 128)

        when:
        chip.setWiper(-3)
        then:
        last(c) == b(0x00, 0)

        when:
        chip.setWiper(1, 1)
        then:
        thrown(IllegalArgumentException)

        when:
        def c8 = mock('MCP4652')
        def chip8 = new MCP4XXXFull(c8, 'MCP4652')
        chip8.setWiper(256, 1)
        then:
        last(c8) == b(0x11, 0x00)
        chip8.getWiper(1) == 256

        when:
        c8.mem[0] = 0x3FF
        then:
        chip8.getWiper(0) == 256
    }

    def "resistance and ratio"() {
        given:
        def chip = new MCP4XXXFull(mock('MCP4531'), 'MCP4531')

        when:
        chip.setWiper(64)
        then:
        Math.abs(chip.getResistance() - 5075.0d) < 1e-9
        Math.abs(chip.getResistanceWa(0) - 5075.0d) < 1e-9

        when:
        chip.setResistance(1e9d)
        then:
        chip.getWiper() == 128

        when:
        chip.setResistanceWa(5075.0d, 0)
        then:
        chip.getWiper() == 64

        when:
        chip.setRatio(0.5d, 0)
        then:
        Math.abs(chip.getRatio(0) - 0.5d) < 1e-9
        Math.abs(chip.getResistanceStep() - 10000.0d / 128) < 1e-9

        when:
        def rheo = new MCP4XXXFull(mock('MCP4532'), 'MCP4532')
        rheo.getResistanceWa(0)
        then:
        thrown(UnsupportedOperationException)

        when:
        rheo.setBoth(1, 2)
        then:
        thrown(UnsupportedOperationException)
    }

    def "increment and decrement"() {
        given:
        def c = mock('MCP4631')
        def chip = new MCP4XXXFull(c, 'MCP4631')

        when:
        chip.setBoth(10, 20)
        then:
        c.mem[0] == 10
        c.mem[1] == 20

        when:
        chip.setWiper(120, 1)
        int n = c.writes().size()
        int code = chip.increment(10, 1)
        then:
        code == 128
        c.writes().size() == n + 2
        c.writes()[n].length == 10
        (c.writes()[n][0] & 0xFF) == 0x14

        when:
        chip.setWiper(5, 0)
        then:
        chip.decrement(10, 0) == 0
        chip.increment(1, 0) == 1
    }

    def "tcon"() {
        given:
        def c = mock('MCP4631')
        def chip = new MCP4XXXFull(c, 'MCP4631')

        when:
        chip.shutdown(1)
        then:
        c.mem[4] == (0x1FF & ~0x80)
        chip.isShutdown(1)
        !chip.isShutdown(0)

        when:
        chip.resume(1)
        then:
        c.mem[4] == 0x1FF

        when:
        chip.shutdown(0)
        then:
        c.mem[4] == (0x1FF & ~0x08)

        when:
        chip.resume(0)
        chip.setTerminals(true, false, true, 1)
        then:
        c.mem[4] == (0x1FF & ~0x20)
        chip.getTerminals(1) == [true, false, true] as boolean[]
        chip.getTerminals(0) == [true, true, true] as boolean[]

        when:
        chip.setGeneralCall(false)
        then:
        (chip.getTcon() & 0x100) == 0

        when:
        chip.setGeneralCall(true)
        chip.setWiper(3, 0)
        chip.shutdown(1)
        chip.reset()
        then:
        c.mem[0] == 64
        c.mem[1] == 64
        c.mem[4] == 0x1FF
    }

    def "raw access"() {
        given:
        def c = mock('MCP4532')
        def chip = new MCP4XXXFull(c, 'MCP4532')

        expect:
        chip.readRegister(4) == 0x1FF

        when:
        chip.writeRegister(0, 77)
        then:
        c.mem[0] == 77

        when:
        chip.writeRegister(5, 0)
        then:
        thrown(IllegalArgumentException)

        when:
        chip.readRegister(2)
        then:
        thrown(IllegalArgumentException)

        when:
        chip.readRegister(16)
        then:
        thrown(IllegalArgumentException)
    }

    def "non-volatile operations throw on a volatile part"() {
        given:
        def chip = new MCP4XXXFull(mock('MCP4532'), 'MCP4532')

        when:
        action(chip)
        then:
        thrown(UnsupportedOperationException)

        where:
        action << [
                { MCP4XXXFull x -> x.readStatus() },
                { MCP4XXXFull x -> x.waitReady(5) },
                { MCP4XXXFull x -> x.getNvWiper(0) },
                { MCP4XXXFull x -> x.storeWiper(0) },
                { MCP4XXXFull x -> x.readData(0) },
                { MCP4XXXFull x -> x.setWriteProtect(true) }
        ]
    }

    def "non-volatile part"() {
        given:
        def pin = new Pin()
        def c = mock('MCP4662')
        def chip = new MCP4XXXFull(c, 'MCP4662', 10000.0d, 75.0d, pin, 1)

        when:
        def st = chip.readStatus()
        then:
        !st.eepromBusy
        !st.writeProtect

        when:
        c.status = 0x1F0 | 0x08 | 0x02 | 0x01
        st = chip.readStatus()
        then:
        st.eepromBusy && st.wiperLock[0] && !st.wiperLock[1] && st.writeProtect
        chip.isWiperLocked(0)
        !chip.isWiperLocked(1)
        chip.isWriteProtected()

        when:
        chip.waitReady(3)
        then:
        thrown(IOException)

        when:
        c.status = 0x1F0
        chip.setNvWiper(200, 1)
        chip.setWiper(150, 0)
        int stored = chip.storeWiper(0)
        chip.writeData(3, 0x1AB)
        then:
        c.mem[3] == 200
        stored == 150
        c.mem[2] == 150
        chip.readData(3) == 0x1AB
        c.writes().any { it == b(0x91, 0xAB) }

        when:
        chip.readData(10)
        then:
        thrown(IllegalArgumentException)

        when:
        chip.setWiper(7, 0)
        chip.reset()
        then:
        c.mem[0] == 150
        c.mem[1] == 200
    }

    def "high-voltage commands"() {
        given:
        def pin = new Pin()
        def c = mock('MCP4662')
        def chip = new MCP4XXXFull(c, 'MCP4662', 10000.0d, 75.0d, pin, 1)

        when:
        chip.setWiperLock(true, 0)
        then:
        c.writes()[0] == b(0x28)
        pin.log == ['HVC1', 'HVC0']

        when:
        int n = c.writes().size()
        chip.setWiperLock(false, 1)
        then:
        c.writes()[n] == b(0x34)

        when:
        n = c.writes().size()
        chip.setWiperLock(true, 1)
        then:
        c.writes()[n] == b(0x38)

        when:
        n = c.writes().size()
        chip.setWriteProtect(true)
        then:
        c.writes()[n] == b(0xF8)

        when:
        n = c.writes().size()
        chip.setWriteProtect(false)
        then:
        c.writes()[n] == b(0xF4)

        when:
        new MCP4XXXFull(mock('MCP4562'), 'MCP4562').setWiperLock(true, 0)
        then:
        thrown(UnsupportedOperationException)
    }

    def "setRab has no traffic"() {
        given:
        def c = mock('MCP4532')
        def chip = new MCP4XXXFull(c, 'MCP4532')

        when:
        int n = c.writes().size()
        chip.setRab(50000.0d, 100.0d)
        then:
        c.writes().size() == n

        when:
        chip.setWiper(128)
        then:
        Math.abs(chip.getResistance() - 50100.0d) < 1e-9
    }
}
