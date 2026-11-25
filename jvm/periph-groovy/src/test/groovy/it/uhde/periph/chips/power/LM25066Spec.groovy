package it.uhde.periph.chips.power

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class LM25066Spec extends Specification {

    /** One byte string per PMBus command; writes do not overlap neighbours. */
    static class CmdMock extends MockConnection {
        final Map<Integer, byte[]> cmds = [:]

        void word(int cmd, int v) {
            cmds[cmd] = [(byte) v, (byte) (v >> 8)] as byte[]
        }

        void bytes(int cmd, int... v) {
            byte[] b = new byte[v.length]
            for (int i = 0; i < v.length; i++) b[i] = (byte) v[i]
            cmds[cmd] = b
        }

        void text(int cmd, String s) {
            byte[] d = new byte[1 + s.length()]
            d[0] = (byte) s.length()
            for (int i = 0; i < s.length(); i++) d[1 + i] = (byte) s.charAt(i)
            cmds[cmd] = d
        }

        void block(int cmd, int... words) {
            byte[] d = new byte[1 + 2 * words.length]
            d[0] = (byte) 12
            for (int i = 0; i < words.length; i++) {
                d[1 + 2 * i] = (byte) words[i]
                d[2 + 2 * i] = (byte) (words[i] >> 8)
            }
            cmds[cmd] = d
        }

        @Override
        byte[] read(int reg, int length) throws IOException {
            byte[] out = new byte[length]
            byte[] d = cmds[reg]
            if (d != null) System.arraycopy(d, 0, out, 0, Math.min(length, d.length))
            out
        }

        @Override
        void write(int reg, byte[] data) throws IOException {
            byte[] w = new byte[1 + data.length]
            w[0] = (byte) reg
            System.arraycopy(data, 0, w, 1, data.length)
            writes().add(w)
            if (data.length > 0) cmds[reg] = data.clone()
        }

        boolean lastIs(int... want) {
            byte[] w = writes().last()
            if (w.length != want.length) return false
            for (int i = 0; i < w.length; i++) if ((w[i] & 0xFF) != want[i]) return false
            true
        }
    }

    private static boolean near(double want, double got) { Math.abs(want - got) < 1e-3d }

    def "minimal with CL pin at GND"() {
        given:
        def conn = new CmdMock()
        def m = new LM25066Minimal(conn, 5.0d, false)

        expect:
        conn.writes().isEmpty()

        when:
        conn.word(0x88, 2000)
        conn.word(0x8B, 0xF000 | 1500)
        conn.word(0xD1, 648)
        conn.word(0xD2, 1000)
        conn.word(0x8D, 0x0190)

        then:
        near(201800d / 22070d, m.voltageIn())
        near(151800d / 22070d, m.voltageOut())
        near(70000d / 68305d, m.current())
        near(103300d / (736d * 5d), m.power())
        near(25.0d, m.temperature())

        when:
        conn.word(0x8D, 0xFF00)

        then:
        near(-16.0d, m.temperature())
    }

    def "minimal with CL pin at VDD"() {
        given:
        def conn = new CmdMock()
        def m = new LM25066Minimal(conn, 5.0d, true)
        conn.word(0xD1, 648)
        conn.word(0xD2, 1000)

        expect:
        near(67900d / (6854d * 5d), m.current())
        near(101900d / (369d * 5d), m.power())
    }

    def "blocks and aux"() {
        given:
        def conn = new CmdMock()
        def f = new LM25066Full(conn)
        conn.word(0xD0, 3546 - 3)
        conn.block(0xDA, 0x0460, 648, 1500, 2000, 1000, 0x0190)
        conn.block(0xE2, 0, 0, 0, 0, 0, 0)
        conn.block(0xE0, 1, 0, 0, 0, 0, 0)

        expect:
        near(1.0d, f.voltageAux())

        when:
        def b = f.readBlock()

        then:
        b.diagnostic == 0x0460
        near(70000d / 13661d, b.current)
        near(151800d / 22070d, b.voltageOut)
        near(201800d / 22070d, b.voltageIn)
        near(103300d / 736d, b.power)
        near(25.0d, b.temperature)
        near(1800d / 22070d, f.readAverage().voltageIn)
        f.blackBox().diagnostic == 1

        when:
        byte[] bad = new byte[13]
        bad[0] = (byte) 5
        conn.cmds[0xDA] = bad
        f.readBlock()

        then:
        thrown(IllegalStateException)
    }

    def "averages and peak"() {
        given:
        def conn = new CmdMock()
        def f = new LM25066Full(conn)
        conn.word(0xDC, 2000)
        conn.word(0xD5, 1000)

        expect:
        near(201800d / 22070d, f.voltageInAvg())
        near(103300d / 736d, f.powerPeak())

        when:
        f.clearPowerPeak()

        then:
        conn.lastIs(0xD6)

        when:
        f.setAveraging(6)

        then:
        conn.lastIs(0xDB, 6)
        f.getAveraging() == 6

        when:
        f.setAveraging(13)

        then:
        thrown(IllegalArgumentException)
    }

    def "limits"() {
        given:
        def conn = new CmdMock()
        def f = new LM25066Full(conn)

        when:
        f.setVinUvWarn(10.0d)

        then:
        conn.lastIs(0x58, 0x8D, 0x08)

        when:
        f.setVinUvWarn(null)

        then:
        conn.lastIs(0x58, 0, 0)

        when:
        f.setVinOvWarn(null)

        then:
        conn.lastIs(0x57, 0xFF, 0x0F)

        when:
        f.setOtFault(150.0d)

        then:
        conn.lastIs(0x4F, 0x60, 0x09)

        when:
        f.setOtWarn(125.0d)

        then:
        conn.lastIs(0x51, 0xD0, 0x07)

        when:
        f.setPinWarn(1e9d)

        then:
        conn.lastIs(0xD4, 0xFE, 0x0F)

        when:
        f.setIinWarn(1.02d)
        int y = (int) Math.round((13661 * 1.02d - 5200) / 100.0d)

        then:
        conn.lastIs(0xD3, y & 0xFF, y >> 8)

        when:
        conn.word(0x57, 0x0FFF)
        conn.word(0x58, 0)
        conn.word(0x43, 0)
        conn.word(0x4F, 0x0960)
        conn.word(0x51, 0x07D0)
        conn.word(0xD3, 0x0FFF)
        conn.word(0xD4, 0x0FFF)
        def l = f.getLimits()

        then:
        l.vinOvWarn == null
        l.vinUvWarn == null
        l.voutUvWarn == null
        l.iinWarn == null
        l.pinWarn == null
        near(150.0d, l.otFault)
        near(125.0d, l.otWarn)
    }

    def "status and control"() {
        given:
        def conn = new CmdMock()
        def f = new LM25066Full(conn)
        conn.bytes(0x78, 0x49)
        conn.word(0x79, 0x3849)
        conn.bytes(0x7A, 0x20)
        conn.bytes(0x7C, 0x10)
        conn.bytes(0x7D, 0x80)
        conn.bytes(0x7E, 0x02)
        conn.bytes(0x80, 0x10)
        conn.word(0xE1, 0x0460)

        expect:
        f.statusByte() == 0x49
        f.statusWord() == 0x3849
        f.statusVout() == 0x20
        f.statusInput() == 0x10
        f.statusTemperature() == 0x80
        f.statusCml() == 0x02
        f.statusMfr() == 0x10
        f.diagnosticWord() == 0x0460
        f.pollInterrupt() == 0x0460

        when:
        f.clearFaults()

        then:
        conn.lastIs(0x03)

        when:
        f.operationOn()

        then:
        conn.lastIs(0x01, 0x80)

        when:
        f.operationOff()

        then:
        conn.lastIs(0x01, 0x00)

        when:
        conn.bytes(0x01, 0x80)
        conn.bytes(0x78, 0x00)

        then:
        f.isOn()

        when:
        conn.bytes(0x78, 0x40)

        then:
        !f.isOn()
    }

    def "device setup, gate mask, identity and alerts"() {
        given:
        def conn = new CmdMock()
        def f = new LM25066Full(conn)

        when:
        f.configureDevice(7, true, false)
        conn.word(0xD1, 648)

        then:
        conn.lastIs(0xD9, 0xE0 | 0x04 | 0x10 | 0x02)
        near(67900d / 6854d, f.current())

        when:
        f.configureDevice(8, null, null)

        then:
        thrown(IllegalArgumentException)

        when:
        conn.bytes(0xD9, 0x16)
        f.setGateMask(0x04)

        then:
        f.deviceSetup() == 0x16
        conn.lastIs(0xD7, 0x04)
        f.getGateMask() == 0x04

        when:
        f.setGateMask(0x01)

        then:
        thrown(IllegalArgumentException)

        when:
        conn.bytes(0x19, 0xB0)
        conn.text(0x99, 'NSC')
        conn.text(0x9A, 'LM25066\u0000')
        conn.text(0x9B, 'AA')

        then:
        f.capability() == 0xB0
        f.manufacturerId() == 'NSC'
        f.model() == 'LM25066'
        f.revision() == 'AA'

        when:
        conn.word(0xD8, 0x0820)
        f.enableInterrupt(LM25066Full.SOURCE_VIN_UV_FAULT)

        then:
        conn.lastIs(0xD8, 0x00, 0x08)

        when:
        f.disableInterrupt(LM25066Full.SOURCE_VIN_UV_WARN)

        then:
        conn.lastIs(0xD8, 0x00, 0x28)
    }
}
