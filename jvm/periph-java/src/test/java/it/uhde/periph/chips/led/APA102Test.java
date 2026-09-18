package it.uhde.periph.chips.led;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class APA102Test {

    private static byte[] lastWrite(MockConnection conn) {
        var writes = conn.writes();
        return writes.isEmpty() ? null : writes.get(writes.size() - 1);
    }

    @Test
    void fillBgrOrderAndOff() throws Exception {
        var conn = new MockConnection();
        var d = new APA102Minimal(conn, 2);
        d.fill(10, 20, 30);
        // Expected: start(4x0x00) + pixels[2*4] + end(4x0xFF)
        // Pixel: [0xFF, B, G, R] = [0xFF, 30, 20, 10]
        assertArrayEquals(
            new byte[]{0, 0, 0, 0, (byte)0xFF, 30, 20, 10, (byte)0xFF, 30, 20, 10, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF},
            lastWrite(conn)
        );

        d.off();
        // off: fill(0,0,0) -> pixels [0xFF, 0, 0, 0] x2
        assertArrayEquals(
            new byte[]{0, 0, 0, 0, (byte)0xFF, 0, 0, 0, (byte)0xFF, 0, 0, 0, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF},
            lastWrite(conn)
        );
    }

    @Test
    void setPixelIndexClamp() throws Exception {
        var conn = new MockConnection();
        var d = new APA102Full(conn, 3);
        d.setPixel(-1, 1, 2, 3, 31);
        d.setPixel(1, 4, 5, 6, 31);
        d.setPixel(100, 7, 8, 9, 31);
        d.show();
        // Index -1 clamps to 0, so it writes pixel 0: [0xFF, 3, 2, 1].
        // Pixel 1: [0xFF, 6, 5, 4]. Index 100 clamps to n-1=2: [0xFF, 9, 8, 7].
        assertArrayEquals(
            new byte[]{0, 0, 0, 0, (byte)0xFF, 3, 2, 1, (byte)0xFF, 6, 5, 4, (byte)0xFF, 9, 8, 7, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF},
            lastWrite(conn)
        );
    }

    @Test
    void setPixelAndRotateZeroLengthDoesNotThrow() throws Exception {
        var conn = new MockConnection();
        var d = new APA102Full(conn, 0);
        assertDoesNotThrow(() -> d.setPixel(0, 1, 2, 3, 31));
        assertDoesNotThrow(() -> d.rotate(1));
        assertDoesNotThrow(d::show);
    }

    @Test
    void brightnessScaling() throws Exception {
        var conn = new MockConnection();
        var d = new APA102Full(conn, 1);
        d.setPixel(0, 200, 100, 50, 31);
        d.setBrightness(128);
        assertEquals(128, d.getBrightness());
        d.show();
        // Hardware brightness byte (0xFF) unchanged, RGB scaled.
        // Full frame: start(4x0x00) + pixel[4] + end(4x0xFF).
        byte[] want = {
            0, 0, 0, 0,
            (byte) 0xFF,
            (byte) (50 * 128 / 255),
            (byte) (100 * 128 / 255),
            (byte) (200 * 128 / 255),
            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
        };
        assertArrayEquals(want, lastWrite(conn));
    }

    @Test
    void rotateShiftsLeftByWholePixels() throws Exception {
        var conn = new MockConnection();
        var d = new APA102Full(conn, 4);
        d.setPixel(0, 1, 0, 0, 31);
        d.setPixel(1, 2, 0, 0, 31);
        d.setPixel(2, 3, 0, 0, 31);
        d.setPixel(3, 4, 0, 0, 31);
        d.rotate(1);
        d.show();
        // After rotate(1): pixel 0 gets old 1 (R=2), pixel 3 gets old 0 (R=1)
        assertArrayEquals(
            new byte[]{0, 0, 0, 0, (byte)0xFF, 0, 0, 2, (byte)0xFF, 0, 0, 3, (byte)0xFF, 0, 0, 4, (byte)0xFF, 0, 0, 1, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF},
            lastWrite(conn)
        );
    }

    @Test
    void fillHsvRed() throws Exception {
        var conn = new MockConnection();
        var d = new APA102Full(conn, 1);
        d.fillHsv(0.0, 1.0, 1.0);
        // Red: R=255, G=0, B=0 -> wire: [0xFF, B=0, G=0, R=255]
        assertArrayEquals(new byte[]{0, 0, 0, 0, (byte)0xFF, 0, 0, (byte)255, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF}, lastWrite(conn));
    }

    @Test
    void setPixelHardwareBrightness() throws Exception {
        var conn = new MockConnection();
        var d = new APA102Full(conn, 1);
        d.setPixel(0, 10, 20, 30, 16); // brightness=16
        d.show();
        // Expected: [0xE0|16=0xF0, B=30, G=20, R=10]
        assertArrayEquals(new byte[]{0, 0, 0, 0, (byte)0xF0, 30, 20, 10, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF}, lastWrite(conn));
    }

    @Test
    void setPixelsWithHardwareBrightness() throws Exception {
        var conn = new MockConnection();
        var d = new APA102Full(conn, 4);
        d.setPixels(new int[][]{
            {10, 20, 30, 31},
            {40, 50, 60, 16},
            {70, 80, 90, 8},
            {100, 110, 120, 4}
        });
        d.show();
        // pixel 0: [0xFF, 30, 20, 10]
        // pixel 1: [0xF0, 60, 50, 40]
        // pixel 2: [0xE8, 90, 80, 70]
        // pixel 3: [0xE4, 120, 110, 100]
        assertArrayEquals(
            new byte[]{
                0, 0, 0, 0,
                (byte)0xFF, 30, 20, 10,
                (byte)0xF0, 60, 50, 40,
                (byte)0xE8, 90, 80, 70,
                (byte)0xE4, (byte)120, 110, 100,
                (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF
            },
            lastWrite(conn)
        );
    }
}