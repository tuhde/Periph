package it.uhde.periph.chips.rtc

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class DS3231Spec extends Specification {

    def "minimal datetime round trip"() {
        given:
        def conn = new MockConnection()
        conn.setRegister(0x00, 0x05, 0x30, 0x14, 0x02, 0x22, 0x09, 0x26)
        conn.setRegister(0x0F, 0x88)
        def rtc = new DS3231Minimal(conn)

        when:
        def dt = rtc.getDatetime()

        then:
        dt.year == 2026
        dt.month == 9
        dt.day == 22
        dt.weekday == 2
        dt.hour == 14
        dt.minute == 30
        dt.second == 5

        when:
        rtc.setDatetime(2027, 1, 1, 5, 0, 0, 0)

        then:
        conn.registers()[0x0F] == 0x08
    }

    def "reads positive and negative temperature"() {
        given:
        def conn = new MockConnection()
        conn.setRegister(0x11, 0x19, 0x40)
        def rtc = new DS3231Minimal(conn)

        expect:
        rtc.readTemperature() == 25.25d

        when:
        def conn2 = new MockConnection()
        conn2.setRegister(0x11, 0xFF, 0x80)
        def rtc2 = new DS3231Minimal(conn2)

        then:
        rtc2.readTemperature() == -0.5d
    }

    def "alarm1 match mode round trip"() {
        given:
        def conn = new MockConnection()
        def rtc = new DS3231Full(conn)

        when:
        rtc.setAlarm1(30, 15, 9, 0, false, DS3231Full.ALARM1_MATCH_HOURS_MINUTES_SECONDS)
        def alarm = rtc.getAlarm1()

        then:
        alarm.second == 30
        alarm.minute == 15
        alarm.hour == 9
        alarm.matchMode == DS3231Full.ALARM1_MATCH_HOURS_MINUTES_SECONDS
    }

    def "enabling an alarm interrupt sets INTCN and the source's enable bit"() {
        given:
        def conn = new MockConnection()
        def rtc = new DS3231Full(conn)

        when:
        rtc.enableInterrupt(DS3231Full.SOURCE_ALARM1)
        int control = conn.registers()[0x0E]

        then:
        (control & DS3231Minimal.CONTROL_INTCN) == DS3231Minimal.CONTROL_INTCN
        (control & DS3231Minimal.CONTROL_A1IE) == DS3231Minimal.CONTROL_A1IE
    }

    def "pollInterrupt clears only the alarm flags"() {
        given:
        def conn = new MockConnection()
        conn.setRegister(0x0F, 0x8B)
        def rtc = new DS3231Full(conn)

        when:
        int status = rtc.pollInterrupt()

        then:
        status == (DS3231Full.SOURCE_ALARM1 | DS3231Full.SOURCE_ALARM2)
        conn.registers()[0x0F] == 0x88
    }

    def "square wave and alarm interrupts share INTCN"() {
        given:
        def conn = new MockConnection()
        def rtc = new DS3231Full(conn)

        when:
        rtc.enableSquareWave(DS3231Full.SQUARE_WAVE_1024_HZ, true)

        then:
        (conn.registers()[0x0E] & DS3231Minimal.CONTROL_INTCN) == 0

        when:
        rtc.disableSquareWave()

        then:
        (conn.registers()[0x0E] & DS3231Minimal.CONTROL_INTCN) != 0
    }

    def "aging offset round trips as a signed byte"() {
        given:
        def conn = new MockConnection()
        def rtc = new DS3231Full(conn)

        when:
        rtc.setAgingOffset(-10)

        then:
        rtc.getAgingOffset() == -10

        when:
        rtc.setAgingOffset(100)

        then:
        rtc.getAgingOffset() == 100
    }
}
