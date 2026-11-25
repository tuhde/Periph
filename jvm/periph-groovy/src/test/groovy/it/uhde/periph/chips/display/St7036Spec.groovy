package it.uhde.periph.chips.display

import it.uhde.periph.connection.MockDisplayConnection
import spock.lang.Specification

class St7036Spec extends Specification {

    static final String INIT_162 =
        'R d40 I39 u30 I14 u30 I78 u30 I55 u30 I6D u30 d200 I38 u30 I0C u30 I01 d2 I06 u30'

    /** R reset, I<hh> instruction, D<hh> data byte, d<ms> delay, u<us> microsecond delay. */
    private static String trace(MockDisplayConnection m, int from) {
        m.events().drop(from).collect { String e ->
            if (e == 'reset') return 'R'
            if (e.startsWith('command:')) {
                def p = e.split(':', -1)
                assert p[2] == '' : 'instructions are single bytes'
                return 'I' + p[1].toUpperCase()
            }
            if (e.startsWith('data:')) {
                String h = e.substring(5)
                return 'D' + h.substring(0, 2).toUpperCase() + (h.length() != 2 ? '!' : '')
            }
            if (e.startsWith('delayUs:')) return 'u' + e.substring(8)
            if (e.startsWith('delay:')) return 'd' + e.substring(6)
            return '?'
        }.join(' ')
    }

    private static St7036Full full(MockDisplayConnection m, int lines) {
        new St7036Full(m, lines, 0, St7036Full.SUPPLY_3V3, -1)
    }

    def "init sequence is the DOGM162 3.3 V preset"() {
        given:
        def m = new MockDisplayConnection()

        when:
        def d = new St7036Minimal(m)

        then:
        trace(m, 0) == INIT_162
        d.columns() == 16
        d.rows() == 2
    }

    def "minimal operations"() {
        given:
        def m = new MockDisplayConnection()
        def d = new St7036Minimal(m)

        expect:
        step(m) { d.clear() } == 'I01 d2'
        step(m) { d.home() } == 'I02 d2'
        step(m) { d.setCursor(3, 1) } == 'IC3 u30'
        step(m) { d.setCursor(0, 0) } == 'I80 u30'
        step(m) { d.setCursor(16, 0); d.setCursor(0, 2); d.setCursor(-1, 0) } == ''
        step(m) { d.write('Hi') } == 'D48 u30 D69 u30'
        step(m) { d.write('a\u00e4z~') } == 'D61 u30 D3F u30 D7A u30 D3F u30'
        step(m) { d.writeChar(0x05) } == 'D05 u30'
        step(m) { d.printAt(2, 1, 'x') } == 'IC2 u30 D78 u30'
        step(m) { d.write(null) } == ''
    }

    private static String step(MockDisplayConnection m, Closure c) {
        int n = m.events().size()
        c.call()
        trace(m, n)
    }

    def "module presets"() {
        when:
        def m = new MockDisplayConnection()
        new St7036Full(m, lines, 0, supply, -1)

        then:
        trace(m, 0) == want

        where:
        lines | supply                  | want
        1 | St7036Full.SUPPLY_3V3 | 'R d40 I31 u30 I14 u30 I7C u30 I55 u30 I6D u30 d200 I30 u30 I0C u30 I01 d2 I06 u30'
        1 | St7036Full.SUPPLY_5V | 'R d40 I31 u30 I1C u30 I74 u30 I51 u30 I6A u30 d200 I30 u30 I0C u30 I01 d2 I06 u30'
        2 | St7036Full.SUPPLY_3V3 | 'R d40 I39 u30 I14 u30 I78 u30 I55 u30 I6D u30 d200 I38 u30 I0C u30 I01 d2 I06 u30'
        2 | St7036Full.SUPPLY_5V | 'R d40 I39 u30 I1C u30 I74 u30 I52 u30 I69 u30 d200 I38 u30 I0C u30 I01 d2 I06 u30'
        3 | St7036Full.SUPPLY_3V3 | 'R d40 I39 u30 I15 u30 I72 u30 I55 u30 I6E u30 d200 I38 u30 I0C u30 I01 d2 I06 u30'
        3 | St7036Full.SUPPLY_5V | 'R d40 I39 u30 I1D u30 I7C u30 I50 u30 I6C u30 d200 I38 u30 I0C u30 I01 d2 I06 u30'
    }

    def "geometry and overrides"() {
        given:
        def m = new MockDisplayConnection()
        def d = full(m, 3)

        expect:
        d.columns() == 16
        d.rows() == 3
        step(m) { d.setCursor(0, 2) } == 'IA0 u30'
        full(new MockDisplayConnection(), 1).columns() == 8
        full(new MockDisplayConnection(), 1).rows() == 1
        new St7036Full(new MockDisplayConnection(), 2, 20, St7036Full.SUPPLY_3V3, -1).columns() == 20

        when:
        def m2 = new MockDisplayConnection()
        new St7036Full(m2, 2, 0, St7036Full.SUPPLY_3V3, 0x2A)

        then:
        trace(m2, 0) == 'R d40 I39 u30 I14 u30 I7A u30 I56 u30 I6D u30 d200 I38 u30 I0C u30 I01 d2 I06 u30'

        when:
        full(new MockDisplayConnection(), 9)

        then:
        thrown(IllegalArgumentException)

        when:
        def m3 = new MockDisplayConnection()
        new St7036Full(m3)

        then:
        trace(m3, 0) == INIT_162
    }

    def "control instructions"() {
        given:
        def m = new MockDisplayConnection()
        def d = full(m, 2)

        expect:
        step(m) { d.display(false) } == 'I08 u30'
        step(m) { d.display(true) } == 'I0C u30'
        step(m) { d.cursor(true, true) } == 'I0E u30 I0F u30'
        step(m) { d.cursor(false, false) } == 'I0D u30 I0C u30'
        step(m) { d.setEntryMode(false, true) } == 'I05 u30'
        step(m) { d.setEntryMode(true, false) } == 'I06 u30'
        step(m) { d.shiftCursor(true); d.shiftCursor(false) } == 'I14 u30 I10 u30'
        step(m) { d.shiftDisplay(true); d.shiftDisplay(false) } == 'I1C u30 I18 u30'
        step(m) { d.returnHome() } == 'I02 d2'
        step(m) { d.setDdramAddress(0x45) } == 'IC5 u30'
        step(m) { d.sendCommand(0x1F); d.sendData(0x41) } == 'I1F u30 D41 u30'
    }

    def "extension table instructions"() {
        given:
        def m = new MockDisplayConnection()
        def d = full(m, 2)
        def m3 = new MockDisplayConnection()
        def three = full(m3, 3)

        expect:
        step(m) { d.setContrast(0x2D) } == 'I39 u30 I56 u30 I7D u30 I38 u30'
        step(m) { d.setBooster(false) } == 'I39 u30 I52 u30 I38 u30'
        step(m) { d.setBooster(true) } == 'I39 u30 I56 u30 I38 u30'
        step(m) { d.setFollower(true, 3) } == 'I39 u30 I6B u30 I38 u30'
        step(m) { d.setFollower(false, 7) } == 'I39 u30 I67 u30 I38 u30'
        step(m) { d.setBias(true) } == 'I39 u30 I1C u30 I38 u30'
        step(m) { d.setBias(false) } == 'I39 u30 I14 u30 I38 u30'
        step(m) { d.icon(true) } == 'I39 u30 I5E u30 I38 u30'
        step(m) { d.writeIcon(3, 0x15) } == 'I39 u30 I43 u30 D15 u30 I38 u30 I80 u30'
        step(m3) { three.setBias(false) } == 'I39 u30 I15 u30 I38 u30'
    }

    def "custom characters and double height"() {
        given:
        def m = new MockDisplayConnection()
        def d = full(m, 2)
        d.setCursor(5, 1)
        int[] glyph = [0x1F, 0x00, 0xFF, 0x01, 0x02, 0x04, 0x08, 0x10] as int[]
        def m1 = new MockDisplayConnection()
        def one = full(m1, 1)
        def m3 = new MockDisplayConnection()
        def three = full(m3, 3)

        expect:
        step(m) { d.createChar(2, glyph) } == 'I50 u30 D1F u30 D00 u30 D1F u30 D01 u30 D02 u30 D04 u30 D08 u30 D10 u30 IC5 u30'
        step(m) { d.createChar(9, glyph) }.startsWith('I48 u30')
        step(m) { d.setDoubleHeight(true, true) } == ''
        step(m1) { one.setDoubleHeight(true, true) } == 'I34 u30 I34 u30'
        step(m1) { one.setDoubleHeight(false, true) } == 'I30 u30 I30 u30'
        step(m3) { three.setDoubleHeight(true, true) } == 'I3E u30 I18 u30 I3C u30'
        step(m3) { three.setDoubleHeight(true, false) } == 'I3E u30 I10 u30 I3C u30'
    }
}
