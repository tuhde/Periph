package it.uhde.periph.chips.rtc

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class PCF8523Spec extends Specification {

    private static int lastWrite(MockConnection c, int reg) {
        def w = c.writes().findAll { it.length == 2 && (it[0] & 0xFF) == reg }
        return w ? (w.last()[1] & 0xFF) : -1
    }

    private static int reg(MockConnection c, int reg) {
        return c.registers()[reg] ?: 0
    }

    def "constructor enables battery switch-over standard mode"() {
        given:
        def conn = new MockConnection()
        conn.setRegister(0x02, 0xE0)

        when:
        new PCF8523Minimal(conn)

        then:
        lastWrite(conn, 0x02) == 0x00
    }

    def "decodes datetime and uses the STOP-bit sequence to set it"() {
        given:
        def conn = new MockConnection()
        conn.setRegister(0x03, 0xC5, 0x30, 0x14, 0x23, 0x03, 0x09, 0x26)
        conn.setRegister(0x00, 0x08)
        def rtc = new PCF8523Minimal(conn)

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
        (w[2] as List) == ([0x03, 0x45, 0x30, 0x14, 0x23, 0x03, 0x09, 0x26] as byte[]) as List
        w[3] as List == [0x00, 0x00]
    }

    def "alarm uses active-low enables"() {
        given:
        def conn = new MockConnection()
        def rtc = new PCF8523Full(conn)

        when:
        rtc.setAlarm(new PCF8523Full.Alarm(minute: 45, weekday: 6))
        def alarm = rtc.getAlarm()

        then:
        (0x0A..0x0D).collect { reg(conn, it) } == [0x45, 0x80, 0x80, 0x06]
        alarm.minute == 45
        alarm.hour == null
        alarm.day == null
        alarm.weekday == 6
    }

    def "offset is two's complement"() {
        given:
        def conn = new MockConnection()
        def rtc = new PCF8523Full(conn)

        when:
        rtc.setOffset(-64, PCF8523Full.OffsetMode.EVERY_MINUTE)
        def o = rtc.getOffset()

        then:
        reg(conn, 0x0E) == 0xC0
        o.offset == -64
        o.mode == PCF8523Full.OffsetMode.EVERY_MINUTE

        when:
        rtc.setOffset(63)
        o = rtc.getOffset()

        then:
        o.offset == 63
        o.mode == PCF8523Full.OffsetMode.EVERY_TWO_HOURS
    }

    def "configures timers and CLKOUT"() {
        given:
        def conn = new MockConnection()
        conn.setRegister(0x0F, 0x38)
        def rtc = new PCF8523Full(conn)

        when:
        rtc.configureTimerA(PCF8523Full.TimerAMode.WATCHDOG, 5, PCF8523Full.SourceClock.HZ_1, true)
        rtc.enableInterrupt(PCF8523Full.SOURCE_TIMER_A)

        then:
        reg(conn, 0x10) == 0x02
        reg(conn, 0x11) == 5
        reg(conn, 0x0F) == 0xBC
        (lastWrite(conn, 0x01) & 0x07) == 0x04

        when:
        conn.setRegister(0x0F, 0x38)
        rtc.configureTimerB(30, PCF8523Full.SourceClock.HZ_1_60, 130.0d, false)

        then:
        reg(conn, 0x12) == 0x43
        reg(conn, 0x0F) == 0x39

        when:
        conn.setRegister(0x0F, 0x00)
        rtc.setClockOutput(1)

        then:
        reg(conn, 0x0F) == 0x30

        when:
        rtc.disableClockOutput()

        then:
        reg(conn, 0x0F) == 0x38
    }

    def "selects direct battery switch-over without low detection"() {
        given:
        def conn = new MockConnection()
        def rtc = new PCF8523Full(conn)

        when:
        rtc.configureBatteryBackup(PCF8523Full.BatteryMode.DIRECT, false)

        then:
        (reg(conn, 0x02) & 0xE0) == 0xA0
    }

    def "pollInterrupt clears only the flags that were set"() {
        given:
        def conn = new MockConnection()
        def rtc = new PCF8523Full(conn)
        conn.setRegister(0x01, 0xAB)
        conn.setRegister(0x02, 0x0E)

        when:
        int status = rtc.pollInterrupt()

        then:
        status == (PCF8523Full.SOURCE_TIMER_A | PCF8523Full.SOURCE_TIMER_B | PCF8523Full.SOURCE_ALARM |
                PCF8523Full.SOURCE_BATTERY_SWITCH | PCF8523Full.SOURCE_BATTERY_LOW)
        lastWrite(conn, 0x01) == 0x53
        lastWrite(conn, 0x02) == 0x02
    }

    def "enables interrupt sources and resets"() {
        given:
        def conn = new MockConnection()
        def rtc = new PCF8523Full(conn)

        when:
        rtc.enableInterrupt(PCF8523Full.SOURCE_SECOND | PCF8523Full.SOURCE_ALARM | PCF8523Full.SOURCE_BATTERY_LOW)

        then:
        lastWrite(conn, 0x00) == 0x06
        (lastWrite(conn, 0x02) & 0x03) == 0x01

        when:
        rtc.disableInterrupt(PCF8523Full.SOURCE_ALARM)
        rtc.softwareReset()

        then:
        conn.writes().findAll { it.length == 2 && it[0] == 0x00 }.collect { it[1] & 0xFF }[-2..-1] == [0x04, 0x58]
    }
}
