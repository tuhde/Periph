package it.uhde.periph.chips.led;

import it.uhde.periph.connection.MockConnection;
import it.uhde.periph.connection.MockResetExtenderConnection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class SK6812RGBWTest {

    private static final int RESET_BYTES = 24;

    private static byte[] lastExtWrite(MockResetExtenderConnection conn) {
        var writes = conn.extWrites();
        return writes.isEmpty() ? null : writes.get(writes.size() - 1);
    }

    @Test
    void fillGrbwOrderAndOffRequestExtendedReset() throws Exception {
        var conn = new MockResetExtenderConnection();
        var d = new SK6812RGBWMinimal(conn, 2);
        d.fill(10, 20, 30, 40);
        assertArrayEquals(new byte[]{20, 10, 30, 40, 20, 10, 30, 40}, lastExtWrite(conn));
        assertEquals(RESET_BYTES, conn.extResetBytes().get(conn.extResetBytes().size() - 1));

        d.off();
        assertArrayEquals(new byte[]{0, 0, 0, 0, 0, 0, 0, 0}, lastExtWrite(conn));
    }

    @Test
    void fillWhiteDefaultsZero() throws Exception {
        var conn = new MockResetExtenderConnection();
        var d = new SK6812RGBWMinimal(conn, 1);
        d.fill(0, 0, 0, 255);
        assertArrayEquals(new byte[]{0, 0, 0, (byte) 255}, lastExtWrite(conn));
    }

    @Test
    void fallsBackToPlainWriteWithoutResetExtender() throws Exception {
        var conn = new MockConnection();
        var d = new SK6812RGBWMinimal(conn, 1);
        d.fill(1, 2, 3, 4);
        var writes = conn.writes();
        assertArrayEquals(new byte[]{2, 1, 3, 4}, writes.get(writes.size() - 1));
    }

    @Test
    void setPixelIndexClamp() throws Exception {
        var conn = new MockResetExtenderConnection();
        var d = new SK6812RGBWFull(conn, 3);
        d.setPixel(-1, 1, 2, 3, 4);
        d.setPixel(1, 5, 6, 7, 8);
        d.setPixel(100, 9, 10, 11, 12);
        d.show();
        assertArrayEquals(new byte[]{2, 1, 3, 4, 6, 5, 7, 8, 10, 9, 11, 12}, lastExtWrite(conn));
    }

    @Test
    void setPixelAndRotateZeroLengthDoesNotThrow() throws Exception {
        var conn = new MockResetExtenderConnection();
        var d = new SK6812RGBWFull(conn, 0);
        assertDoesNotThrow(() -> d.setPixel(0, 1, 2, 3, 4));
        assertDoesNotThrow(() -> d.rotate(1));
        assertDoesNotThrow(d::show);
    }

    @Test
    void brightnessScaling() throws Exception {
        var conn = new MockResetExtenderConnection();
        var d = new SK6812RGBWFull(conn, 1);
        d.setPixel(0, 200, 100, 50, 80);
        d.setBrightness(128);
        assertEquals(128, d.getBrightness());
        d.show();
        byte[] want = {
            (byte) (100 * 128 / 255),
            (byte) (200 * 128 / 255),
            (byte) (50 * 128 / 255),
            (byte) (80 * 128 / 255),
        };
        assertArrayEquals(want, lastExtWrite(conn));
    }

    @Test
    void rotateShiftsLeftByWholePixels() throws Exception {
        var conn = new MockResetExtenderConnection();
        var d = new SK6812RGBWFull(conn, 4);
        d.setPixel(0, 1, 0, 0, 0);
        d.setPixel(1, 2, 0, 0, 0);
        d.setPixel(2, 3, 0, 0, 0);
        d.setPixel(3, 4, 0, 0, 0);
        d.rotate(1);
        d.show();
        assertArrayEquals(
            new byte[]{0, 2, 0, 0, 0, 3, 0, 0, 0, 4, 0, 0, 0, 1, 0, 0}, lastExtWrite(conn));
    }

    @Test
    void fillHsvRed() throws Exception {
        var conn = new MockResetExtenderConnection();
        var d = new SK6812RGBWFull(conn, 1);
        d.fillHsv(0.0, 1.0, 1.0);
        assertArrayEquals(new byte[]{0, (byte) 255, 0, 0}, lastExtWrite(conn));
    }
}
