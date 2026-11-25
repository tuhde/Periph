package it.uhde.periph.chips.rtc

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class PCF85063ASpec extends Specification {

    private static int lastWrite(MockConnection c, int reg) {
        def w = c.writes().findAll { it.length == 2 && (it[0] & 0xFF) == reg }
        return w ? (w.last()[1] & 0xFF) : -1
    }

    private static int reg(MockConnection c, int reg) {
        return c.registers()[reg] ?: 0
    }

    def "constructor forces 24-hour mode with the clock running"() {
        given:
        def conn = new MockConnection()
        conn.setRegister(0x00, 0x37)

        when:
        new PCF85063AMinimal(conn)

        then:
        lastWrite(conn, 0x00) == 0x05
        conn.writes().every { it.length <= 2 }
    }

    def "decodes datetime and uses the STOP-bit sequence to set it"() {
        given:
        def conn = new MockConnection()
        conn.setRegister(0x04, 0xC5, 0x30, 0x14, 0x23, 0x03, 0x09, 0x26)
        def rtc = new PCF85063AMinimal(conn)
        conn.setRegister(0x00, 0x02)

        when:
        def dt = rtc.getDatetime()

        then:
        [dt.year, dt.month, dt.day, dt.weekday, dt.hour, dt.minute, dt.second] == [2026, 9, 23, 3, 14, 30, 45]

        when:
        int before = conn.writes().size()
        rtc.setDatetime(2026, 9, 23, 3, 14, 30, 45)
        def w = conn.writes().subList(before, conn.writes().size())

        then:
        w[1] as List == [0x00, 0x20]
        (w[2] as List) == ([0x04, 0x45, 0x30, 0x14, 0x23, 0x03, 0x09, 0x26] as byte[]) as List
        w[3] as List == [0x00, 0x00]
    }

    def "setDatetime rejects years outside 2000-2099"() {
        given:
        def conn = new MockConnection()
        def rtc = new PCF85063AMinimal(conn)
        int before = conn.writes().size()

        when:
        rtc.setDatetime(2100, 1, 1, 5, 0, 0, 0)

        then:
        thrown(IllegalArgumentException)
        conn.writes().size() == before
    }

    def "alarm uses active-low enables and clears AF"() {
        given:
        def conn = new MockConnection()
        def rtc = new PCF85063AFull(conn)
        conn.setRegister(0x01, 0xC8)

        when:
        rtc.setAlarm(new PCF85063AFull.Alarm(second: 5, minute: 45, weekday: 6))
        def alarm = rtc.getAlarm()
        int c2 = lastWrite(conn, 0x01)

        then:
        (0x0B..0x0F).collect { reg(conn, it) } == [0x05, 0x45, 0x80, 0x80, 0x06]
        alarm.second == 5
        alarm.minute == 45
        alarm.hour == null
        alarm.day == null
        alarm.weekday == 6
        (c2 & 0x48) == 0x08
        (c2 & 0x80) == 0x80
    }

    def "offset is two's complement"() {
        given:
        def conn = new MockConnection()
        def rtc = new PCF85063AFull(conn)

        when:
        rtc.setOffset(-64, PCF85063AFull.OffsetMode.COURSE)
        def a = rtc.getOffset()

        then:
        reg(conn, 0x02) == 0xC0
        a.offset == -64
        a.mode == PCF85063AFull.OffsetMode.COURSE

        when:
        rtc.setOffset(63)
        def b = rtc.getOffset()

        then:
        b.offset == 63
        b.mode == PCF85063AFull.OffsetMode.NORMAL
    }

    def "timer configuration, TIE and disable"() {
        given:
        def conn = new MockConnection()
        conn.setRegister(0x11, 0x18)
        def rtc = new PCF85063AFull(conn)

        when:
        rtc.configureTimer(30, PCF85063AFull.SourceClock.HZ_1, true)

        then:
        reg(conn, 0x10) == 30
        reg(conn, 0x11) == 0x15

        when:
        rtc.enableInterrupt(PCF85063AFull.SOURCE_TIMER)

        then:
        reg(conn, 0x11) == 0x17

        when:
        rtc.disableTimer()

        then:
        reg(conn, 0x11) == 0x19

        when:
        conn.setRegister(0x10, 17)

        then:
        rtc.readTimer() == 17
    }

    def "clock output and minute interrupt"() {
        given:
        def conn = new MockConnection()
        conn.setRegister(0x01, 0x48)
        def rtc = new PCF85063AFull(conn)

        when:
        rtc.setClockOutput(1)

        then:
        reg(conn, 0x01) == 0x4E

        when:
        rtc.disableClockOutput()

        then:
        (reg(conn, 0x01) & 0x07) == 0x07

        when:
        rtc.setClockOutput(2048)

        then:
        (reg(conn, 0x01) & 0x07) == 0x04

        when:
        rtc.setMinuteInterrupt(PCF85063AFull.MinuteInterrupt.MINUTE)

        then:
        (reg(conn, 0x01) & 0x30) == 0x20

        when:
        rtc.setMinuteInterrupt(PCF85063AFull.MinuteInterrupt.HALF_MINUTE)

        then:
        (reg(conn, 0x01) & 0x30) == 0x10

        when:
        rtc.setMinuteInterrupt(PCF85063AFull.MinuteInterrupt.OFF)

        then:
        (reg(conn, 0x01) & 0x30) == 0x00
    }

    def "oscillator load, stop/start, OS flag and RAM"() {
        given:
        def conn = new MockConnection()
        def rtc = new PCF85063AFull(conn)

        when:
        rtc.setOscillatorLoad(12.5d)

        then:
        (reg(conn, 0x00) & 0x01) == 0x01

        when:
        rtc.setOscillatorLoad(7d)
        rtc.stopClock()

        then:
        (reg(conn, 0x00) & 0x01) == 0x00
        (reg(conn, 0x00) & 0x20) == 0x20

        when:
        rtc.startClock()
        conn.setRegister(0x04, 0xD9)

        then:
        (reg(conn, 0x00) & 0x20) == 0x00
        rtc.oscillatorStopped()

        when:
        rtc.clearOscillatorStop()
        rtc.writeRam(0xA5)

        then:
        reg(conn, 0x04) == 0x59
        !rtc.oscillatorStopped()
        rtc.readRam() == 0xA5
    }

    def "pollInterrupt maps flags and clears only the set ones"() {
        given:
        def conn = new MockConnection()
        def rtc = new PCF85063AFull(conn)
        conn.setRegister(0x01, 0xE5)

        when:
        int status = rtc.pollInterrupt()
        int c2 = lastWrite(conn, 0x01)

        then:
        status == PCF85063AFull.SOURCE_ALARM
        (c2 & 0x40) == 0x00
        (c2 & 0x08) == 0x08
        (c2 & 0xB7) == 0xA5

        when:
        conn.setRegister(0x01, 0x08)

        then:
        rtc.pollInterrupt() == PCF85063AFull.SOURCE_TIMER

        when:
        conn.setRegister(0x01, 0x00)

        then:
        rtc.pollInterrupt() == 0
    }

    def "enable and disable interrupt sources, software reset"() {
        given:
        def conn = new MockConnection()
        def rtc = new PCF85063AFull(conn)

        when:
        rtc.enableInterrupt(PCF85063AFull.SOURCE_ALARM | PCF85063AFull.SOURCE_MINUTE |
                PCF85063AFull.SOURCE_HALF_MINUTE | PCF85063AFull.SOURCE_CORRECTION)

        then:
        (reg(conn, 0x01) & 0xB0) == 0xB0
        (reg(conn, 0x00) & 0x04) == 0x04

        when:
        rtc.disableInterrupt(PCF85063AFull.SOURCE_ALARM | PCF85063AFull.SOURCE_CORRECTION)

        then:
        (reg(conn, 0x01) & 0xB0) == 0x30
        (reg(conn, 0x00) & 0x04) == 0x00

        when:
        rtc.softwareReset()

        then:
        lastWrite(conn, 0x00) == 0x58
    }
}
