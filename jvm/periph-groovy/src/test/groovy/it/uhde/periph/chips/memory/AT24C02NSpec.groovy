package it.uhde.periph.chips.memory

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class AT24C02NSpec extends Specification {

    static MockConnection mock() {
        MockConnection c = new MockConnection()
                return c
    }

    /** Frames carrying data (one address byte plus at least one data byte). */
    static List<byte[]> dataFrames(MockConnection c) {
        return c.writes().findAll { it.length > 1 }
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length)
        System.arraycopy(b, 0, out, a.length, b.length)
        return out
    }

    def "byte access"() {
        given:
        def connection = mock()
        def ee = new AT24C02NFull(connection)

        expect:
        connection.writes().isEmpty()
        ee.size() == 256
        ee.pageSize() == 8

        when:
        connection.setRegister(0x0034, 0x42)

        then:
        ee.readByte(0x0034) == 0x42
        connection.writes().last() == [0x34] as byte[]
        ee.readByte(0x00FF) == 0

        when:
        connection.writes().clear()
        ee.writeByte(0x0010, 0x99)

        then:
        connection.registers().get(0x0010) == 0x99
        connection.writes()[0] == [0x10, 0x99] as byte[]
        connection.writes()[1] == [0x00] as byte[]

        when:
        ee.readByte(0x0100)

        then:
        thrown(IllegalArgumentException)

        when:
        ee.writeByte(0x0100, 1)

        then:
        thrown(IllegalArgumentException)
    }

    def "ack polling"() {
        given:
        def connection = mock()
        def ee = new AT24C02NFull(connection)

        when:
        connection.failNextWrites(2, 0)
        connection.writes().clear()
        def ready = ee.waitReady(50)

        then:
        ready
        connection.writes().size() == 3

        when:
        connection.failNextWrites(100000, 0)

        then:
        !ee.waitReady(5)

        when:
        connection.failNextWrites(0, 0)
        connection.failNextWrites(100000, 1)
        ee.writeByte(0x0021, 2)

        then:
        thrown(IOException)
    }

    def "page split write"() {
        given:
        def connection = mock()
        def ee = new AT24C02NFull(connection)
        byte[] data = new byte[20]
        for (int i = 0; i < 20; i++) data[i] = (byte) i

        when:
        ee.write(0x0074, data)
        def frames = dataFrames(connection)

        then:
        ee.getMaxChunk() == 30
        frames.size() == 3
        frames[0] == concat([0x74] as byte[], Arrays.copyOfRange(data, 0, 4))
        frames[1] == concat([0x78] as byte[], Arrays.copyOfRange(data, 4, 12))
        frames[2] == concat([0x80] as byte[], Arrays.copyOfRange(data, 12, 20))
        connection.writes().size() == 6
        ee.read(0x0074, 20) == data

        when:
        ee.setMaxChunk(8)
        connection.writes().clear()
        ee.write(0x00E0, new byte[20])

        then:
        dataFrames(connection).size() == 3

        when:
        ee.setMaxChunk(0)

        then:
        thrown(IllegalArgumentException)
    }

    def "write page and fill"() {
        given:
        def connection = mock()
        def ee = new AT24C02NFull(connection)
        byte[] page = new byte[8]
        for (int i = 0; i < 8; i++) page[i] = (byte) i

        when:
        ee.writePage(0x00C0, page)

        then:
        (0..7).every { connection.registers().get(0x00C0 + it) == it }

        when:
        ee.writePage(0x00CC, new byte[8])

        then:
        thrown(IllegalArgumentException)

        when:
        ee.writePage(0x00D0, new byte[9])

        then:
        thrown(IllegalArgumentException)

        when:
        ee.fill(0x0040, 100, 0xAB)

        then:
        (0..99).every { connection.registers().get(0x0040 + it) == 0xAB }
        connection.registers().get(0x0040 + 100) == null

        when:
        ee.fill(0x0040, 4)

        then:
        connection.registers().get(0x0040) == 0
    }

    def "range rules"() {
        given:
        def connection = mock()
        def ee = new AT24C02NFull(connection)

        when:
        ee.write(0x00FE, [1, 2] as byte[])

        then:
        ee.read(0x00FE, 2) == [1, 2] as byte[]
        ee.read(0, 0).length == 0

        when:
        ee.write(0x00FF, [1, 2] as byte[])

        then:
        thrown(IllegalArgumentException)

        when:
        ee.read(0x00F0, 0x20)

        then:
        thrown(IllegalArgumentException)

        when:
        ee.fill(0x00FF, 2, 0)

        then:
        thrown(IllegalArgumentException)
    }
}
