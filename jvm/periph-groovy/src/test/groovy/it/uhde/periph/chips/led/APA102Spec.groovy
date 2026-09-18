package it.uhde.periph.chips.led

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class APA102Spec extends Specification {

    private static byte[] lastWrite(MockConnection connection) {
        def writes = connection.writes()
        writes[writes.size() - 1]
    }

    def "fill stores BGR+brightness order and off zeroes"() {
        given:
        def connection = new MockConnection()
        def d = new APA102Minimal(connection, 2)

        when:
        d.fill(10, 20, 30)

        then:
        // start(4x0x00) + pixels[2*4] + end(4x0xFF)
        // Pixel: [0xFF, B, G, R] = [0xFF, 30, 20, 10]
        lastWrite(connection) == [
            0, 0, 0, 0,
            0xFF, 30, 20, 10,
            0xFF, 30, 20, 10,
            0xFF, 0xFF, 0xFF, 0xFF
        ] as byte[]

        when:
        d.off()

        then:
        // off: fill(0,0,0) -> pixels [0xFF, 0, 0, 0] x2
        lastWrite(connection) == [
            0, 0, 0, 0,
            0xFF, 0, 0, 0,
            0xFF, 0, 0, 0,
            0xFF, 0xFF, 0xFF, 0xFF
        ] as byte[]
    }

    def "setPixel clamps index"() {
        given:
        def connection = new MockConnection()
        def d = new APA102Full(connection, 3)

        when:
        d.setPixel(-1, 1, 2, 3, 31)
        d.setPixel(1, 4, 5, 6, 31)
        d.setPixel(100, 7, 8, 9, 31)
        d.show()

        then:
        // Index -1 clamps to 0, so it writes pixel 0: [0xFF, 3, 2, 1].
        // Pixel 1: [0xFF, 6, 5, 4]. Index 100 clamps to n-1=2: [0xFF, 9, 8, 7].
        lastWrite(connection) == [
            0, 0, 0, 0,
            0xFF, 3, 2, 1,
            0xFF, 6, 5, 4,
            0xFF, 9, 8, 7,
            0xFF, 0xFF, 0xFF, 0xFF
        ] as byte[]
    }

    def "setPixel and rotate on zero-length strip do not throw"() {
        given:
        def connection = new MockConnection()
        def d = new APA102Full(connection, 0)

        when:
        d.setPixel(0, 1, 2, 3, 31)
        d.rotate(1)
        d.show()

        then:
        noExceptionThrown()
    }

    def "show scales by brightness"() {
        given:
        def connection = new MockConnection()
        def d = new APA102Full(connection, 1)
        d.setPixel(0, 200, 100, 50, 31)
        d.setBrightness(128)

        expect:
        d.getBrightness() == 128

        when:
        d.show()

        then:
        // Hardware brightness byte (0xFF) unchanged, RGB scaled
        lastWrite(connection) == [
            0, 0, 0, 0,
            0xFF,
            (byte) (50 * 128 / 255),
            (byte) (100 * 128 / 255),
            (byte) (200 * 128 / 255),
            0xFF, 0xFF, 0xFF, 0xFF
        ] as byte[]
    }

    def "rotate shifts left by whole pixels"() {
        given:
        def connection = new MockConnection()
        def d = new APA102Full(connection, 4)
        d.setPixel(0, 1, 0, 0, 31)
        d.setPixel(1, 2, 0, 0, 31)
        d.setPixel(2, 3, 0, 0, 31)
        d.setPixel(3, 4, 0, 0, 31)

        when:
        d.rotate(1)
        d.show()

        then:
        // After rotate(1): pixel 0 gets old 1 (R=2), pixel 3 gets old 0 (R=1)
        lastWrite(connection) == [
            0, 0, 0, 0,
            0xFF, 0, 0, 2,
            0xFF, 0, 0, 3,
            0xFF, 0, 0, 4,
            0xFF, 0, 0, 1,
            0xFF, 0xFF, 0xFF, 0xFF
        ] as byte[]
    }

    def "fillHsv pure red"() {
        given:
        def connection = new MockConnection()
        def d = new APA102Full(connection, 1)

        when:
        d.fillHsv(0.0d, 1.0d, 1.0d)

        then:
        // Red: R=255, G=0, B=0 -> wire: [0xFF, B=0, G=0, R=255]
        lastWrite(connection) == [
            0, 0, 0, 0,
            0xFF, 0, 0, 255,
            0xFF, 0xFF, 0xFF, 0xFF
        ] as byte[]
    }

    def "setPixel with custom hardware brightness"() {
        given:
        def connection = new MockConnection()
        def d = new APA102Full(connection, 1)

        when:
        d.setPixel(0, 10, 20, 30, 16) // brightness=16
        d.show()

        then:
        // Expected: [0xE0|16=0xF0, B=30, G=20, R=10]
        lastWrite(connection) == [
            0, 0, 0, 0,
            0xF0, 30, 20, 10,
            0xFF, 0xFF, 0xFF, 0xFF
        ] as byte[]
    }

    def "setPixels with hardware brightness"() {
        given:
        def connection = new MockConnection()
        def d = new APA102Full(connection, 4)

        when:
        d.setPixels([
            [10, 20, 30, 31] as int[],
            [40, 50, 60, 16] as int[],
            [70, 80, 90, 8] as int[],
            [100, 110, 120, 4] as int[]
        ])
        d.show()

        then:
        // pixel 0: [0xFF, 30, 20, 10]
        // pixel 1: [0xF0, 60, 50, 40]
        // pixel 2: [0xE8, 90, 80, 70]
        // pixel 3: [0xE4, 120, 110, 100]
        lastWrite(connection) == [
            0, 0, 0, 0,
            0xFF, 30, 20, 10,
            0xF0, 60, 50, 40,
            0xE8, 90, 80, 70,
            0xE4, 120, 110, 100,
            0xFF, 0xFF, 0xFF, 0xFF
        ] as byte[]
    }
}