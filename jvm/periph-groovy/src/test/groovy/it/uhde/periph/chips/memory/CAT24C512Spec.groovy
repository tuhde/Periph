package it.uhde.periph.chips.memory

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class CAT24C512Spec extends Specification {

    static MockConnection mock() {
        MockConnection c = new MockConnection()
        c.setAddressWidth(2)
        return c
    }

    /** Frames carrying data (two address bytes plus at least one data byte). */
    static List<byte[]> dataFrames(MockConnection c) {
        return c.writes().findAll { it.length > 2 }
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length)
        System.arraycopy(b, 0, out, a.length, b.length)
        return out
    }

    def "byte access"() {
        given:
        def connection = mock()
        def ee = new CAT24C512Full(connection)

        expect:
        connection.writes().isEmpty()
        ee.size() == 65536
        ee.pageSize() == 128

        when:
        connection.setRegister(0x1234, 0x42)

        then:
        ee.readByte(0x1234) == 0x42
        connection.writes().last() == [0x12, 0x34] as byte[]
        ee.readByte(0xFFFF) == 0

        when:
        connection.writes().clear()
        ee.writeByte(0x0010, 0x99)

        then:
        connection.registers().get(0x0010) == 0x99
        connection.writes()[0] == [0x00, 0x10, 0x99] as byte[]
        connection.writes()[1] == [0x00, 0x00] as byte[]

        when:
        ee.readByte(0x10000)

        then:
        thrown(IllegalArgumentException)

        when:
        ee.writeByte(0x10000, 1)

        then:
        thrown(IllegalArgumentException)
    }

    def "ack polling"() {
        given:
        def connection = mock()
        def ee = new CAT24C512Full(connection)

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
        def ee = new CAT24C512Full(connection)
        byte[] data = new byte[70]
        for (int i = 0; i < 70; i++) data[i] = (byte) i

        when:
        ee.write(0x0070, data)
        def frames = dataFrames(connection)

        then:
        ee.getMaxChunk() == 30
        frames.size() == 3
        frames[0] == concat([0x00, 0x70] as byte[], Arrays.copyOfRange(data, 0, 16))
        frames[1] == concat([0x00, 0x80] as byte[], Arrays.copyOfRange(data, 16, 46))
        frames[2] == concat([0x00, 0x9E] as byte[], Arrays.copyOfRange(data, 46, 70))
        connection.writes().size() == 6
        ee.read(0x0070, 70) == data

        when:
        ee.setMaxChunk(8)
        connection.writes().clear()
        ee.write(0x0200, new byte[20])

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
        def ee = new CAT24C512Full(connection)
        byte[] page = new byte[128]
        for (int i = 0; i < 128; i++) page[i] = (byte) i

        when:
        ee.writePage(0x0300, page)

        then:
        (0..127).every { connection.registers().get(0x0300 + it) == it }

        when:
        ee.writePage(0x037C, new byte[8])

        then:
        thrown(IllegalArgumentException)

        when:
        ee.writePage(0x0400, new byte[129])

        then:
        thrown(IllegalArgumentException)

        when:
        ee.fill(0x0500, 200, 0xAB)

        then:
        (0..199).every { connection.registers().get(0x0500 + it) == 0xAB }
        connection.registers().get(0x0500 + 200) == null

        when:
        ee.fill(0x0500, 4)

        then:
        connection.registers().get(0x0500) == 0
    }

    def "range rules"() {
        given:
        def connection = mock()
        def ee = new CAT24C512Full(connection)

        when:
        ee.write(0xFFFE, [1, 2] as byte[])

        then:
        ee.read(0xFFFE, 2) == [1, 2] as byte[]
        ee.read(0, 0).length == 0

        when:
        ee.write(0xFFFF, [1, 2] as byte[])

        then:
        thrown(IllegalArgumentException)

        when:
        ee.read(0xFFF0, 0x20)

        then:
        thrown(IllegalArgumentException)

        when:
        ee.fill(0xFFFF, 2, 0)

        then:
        thrown(IllegalArgumentException)
    }
}
