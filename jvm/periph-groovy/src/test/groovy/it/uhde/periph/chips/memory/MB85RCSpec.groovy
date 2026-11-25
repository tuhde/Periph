package it.uhde.periph.chips.memory

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class MB85RCSpec extends Specification {

    private static MockConnection mock(int width) {
        def c = new MockConnection()
        c.setAddressWidth(width)
        c
    }

    def "full API"() {
        given:
        def connection = mock(2)
        def fram = new MB85RCFull(connection)

        expect:
        connection.writes().isEmpty()
        fram.size() == 32768

        when:
        connection.setRegister(0x1234, 0x42)

        then:
        fram.readByte(0x1234) == 0x42
        connection.writes().last() == [0x12, 0x34] as byte[]

        when:
        fram.writeByte(0x0010, 0x99)

        then:
        connection.registers().get(0x0010) == 0x99
        connection.writes().last() == [0x00, 0x10, 0x99] as byte[]

        when:
        fram.readByte(0x8000)

        then:
        thrown(IllegalArgumentException)

        when:
        fram.writeByte(0x8000, 1)

        then:
        thrown(IllegalArgumentException)

        when: "70 bytes at maxChunk=30 -> 30 + 30 + 10"
        byte[] data = (0..<70).collect { (byte) it } as byte[]
        connection.writes().clear()
        fram.write(0x0100, data)

        then:
        fram.maxChunk == 30
        connection.writes().size() == 3
        connection.writes()[0].length == 32
        connection.writes()[2].length == 12
        (connection.writes()[1][1] & 0xFF) == 30

        when:
        connection.writes().clear()
        def back = fram.read(0x0100, 70)

        then:
        back == data
        connection.writes().size() == 3

        when:
        fram.maxChunk = 8
        connection.writes().clear()
        fram.write(0x0200, new byte[20])

        then:
        connection.writes().size() == 3

        when:
        fram.maxChunk = 0

        then:
        thrown(IllegalArgumentException)

        when:
        fram.maxChunk = 30
        fram.fill(0x0300, 65, 0xAB)

        then:
        (0..<65).every { connection.registers().get(0x0300 + it) == 0xAB }
        connection.registers().get(0x0300 + 65) == null

        when:
        fram.fill(0x0300, 4)

        then:
        (0..<4).every { connection.registers().get(0x0300 + it) == 0 }

        when:
        fram.write(0x7FFE, [1, 2] as byte[])

        then:
        fram.read(0x7FFE, 2) == [1, 2] as byte[]
        fram.read(0, 0).length == 0

        when:
        fram.write(0x7FFF, [1, 2] as byte[])

        then:
        thrown(IllegalArgumentException)

        when:
        fram.read(0x7FF0, 0x20)

        then:
        thrown(IllegalArgumentException)

        when:
        fram.fill(0x7FFF, 2)

        then:
        thrown(IllegalArgumentException)
    }

    def "device ID"() {
        given:
        def fram = new MB85RCFull(mock(2))
        def id = mock(1)
        id.setRegister(0xA6, 0x00, 0xA5, 0x10)

        expect:
        fram.readDeviceId(id, 0x53) == [0x00A, 0x5, 0x10] as int[]
        id.writes().last() == [0xA6] as byte[]

        when:
        id.setRegister(0xA0, 0x00, 0xA5, 0x10)

        then:
        fram.readDeviceId(id) == [0x00A, 0x5, 0x10] as int[]
    }

    def "minimal"() {
        given:
        def fram = new MB85RCMinimal(mock(2))

        when:
        fram.writeByte(0x0005, 0x77)

        then:
        fram.readByte(0x0005) == 0x77
    }
}
