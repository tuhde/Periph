package it.uhde.periph.chips.led

import it.uhde.periph.connection.MockConnection
import it.uhde.periph.connection.MockResetExtenderConnection
import spock.lang.Specification

class SK6812RGBWSpec extends Specification {

    private static final int RESET_BYTES = 24

    private static byte[] lastExtWrite(MockResetExtenderConnection connection) {
        def writes = connection.extWrites()
        writes[writes.size() - 1]
    }

    def "fill stores GRBW order and requests extended reset, off zeroes"() {
        given:
        def connection = new MockResetExtenderConnection()
        def d = new SK6812RGBWMinimal(connection, 2)

        when:
        d.fill(10, 20, 30, 40)

        then:
        lastExtWrite(connection) == [20, 10, 30, 40, 20, 10, 30, 40] as byte[]
        connection.extResetBytes().last() == RESET_BYTES

        when:
        d.off()

        then:
        lastExtWrite(connection) == [0, 0, 0, 0, 0, 0, 0, 0] as byte[]
    }

    def "fill white channel defaults to zero"() {
        given:
        def connection = new MockResetExtenderConnection()
        def d = new SK6812RGBWMinimal(connection, 1)

        when:
        d.fill(0, 0, 0, 255)

        then:
        lastExtWrite(connection) == [0, 0, 0, (byte) 255] as byte[]
    }

    def "falls back to plain write without ResetExtender"() {
        given:
        def connection = new MockConnection()
        def d = new SK6812RGBWMinimal(connection, 1)

        when:
        d.fill(1, 2, 3, 4)

        then:
        def writes = connection.writes()
        writes[writes.size() - 1] == [2, 1, 3, 4] as byte[]
    }

    def "setPixel clamps index"() {
        given:
        def connection = new MockResetExtenderConnection()
        def d = new SK6812RGBWFull(connection, 3)

        when:
        d.setPixel(-1, 1, 2, 3, 4)
        d.setPixel(1, 5, 6, 7, 8)
        d.setPixel(100, 9, 10, 11, 12)
        d.show()

        then:
        lastExtWrite(connection) == [2, 1, 3, 4, 6, 5, 7, 8, 10, 9, 11, 12] as byte[]
    }

    def "setPixel and rotate on zero-length strip do not throw"() {
        given:
        def connection = new MockResetExtenderConnection()
        def d = new SK6812RGBWFull(connection, 0)

        when:
        d.setPixel(0, 1, 2, 3, 4)
        d.rotate(1)
        d.show()

        then:
        noExceptionThrown()
    }

    def "show scales by brightness"() {
        given:
        def connection = new MockResetExtenderConnection()
        def d = new SK6812RGBWFull(connection, 1)
        d.setPixel(0, 200, 100, 50, 80)
        d.setBrightness(128)

        expect:
        d.getBrightness() == 128

        when:
        d.show()

        then:
        lastExtWrite(connection) == [
            (byte) (100 * 128 / 255),
            (byte) (200 * 128 / 255),
            (byte) (50 * 128 / 255),
            (byte) (80 * 128 / 255),
        ] as byte[]
    }

    def "rotate shifts left by whole pixels"() {
        given:
        def connection = new MockResetExtenderConnection()
        def d = new SK6812RGBWFull(connection, 4)
        d.setPixel(0, 1, 0, 0, 0)
        d.setPixel(1, 2, 0, 0, 0)
        d.setPixel(2, 3, 0, 0, 0)
        d.setPixel(3, 4, 0, 0, 0)

        when:
        d.rotate(1)
        d.show()

        then:
        lastExtWrite(connection) == [0, 2, 0, 0, 0, 3, 0, 0, 0, 4, 0, 0, 0, 1, 0, 0] as byte[]
    }

    def "fillHsv pure red"() {
        given:
        def connection = new MockResetExtenderConnection()
        def d = new SK6812RGBWFull(connection, 1)

        when:
        d.fillHsv(0.0d, 1.0d, 1.0d)

        then:
        lastExtWrite(connection) == [0, (byte) 255, 0, 0] as byte[]
    }
}
