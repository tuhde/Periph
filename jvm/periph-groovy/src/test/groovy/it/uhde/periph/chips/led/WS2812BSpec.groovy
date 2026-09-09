package it.uhde.periph.chips.led

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class WS2812BSpec extends Specification {

    private static byte[] lastWrite(MockConnection connection) {
        def writes = connection.writes()
        writes[writes.size() - 1]
    }

    def "fill stores GRB order and off zeroes"() {
        given:
        def connection = new MockConnection()
        def d = new WS2812BMinimal(connection, 2)

        when:
        d.fill(10, 20, 30)

        then:
        lastWrite(connection) == [20, 10, 30, 20, 10, 30] as byte[]

        when:
        d.off()

        then:
        lastWrite(connection) == [0, 0, 0, 0, 0, 0] as byte[]
    }

    def "setPixel clamps index"() {
        given:
        def connection = new MockConnection()
        def d = new WS2812BFull(connection, 3)

        when:
        d.setPixel(-1, 1, 2, 3)
        d.setPixel(1, 4, 5, 6)
        d.setPixel(100, 7, 8, 9)
        d.show()

        then:
        lastWrite(connection) == [2, 1, 3, 5, 4, 6, 8, 7, 9] as byte[]
    }

    def "setPixel and rotate on zero-length strip do not throw"() {
        given:
        def connection = new MockConnection()
        def d = new WS2812BFull(connection, 0)

        when:
        d.setPixel(0, 1, 2, 3)
        d.rotate(1)
        d.show()

        then:
        noExceptionThrown()
    }

    def "show scales by brightness"() {
        given:
        def connection = new MockConnection()
        def d = new WS2812BFull(connection, 1)
        d.setPixel(0, 200, 100, 50)
        d.setBrightness(128)

        expect:
        d.getBrightness() == 128

        when:
        d.show()

        then:
        lastWrite(connection) == [
            (byte) (100 * 128 / 255),
            (byte) (200 * 128 / 255),
            (byte) (50 * 128 / 255),
        ] as byte[]
    }

    def "rotate shifts left by whole pixels"() {
        given:
        def connection = new MockConnection()
        def d = new WS2812BFull(connection, 4)
        d.setPixel(0, 1, 0, 0)
        d.setPixel(1, 2, 0, 0)
        d.setPixel(2, 3, 0, 0)
        d.setPixel(3, 4, 0, 0)

        when:
        d.rotate(1)
        d.show()

        then:
        lastWrite(connection) == [0, 2, 0, 0, 3, 0, 0, 4, 0, 0, 1, 0] as byte[]
    }

    def "fillHsv pure red"() {
        given:
        def connection = new MockConnection()
        def d = new WS2812BFull(connection, 1)

        when:
        d.fillHsv(0.0d, 1.0d, 1.0d)

        then:
        lastWrite(connection) == [0, (byte) 255, 0] as byte[]
    }
}
