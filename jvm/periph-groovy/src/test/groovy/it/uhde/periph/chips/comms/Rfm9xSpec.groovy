package it.uhde.periph.chips.comms

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.MockConnection
import it.uhde.periph.connection.OutputPin
import spock.lang.Specification

class Rfm9xSpec extends Specification {

    static final int REG_VERSION = 0x42
    static final int EXPECTED_VERSION = 0x12

    static class FakeOutputPin implements OutputPin {
        List<Boolean> calls = []
        void set(boolean high) { calls << high }
        void close() {}
    }

    static class FakeInputPin implements InputPin {
        void onEdge(EdgeHandler handler, EdgeTrigger trigger) {
            Thread.start {
                Thread.sleep(20)
                handler.onEdge()
            }
        }
        void offEdge(EdgeHandler handler) {}
        void close() {}
    }

    static MockConnection newConnection() {
        def c = new MockConnection()
        c.setRegister(REG_VERSION, EXPECTED_VERSION)
        return c
    }

    static boolean hasWrite(MockConnection connection, int... bytes) {
        byte[] want = new byte[bytes.length]
        bytes.eachWithIndex { b, i -> want[i] = (byte) b }
        return connection.writes().any { it == want }
    }

    def "init and send"() {
        given:
        def connection = newConnection()
        def sensor = new Rfm95Minimal(connection, 915_000_000L)

        expect:
        hasWrite(connection, 0x86, 0xE4)
        hasWrite(connection, 0x87, 0xC0)
        hasWrite(connection, 0x88, 0x00)
        hasWrite(connection, 0x9D, 0x72)
        hasWrite(connection, 0x9E, 0x77)
        hasWrite(connection, 0x89, 0x8F)

        when:
        def badVersionConn = new MockConnection()
        badVersionConn.setRegister(REG_VERSION, 0x99)
        new Rfm95Minimal(badVersionConn, 915_000_000L)

        then:
        thrown(IOException)

        when:
        new Rfm95Minimal(connection, 433_000_000L)

        then:
        thrown(IllegalArgumentException)

        when:
        def lfConnection = newConnection()
        new Rfm96Minimal(lfConnection, 433_000_000L)

        then:
        noExceptionThrown()

        when: "send([0xDE, 0xAD, 0xBE]): standby first (writes[0]), then FIFO/TX sequence"
        connection.setRegister(0x12, 0x08)
        def base = connection.writes().size()
        sensor.send([0xDE, 0xAD, 0xBE] as byte[])
        def w = connection.writes()

        then:
        w[base + 1] == [0x8D, 0x80] as byte[]
        w[base + 2] == [0x80, 0xDE, 0xAD, 0xBE] as byte[]
        w[base + 3] == [0xA2, 0x03] as byte[]
        w[base + 4] == [0xC0, 0x40] as byte[]
        w[base + 5] == [0x81, 0x83] as byte[]
        hasWrite(connection, 0x92, 0x08)
        w[-1] == [0x81, 0x81] as byte[]

        when: "NOTE: send() silently truncates an over-255-byte payload instead of rejecting it"
        connection.setRegister(0x12, 0x08)
        sensor.send(new byte[256])

        then:
        hasWrite(connection, 0xA2, 255)
    }

    def "receive"() {
        given:
        def connection = newConnection()
        def sensor = new Rfm95Minimal(connection, 915_000_000L)

        when:
        connection.setRegister(0x12, 0x40)
        connection.setRegister(0x10, 0x00)
        connection.setRegister(0x13, 0x03)
        connection.setRegister(0x00, 0xAA, 0xBB, 0xCC)
        def base = connection.writes().size()
        def payload = sensor.receive(100)

        then:
        connection.writes()[base + 2] == [0x81, 0x86] as byte[]
        payload == [0xAA, 0xBB, 0xCC] as byte[]
        hasWrite(connection, 0x92, 0x40)

        when:
        connection.setRegister(0x12, 0x00)
        def timeoutResult = sensor.receive(10)

        then:
        timeoutResult == null
    }

    def "configure, setFrequency, setTxPower"() {
        given:
        def connection = newConnection()
        def full = new Rfm95Full(connection, 915_000_000L)

        when:
        full.configure(9, 125.0f, 5, true)

        then:
        hasWrite(connection, 0xB1, 0x03)
        hasWrite(connection, 0x9D, 0x72)
        hasWrite(connection, 0x9E, 0x97)

        when: "RFM97's maxSf is 9 -- configure() clamps rather than throwing"
        def rfm97Connection = newConnection()
        def rfm97 = new Rfm97Full(rfm97Connection, 915_000_000L)
        rfm97.configure(12, 125.0f, 5, true)

        then:
        hasWrite(rfm97Connection, 0x9E, 0x97)

        when:
        full.setFrequency(868_000_000L)

        then:
        hasWrite(connection, 0x86, 0xD9)
        hasWrite(connection, 0x87, 0x00)

        when:
        full.setTxPower(20, true)

        then:
        hasWrite(connection, 0xCD, 0x87)
        hasWrite(connection, 0x8B, 0x3B)
        hasWrite(connection, 0x89, 0x8F)

        when:
        full.setTxPower(10, false)

        then:
        hasWrite(connection, 0x89, 0x7A)
    }

    def "telemetry and power control"() {
        given:
        def connection = newConnection()
        def full = new Rfm95Full(connection, 915_000_000L)

        when:
        full.standby()

        then:
        connection.writes()[-1] == [0x81, 0x81] as byte[]

        when:
        full.sleep()

        then:
        connection.writes()[-1] == [0x81, 0x80] as byte[]

        expect:
        full.version() == 0x12

        when:
        connection.setRegister(0x1B, 100)

        then:
        full.rssi() == -137f + 100f

        when:
        connection.setRegister(0x1A, 90)

        then:
        full.lastPacketRssi() == -137f + 90f

        when:
        connection.setRegister(0x19, 20)

        then:
        Math.abs(full.lastPacketSnr() - 5.0f) < 1e-6f

        when:
        connection.setRegister(0x19, 0xF4)

        then:
        Math.abs(full.lastPacketSnr() - (-3.0f)) < 1e-6f
    }

    def "receiveContinuous and readPacket"() {
        given:
        def connection = newConnection()
        def full = new Rfm95Full(connection, 915_000_000L)

        when:
        full.receiveContinuous()

        then:
        connection.writes()[-1] == [0x81, 0x85] as byte[]

        when:
        connection.setRegister(0x12, 0x40)
        connection.setRegister(0x10, 0x00)
        connection.setRegister(0x13, 0x02)
        connection.setRegister(0x00, 0x11, 0x22)

        then:
        full.readPacket() == [0x11, 0x22] as byte[]

        when:
        connection.setRegister(0x12, 0x00)

        then:
        full.readPacket() == null

        when:
        full.stopReceive()

        then:
        connection.writes()[-1] == [0x81, 0x81] as byte[]
    }

    def "receive interrupt"() {
        given:
        def connection = newConnection()
        def full = new Rfm95Full(connection, 915_000_000L)

        when:
        full.receive(2000, true)

        then:
        thrown(IllegalStateException)

        when:
        def dio0Connection = newConnection()
        dio0Connection.setRegister(0x12, 0x40)
        dio0Connection.setRegister(0x10, 0x00)
        dio0Connection.setRegister(0x13, 0x01)
        dio0Connection.setRegister(0x00, 0x99)
        def dio0 = new FakeInputPin()
        def fullWithDio0 = new Rfm95Full(dio0Connection, 915_000_000L, null, dio0)
        def payload = fullWithDio0.receive(2000, true)

        then:
        payload == [0x99] as byte[]
    }

    def "reset"() {
        given:
        def connection = newConnection()
        def full = new Rfm95Full(connection, 915_000_000L)

        when: "no resetPin -> falls back to a POR wait, not an error"
        full.reset()

        then:
        noExceptionThrown()

        when:
        def resetPin = new FakeOutputPin()
        def connection2 = newConnection()
        def withReset = new Rfm95Full(connection2, 915_000_000L, resetPin, null)

        then:
        resetPin.calls == [false, true]

        when:
        resetPin.calls.clear()
        withReset.reset()

        then:
        resetPin.calls == [false, true]
    }
}
