package it.uhde.periph.chips.led;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class WS2812BTest {

    private static byte[] lastWrite(MockConnection conn) {
        var writes = conn.writes();
        return writes.isEmpty() ? null : writes.get(writes.size() - 1);
    }

    @Test
    void fillGrbOrderAndOff() throws Exception {
        var conn = new MockConnection();
        var d = new WS2812BMinimal(conn, 2);
        d.fill(10, 20, 30);
        assertArrayEquals(new byte[]{20, 10, 30, 20, 10, 30}, lastWrite(conn));

        d.off();
        assertArrayEquals(new byte[]{0, 0, 0, 0, 0, 0}, lastWrite(conn));
    }

    @Test
    void setPixelIndexClamp() throws Exception {
        var conn = new MockConnection();
        var d = new WS2812BFull(conn, 3);
        d.setPixel(-1, 1, 2, 3);
        d.setPixel(1, 4, 5, 6);
        d.setPixel(100, 7, 8, 9);
        d.show();
        assertArrayEquals(new byte[]{2, 1, 3, 5, 4, 6, 8, 7, 9}, lastWrite(conn));
    }

    @Test
    void setPixelAndRotateZeroLengthDoesNotThrow() throws Exception {
        var conn = new MockConnection();
        var d = new WS2812BFull(conn, 0);
        assertDoesNotThrow(() -> d.setPixel(0, 1, 2, 3));
        assertDoesNotThrow(() -> d.rotate(1));
        assertDoesNotThrow(d::show);
    }

    @Test
    void brightnessScaling() throws Exception {
        var conn = new MockConnection();
        var d = new WS2812BFull(conn, 1);
        d.setPixel(0, 200, 100, 50);
        d.setBrightness(128);
        assertEquals(128, d.getBrightness());
        d.show();
        byte[] want = {
            (byte) (100 * 128 / 255),
            (byte) (200 * 128 / 255),
            (byte) (50 * 128 / 255),
        };
        assertArrayEquals(want, lastWrite(conn));
    }

    @Test
    void rotateShiftsLeftByWholePixels() throws Exception {
        var conn = new MockConnection();
        var d = new WS2812BFull(conn, 4);
        d.setPixel(0, 1, 0, 0);
        d.setPixel(1, 2, 0, 0);
        d.setPixel(2, 3, 0, 0);
        d.setPixel(3, 4, 0, 0);
        d.rotate(1);
        d.show();
        assertArrayEquals(new byte[]{0, 2, 0, 0, 3, 0, 0, 4, 0, 0, 1, 0}, lastWrite(conn));
    }

    @Test
    void fillHsvRed() throws Exception {
        var conn = new MockConnection();
        var d = new WS2812BFull(conn, 1);
        d.fillHsv(0.0, 1.0, 1.0);
        assertArrayEquals(new byte[]{0, (byte) 255, 0}, lastWrite(conn));
    }
}
