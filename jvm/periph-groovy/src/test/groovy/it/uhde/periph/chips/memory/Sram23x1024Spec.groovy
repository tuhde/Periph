package it.uhde.periph.chips.memory

import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.OutputPin
import spock.lang.Specification

class Sram23x1024Spec extends Specification {

    // Behavioural 23X1024: parses the instruction frames the driver sends and
    // models the array, the MODE register and the Byte/Page/Sequential
    // address-increment rules. Every frame is recorded on frames.
    static class SramModel implements Connection {
        byte[] mem = new byte[131072]
        List<byte[]> frames = []
        int mode = 0x40
        boolean dead = false

        private int next(int addr) {
            if ((mode & 0xC0) == 0x80) return (addr & ~31) | ((addr + 1) & 31)
            return (addr + 1) & 0x1FFFF
        }

        void enable() {}
        void disable() {}
        boolean isEnabled() { true }
        InputPin intPin() { null }
        OutputPin enPin() { null }

        void write(byte[] data) {
            frames << data.clone()
            if ((data[0] & 0xFF) == 0x01 && data.length == 2) {
                mode = data[1] & 0xFF
            } else if ((data[0] & 0xFF) == 0x02 && data.length >= 4) {
                int addr = ((data[1] & 0xFF) << 16) | ((data[2] & 0xFF) << 8) | (data[3] & 0xFF)
                for (int i = 4; i < data.length; i++) {
                    mem[addr] = data[i]
                    addr = next(addr)
                    if ((mode & 0xC0) == 0x00) break
                }
            }
        }

        byte[] read(int n) { new byte[n] }

        byte[] writeRead(byte[] data, int n) {
            frames << data.clone()
            byte[] out = new byte[n]
            if ((data[0] & 0xFF) == 0x05) {
                out[0] = dead ? (byte) 0 : (byte) mode
            } else if ((data[0] & 0xFF) == 0x03 && data.length == 4) {
                int addr = ((data[1] & 0xFF) << 16) | ((data[2] & 0xFF) << 8) | (data[3] & 0xFF)
                for (int i = 0; i < n; i++) {
                    out[i] = mem[addr]
                    addr = next(addr)
                    if ((mode & 0xC0) == 0x00) break
                }
            }
            return out
        }

        void close() {}
    }

    static byte[] bytes(int... values) {
        byte[] b = new byte[values.length]
        values.eachWithIndex { v, i -> b[i] = (byte) v }
        return b
    }

    static byte[] pattern(int n) {
        byte[] p = new byte[n]
        for (int i = 0; i < n; i++) p[i] = (byte) (i * 7 + 3)
        return p
    }

    def "init writes Sequential and verifies it"() {
        given:
        def conn = new SramModel()
        when:
        new Sram23x1024Minimal(conn)
        then:
        conn.frames.size() == 2
        conn.frames[0] == bytes(0x01, 0x40)
        conn.frames[1] == bytes(0x05)
    }

    def "init throws on a MODE mismatch"() {
        given:
        def dead = new SramModel()
        dead.dead = true
        when:
        new Sram23x1024Minimal(dead)
        then:
        thrown(IOException)
    }

    def "byte access frames and round trip"() {
        given:
        def conn = new SramModel()
        def chip = new Sram23x1024Minimal(conn)
        when:
        chip.writeByte(0x1ABCD, 0x5A)
        then:
        conn.frames[-1] == bytes(0x02, 0x01, 0xAB, 0xCD, 0x5A)
        conn.mem[0x1ABCD] == (byte) 0x5A
        chip.readByte(0x1ABCD) == 0x5A
        conn.frames[-1] == bytes(0x03, 0x01, 0xAB, 0xCD)
        when:
        chip.readByte(0x20000)
        then:
        thrown(IllegalArgumentException)
    }

    def "block access is chunked"() {
        given:
        def fc = new SramModel()
        def full = new Sram23x1024Full(fc)
        byte[] p = pattern(600)
        when:
        full.write(0x100, p)
        then:
        full.size() == 131072
        Arrays.copyOfRange(fc.mem, 0x100, 0x100 + 600) == p
        full.read(0x100, 600) == p
        fc.frames.count { (it[0] & 0xFF) == 0x02 } == 3
        when:
        full.setMaxChunk(100)
        fc.frames.clear()
        full.write(0, Arrays.copyOf(p, 250))
        then:
        full.maxChunk() == 100
        fc.frames.size() == 3
        fc.frames[0].length == 104
        fc.frames[2].length == 54
        when:
        full.setMaxChunk(257)
        then:
        thrown(IllegalArgumentException)
    }

    def "fill writes only the range"() {
        given:
        def fc = new SramModel()
        def full = new Sram23x1024Full(fc)
        when:
        full.fill(0x2000, 100, 0xA5)
        then:
        (0..<100).every { fc.mem[0x2000 + it] == (byte) 0xA5 }
        fc.mem[0x2064] == (byte) 0
        fc.mem[0x1FFF] == (byte) 0
    }

    def "ranges past the end are rejected"() {
        given:
        def full = new Sram23x1024Full(new SramModel())
        when:
        action.call(full)
        then:
        thrown(IllegalArgumentException)
        where:
        action << [
            { Sram23x1024Full f -> f.read(0x1FFFF, 2) },
            { Sram23x1024Full f -> f.write(0x1FFFF, bytes(1, 2)) },
            { Sram23x1024Full f -> f.fill(0x1FFFF, 2, 0) },
        ]
    }

    def "page mode splits at page boundaries"() {
        given:
        def fc = new SramModel()
        def full = new Sram23x1024Full(fc)
        byte[] data = new byte[64]
        for (int i = 0; i < 64; i++) data[i] = (byte) (0xC0 + i)
        when:
        full.setMode(Sram23x1024Full.MODE_PAGE)
        fc.frames.clear()
        full.write(0x4010, data)
        then:
        fc.mode == 0x80
        full.getMode() == Sram23x1024Full.MODE_PAGE
        fc.frames.size() == 3 + 1  // 3 write frames + the getMode() RDMR above
        Arrays.copyOfRange(fc.mem, 0x4010, 0x4050) == data
        fc.mem[0x4050] == (byte) 0
        full.read(0x4010, 64) == data
    }

    def "byte mode sends one-byte frames and set mode validates"() {
        given:
        def fc = new SramModel()
        def full = new Sram23x1024Full(fc)
        byte[] data = bytes(1, 2, 3, 4)
        when:
        full.setMode(Sram23x1024Full.MODE_BYTE)
        fc.frames.clear()
        full.write(0x500, data)
        then:
        fc.frames.size() == 4
        full.read(0x500, 4) == data
        when:
        full.setMode(Sram23x1024Full.MODE_SEQUENTIAL)
        then:
        full.getMode() == Sram23x1024Full.MODE_SEQUENTIAL
        when:
        full.setMode(0xC0)
        then:
        thrown(IllegalArgumentException)
    }

    def "getMode masks reserved bits and resetIo sends 0xFF"() {
        given:
        def fc = new SramModel()
        def full = new Sram23x1024Full(fc)
        when:
        fc.mode = 0x4F
        then:
        full.getMode() == Sram23x1024Full.MODE_SEQUENTIAL
        when:
        fc.mode = 0x40
        fc.frames.clear()
        full.resetIo()
        then:
        fc.frames[0] == bytes(0xFF)
    }
}
