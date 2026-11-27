package it.uhde.periph.chips.rfid

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Pn532Spec extends Specification {

    static final byte[] ACK = b(0x00, 0x00, 0xFF, 0x00, 0xFF, 0x00)
    static final byte[] NACK = b(0x00, 0x00, 0xFF, 0xFF, 0x00, 0x00)
    static final byte[] ERROR_FRAME = b(0x00, 0x00, 0xFF, 0x01, 0xFF, 0x7F, 0x81, 0x00)

    static byte[] b(int... v) {
        byte[] r = new byte[v.length]
        for (int i = 0; i < v.length; i++) r[i] = (byte) v[i]
        r
    }

    static byte[] cat(byte[] a, byte[] c) {
        byte[] r = Arrays.copyOf(a, a.length + c.length)
        System.arraycopy(c, 0, r, a.length, c.length)
        r
    }

    static byte[] frame(int tfi, byte[] data) {
        int len = data.length + 1
        int sum = tfi
        for (byte x : data) sum += x & 0xFF
        cat(cat(b(0x00, 0x00, 0xFF, len, 0x100 - len, tfi), data), b(0x100 - (sum & 0xFF), 0x00))
    }

    static class Cmd {
        int cmd
        byte[] params

        boolean is(int c, int... p) { cmd == c && Arrays.equals(params, b(p)) }
    }

    /** Simulates the PN532 I²C frame protocol: ACK, response, NACK resend, ACK abort, status byte. */
    static class Sim extends MockConnection {
        Deque<byte[]> queue = new ArrayDeque<>()
        byte[] lastFrame
        List<Cmd> commands = []
        List<byte[]> written = []
        List<Integer> reads = []
        boolean card = true, noAck = false, noResponse = false, errorFrame = false, badDcs = false
        int ic = 0x32, status = 0, failWrites = 0
        byte[] uid = b(0x04, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66)
        byte[] listResponse
        Map<Integer, Integer> regs = [:]
        InputPin pin

        Cmd last() { commands[-1] }

        byte[] respond(int cmd, byte[] p) {
            switch (cmd) {
                case 0x02: return b(ic, 0x01, 0x06, 0x07)
                case 0x04: return b(0x00, 0x01, 0x01, 0x01, 0x00, 0x00, 0x10, 0x01)
                case 0x4A:
                    if (listResponse != null) return listResponse
                    if (!card) return b(0)
                    return cat(b(1, 1, 0x00, 0x44, 0x00, uid.length), uid)
                case [0x52, 0x54, 0x44, 0x16]: return b(status)
                case 0x40:
                    if (status != 0) return b(status)
                    if (p[1] == 0x30) {
                        byte[] r = new byte[17]
                        for (int i = 0; i < 16; i++) r[1 + i] = (byte) (p[2] + i)
                        return r
                    }
                    return b(0)
                case 0x42:
                    byte[] r = new byte[p.length + 1]
                    for (int i = 0; i < p.length; i++) r[1 + i] = p[p.length - 1 - i]
                    return r
                case 0x06: return b(regs.getOrDefault(((p[0] & 0xFF) << 8) | (p[1] & 0xFF), 0))
                case 0x08:
                    regs[((p[0] & 0xFF) << 8) | (p[1] & 0xFF)] = p[2] & 0xFF
                    return new byte[0]
                case 0x00: return p[0] == 0 ? p.clone() : b(0)
                default: return new byte[0]
            }
        }

        @Override
        void write(byte[] data) throws IOException {
            written << data.clone()
            if (failWrites > 0) {
                failWrites--
                throw new IOException('NACK')
            }
            if (Arrays.equals(data, ACK)) { queue.clear(); return }
            if (Arrays.equals(data, NACK)) { if (lastFrame != null) queue.add(lastFrame); return }
            int ln = data[3] & 0xFF
            Cmd c = new Cmd(cmd: data[6] & 0xFF, params: Arrays.copyOfRange(data, 7, 5 + ln))
            commands << c
            if (noAck) return
            queue.clear()
            queue.add(ACK)
            if (noResponse) return
            if (errorFrame) { queue.add(ERROR_FRAME); return }
            byte[] f = frame(0xD5, cat(b(c.cmd + 1), respond(c.cmd, c.params)))
            if (badDcs) f[f.length - 2] = (byte) (f[f.length - 2] + 1)
            queue.add(f)
        }

        @Override
        byte[] read(int n) {
            reads << n
            byte[] out = new byte[n]
            if (queue.isEmpty()) return out
            out[0] = 0x01
            if (n == 1) return out
            byte[] item = queue.poll()
            if (!Arrays.equals(item, ACK)) lastFrame = item
            for (int i = 1; i < n; i++) out[i] = i - 1 < item.length ? item[i - 1] : (byte) 0xFF
            out
        }

        @Override
        InputPin intPin() { pin }
    }

    def "frame matches the datasheet vector"() {
        expect:
        Pn532Minimal.frame(0x02, new byte[0]) == b(0x00, 0x00, 0xFF, 0x02, 0xFE, 0xD4, 0x02, 0x2A, 0x00)
    }

    def "init sends GetFirmwareVersion, SAMConfiguration and MaxRetries"() {
        given:
        def sim = new Sim()

        when:
        new Pn532Minimal(sim).init()

        then:
        sim.commands.size() == 3
        sim.commands[1].is(0x14, 0x01, 0x14, 0x01)
        sim.commands[2].is(0x32, 0x05, 0xFF, 0x01, 0x02)
        sim.written[1] == NACK
        sim.reads.count { it == 7 } == 3
        sim.reads.contains(14)
    }

    def "readUid returns the UID and releases the target"() {
        given:
        def sim = new Sim()
        def m = new Pn532Minimal(sim)

        when:
        def uid = m.readUid()

        then:
        uid == sim.uid
        sim.commands[0].is(0x4A, 0x01, 0x00)
        sim.commands[1].is(0x52, 0x01)
    }

    def "readUid returns null without a card or on a timeout"() {
        given:
        def sim = new Sim()
        def m = new Pn532Minimal(sim)

        when:
        sim.card = false
        then:
        m.readUid() == null

        when:
        sim.noResponse = true
        def r = m.readUid(0.01d)
        then:
        r == null
        sim.written[-1] == ACK
    }

    def "error frame, bad DCS, bad IC and missing ACK fail"() {
        when:
        def sim = new Sim(errorFrame: true)
        new Pn532Minimal(sim).readUid()
        then:
        thrown(Pn532Minimal.Pn532Exception)

        when:
        new Pn532Minimal(new Sim(badDcs: true)).readUid()
        then:
        thrown(Pn532Minimal.Pn532Exception)

        when:
        new Pn532Minimal(new Sim(ic: 0x31)).init()
        then:
        thrown(Pn532Minimal.Pn532Exception)

        when:
        def noAck = new Sim(noAck: true)
        new Pn532Minimal(noAck).init()
        then:
        thrown(Pn532Minimal.Pn532Exception)
        noAck.commands.size() == 3
    }

    def "writes are retried to wake the chip"() {
        given:
        def sim = new Sim(failWrites: 3)

        when:
        new Pn532Minimal(sim).init()

        then:
        sim.commands.size() == 3
    }

    def "firmware version and general status"() {
        given:
        def f = new Pn532Full(new Sim())

        expect:
        f.firmwareVersion() == new Pn532Full.FirmwareVersion(0x32, 1, 6, 7, true, true, true)
        f.generalStatus() == new Pn532Full.GeneralStatus(0, true, 1, 1)
    }

    def "listPassiveTargets parses type A with ATS and a second target"() {
        given:
        def sim = new Sim(listResponse: b(2, 1, 0x00, 0x04, 0x20, 4, 1, 2, 3, 4, 5, 0x75, 0x77, 0x81, 0x02,
                2, 0x00, 0x44, 0x00, 7, 9, 9, 9, 9, 9, 9, 9))
        def f = new Pn532Full(sim)

        when:
        def t = f.listPassiveTargets(Pn532Full.BAUD_106_A, 2, new byte[0], 1.0d)

        then:
        t.size() == 2
        t[0].tg() == 1 && t[0].sensRes() == 0x0004 && t[0].selRes() == 0x20
        t[0].uid() == b(1, 2, 3, 4)
        t[0].ats() == b(5, 0x75, 0x77, 0x81, 0x02)
        t[1].sensRes() == 0x0044 && t[1].uid().length == 7 && t[1].ats().length == 0
        sim.last().is(0x4A, 0x02, 0x00)
    }

    def "listPassiveTargets parses FeliCa and type B"() {
        given:
        def sim = new Sim()
        def f = new Pn532Full(sim)

        when:
        sim.listResponse = b(1, 1, 20, 0x01, 1, 2, 3, 4, 5, 6, 7, 8, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x12, 0xFC)
        def t = f.listPassiveTargets(Pn532Full.BAUD_212_F, 1, b(0x00, 0xFF, 0xFF, 0x01, 0x00), 1.0d)
        then:
        t[0].uid() == b(1, 2, 3, 4, 5, 6, 7, 8)
        t[0].ats() == b(0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x12, 0xFC)
        sim.last().is(0x4A, 0x01, 0x01, 0x00, 0xFF, 0xFF, 0x01, 0x00)

        when:
        sim.listResponse = b(1, 1, 0x50, 0xA1, 0xA2, 0xA3, 0xA4, 0, 0, 0, 0, 0x81, 0x81, 0x00, 1, 0x00)
        t = f.listPassiveTargets(Pn532Full.BAUD_106_B, 1, b(0x00), 1.0d)
        then:
        t[0].uid() == b(0xA1, 0xA2, 0xA3, 0xA4)
        t[0].ats() == b(0x00)

        when:
        sim.listResponse = b(0)
        then:
        f.listPassiveTargets().isEmpty()
    }

    def "MIFARE and NTAG commands"() {
        given:
        def sim = new Sim()
        def f = new Pn532Full(sim)

        when:
        def block = f.mifareReadBlock(1, 4)
        then:
        block[0] == 4 && block[15] == 19
        sim.last().is(0x40, 0x01, 0x30, 0x04)

        when:
        f.mifareAuthenticate(1, 7, b(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF), sim.uid, false)
        then:
        sim.last().is(0x40, 0x01, 0x60, 0x07, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x33, 0x44, 0x55, 0x66)

        when:
        f.mifareAuthenticate(1, 7, new byte[6], b(0xDE, 0xAD, 0xBE, 0xEF), true)
        then:
        sim.last().params[1] == 0x61

        when:
        f.mifareWriteBlock(1, 4, new byte[16])
        then:
        sim.last().is(0x40, 0x01, 0xA0, 0x04, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

        when:
        f.ntagWritePage(1, 5, b(1, 2, 3, 4))
        then:
        sim.last().is(0x40, 0x01, 0xA2, 0x05, 1, 2, 3, 4)
        f.ntagReadPage(1, 4)[0] == 4
        f.communicateThru(b(1, 2, 3)) == b(3, 2, 1)
    }

    def "a non-zero status throws with the code"() {
        given:
        def f = new Pn532Full(new Sim(status: 0x14))

        when:
        f.dataExchange(1, b(0x30, 0x00))
        then:
        def e = thrown(Pn532Minimal.Pn532Exception)
        e.code() == 0x14
    }

    def "release, select and deselect"() {
        given:
        def sim = new Sim()
        def f = new Pn532Full(sim)

        when:
        f.release(0)
        then:
        sim.last().is(0x52, 0x00)

        when:
        f.select(1)
        then:
        sim.last().is(0x54, 0x01)

        when:
        f.deselect(1)
        then:
        sim.last().is(0x44, 0x01)
    }

    def "configuration, registers, self test and power down"() {
        given:
        def sim = new Sim()
        def f = new Pn532Full(sim)

        when:
        f.setRfField(true, true)
        then:
        sim.last().is(0x32, 0x01, 0x03)

        when:
        f.setMaxRetries(0xFF)
        then:
        sim.last().is(0x32, 0x05, 0xFF, 0x01, 0xFF)

        when:
        f.writeRegister(0x6302, 0x5A)
        then:
        sim.last().is(0x08, 0x63, 0x02, 0x5A)
        f.readRegister(0x6302) == 0x5A

        when:
        def ok = f.selfTest()
        then:
        ok
        sim.last().is(0x00, 0x00, 0x50, 0x45, 0x52, 0x49, 0x50, 0x48, 0x30, 0x31)
        f.selfTest(0x01)

        when:
        f.powerDown()
        then:
        sim.last().is(0x16, 0x20)
    }

    def "raw command, start/read response and polling"() {
        given:
        def f = new Pn532Full(new Sim())

        expect:
        f.sendCommand(0x02, new byte[0]) == b(0x32, 0x01, 0x06, 0x07)

        when:
        f.startCommand(0x02, new byte[0])
        then:
        f.waitReady(0.01d)
        f.pollInterrupt()
        f.readResponse(0x02, 1.0d) == b(0x32, 0x01, 0x06, 0x07)
        !f.pollInterrupt()
        !f.waitReady(0.005d)
    }

    def "onInterrupt registers on the IRQ pin"() {
        given:
        EdgeHandler handler = null
        def sim = new Sim()
        sim.pin = new InputPin() {
            @Override void onEdge(EdgeHandler h, EdgeTrigger t) { handler = h }
            @Override void offEdge(EdgeHandler h) { if (handler.is(h)) handler = null }
            @Override boolean isHigh() { true }
            @Override void close() {}
        }
        def f = new Pn532Full(sim)
        int hits = 0

        when:
        f.onInterrupt({ hits++ } as Runnable)
        handler.onEdge()
        then:
        hits == 1

        when:
        f.offInterrupt()
        then:
        handler == null
    }
}
