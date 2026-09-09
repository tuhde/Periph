package it.uhde.periph.chips.gnss;

import it.uhde.periph.connection.Neo6ConnectionMock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Neo6Minimal} / {@link Neo6Full} against {@link Neo6ConnectionMock} —
 * no hardware, no bus. Mirrors the coverage of python/tests/gnss/neo6_test_unit.py and
 * go/periph/chips/gnss/neo6_test.go, adapted to JUnit idiom. The JVM connection library has
 * no SPI connection type, so {@link Neo6Minimal.BusType} only offers UART and I2C (unlike
 * Python/Go/C++/Node.js/Rust, which also cover SPI).
 */
class Neo6Test {

    private static final double EPS = 1e-6;

    // Field lists are built explicitly and joined with ',' rather than hand-typed as
    // comma-heavy literals, to avoid miscounting empty fields.
    private static final String GGA_FIX = String.join(",",
            "GPGGA", "092750.000", "5321.6802", "N", "00630.3372", "W",
            "1", "08", "1.03", "61.7", "M", "55.2", "M", "", "");
    private static final String GGA_NO_FIX = String.join(",",
            "GPGGA", "092750.000", "", "", "", "",
            "0", "00", "", "", "", "", "", "", "");
    private static final String RMC = String.join(",",
            "GPRMC", "092750.000", "A", "5321.6802", "N", "00630.3372", "W",
            "022.4", "084.4", "230394", "003.1", "W", "A");
    private static final String VTG = String.join(",",
            "GPVTG", "084.4", "T", "077.4", "M", "022.4", "N", "041.5", "K", "A");

    /** Builds a "$&lt;body&gt;*XX\r\n" NMEA sentence with a correct XOR checksum. */
    private static byte[] nmeaSentence(String body) throws IOException {
        int checksum = 0;
        for (byte b : body.getBytes(StandardCharsets.US_ASCII)) {
            checksum ^= (b & 0xFF);
        }
        String s = "$" + body + String.format("*%02X\r\n", checksum);
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Builds a UBX frame with a correct Fletcher checksum, computed here — independent of
     * Neo6Minimal's own (package-private) ubxChecksum().
     */
    private static byte[] ubxFrame(int msgClass, int msgId, byte[] payload) throws IOException {
        int length = payload.length;
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(msgClass);
        body.write(msgId);
        body.write(length & 0xFF);
        body.write((length >> 8) & 0xFF);
        body.write(payload);
        byte[] bodyBytes = body.toByteArray();

        int ckA = 0, ckB = 0;
        for (byte b : bodyBytes) {
            ckA = (ckA + (b & 0xFF)) & 0xFF;
            ckB = (ckB + ckA) & 0xFF;
        }

        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0xB5);
        frame.write(0x62);
        frame.write(bodyBytes);
        frame.write(ckA);
        frame.write(ckB);
        return frame.toByteArray();
    }

    private static byte[] ubxFrame(int msgClass, int msgId) throws IOException {
        return ubxFrame(msgClass, msgId, new byte[0]);
    }

    /** Queues data and drives update() enough times to consume it all. */
    private static boolean feed(Neo6Minimal gps, Neo6ConnectionMock conn, byte[] data) throws IOException {
        conn.queueBytes(data);
        boolean gotFix = false;
        for (int i = 0; i < data.length; i++) {
            if (gps.update()) {
                gotFix = true;
            }
        }
        return gotFix;
    }

    // --- Neo6Minimal: GGA decode across both bus types (UART, I2C) ---

    @ParameterizedTest
    @EnumSource(Neo6Minimal.BusType.class)
    void ggaDecodeAcrossBusTypes(Neo6Minimal.BusType busType) throws IOException {
        Neo6ConnectionMock conn = new Neo6ConnectionMock();
        Neo6Minimal gps = new Neo6Minimal(conn, busType);
        assertEquals(0, gps.fix());
        assertNull(gps.latitude());

        boolean gotFix = feed(gps, conn, nmeaSentence(GGA_FIX));
        assertTrue(gotFix, "update() never reported a fix");
        assertEquals(1, gps.fix());
        assertEquals(8, gps.satellites());
        assertEquals(53.361336667, gps.latitude(), EPS);
        assertEquals(-6.505620, gps.longitude(), EPS);
        assertEquals(61.7, gps.altitude(), 1e-3);
    }

    // --- Neo6Minimal: no-fix GGA updates fix/satellites but not lat/lon ---

    @Test
    void noFixKeepsLastPosition() throws IOException {
        Neo6ConnectionMock conn = new Neo6ConnectionMock();
        Neo6Minimal gps = new Neo6Minimal(conn);
        feed(gps, conn, nmeaSentence(GGA_FIX));
        boolean gotFix = feed(gps, conn, nmeaSentence(GGA_NO_FIX));
        assertFalse(gotFix);
        assertEquals(0, gps.fix());
        assertEquals(53.361336667, gps.latitude(), EPS);
    }

    // --- Checksum validation: a corrupted sentence is silently discarded ---

    @Test
    void badChecksumDiscarded() throws IOException {
        Neo6ConnectionMock conn = new Neo6ConnectionMock();
        Neo6Minimal gps = new Neo6Minimal(conn);
        byte[] bad = nmeaSentence(GGA_FIX);
        bad[bad.length - 4] ^= (byte) 0xFF; // corrupt one checksum hex digit
        boolean gotFix = feed(gps, conn, bad);
        assertFalse(gotFix);
        assertEquals(0, gps.fix());
    }

    // --- Leading 0xFF idle-filler bytes before '$' are ignored ---

    @Test
    void leadingGarbageIgnored() throws IOException {
        Neo6ConnectionMock conn = new Neo6ConnectionMock();
        Neo6Minimal gps = new Neo6Minimal(conn);
        List<Byte> data = new ArrayList<>();
        data.add((byte) 0xFF);
        data.add((byte) 0xFF);
        data.add((byte) 0xFF);
        byte[] sentence = nmeaSentence(GGA_FIX);
        byte[] full = new byte[data.size() + sentence.length];
        for (int i = 0; i < data.size(); i++) full[i] = data.get(i);
        System.arraycopy(sentence, 0, full, data.size(), sentence.length);
        boolean gotFix = feed(gps, conn, full);
        assertTrue(gotFix, "update() never reported a fix after leading garbage");
    }

    // --- Neo6Full: RMC (speed/course/utcTime/utcDate) ---

    @Test
    void rmcFields() throws IOException {
        Neo6ConnectionMock conn = new Neo6ConnectionMock();
        Neo6Full gps = new Neo6Full(conn);
        feed(gps, conn, nmeaSentence(RMC));
        assertEquals(22.4 * 0.514444, gps.speed(), 1e-4);
        assertEquals(84.4, gps.course(), EPS);
        assertEquals("092750.000", gps.utcTime());
        assertEquals("230394", gps.utcDate());
    }

    // --- Neo6Full: VTG (course/speed) ---

    @Test
    void vtgFields() throws IOException {
        Neo6ConnectionMock conn = new Neo6ConnectionMock();
        Neo6Full gps = new Neo6Full(conn);
        feed(gps, conn, nmeaSentence(VTG));
        assertEquals(84.4, gps.course(), EPS);
        assertEquals(41.5 / 3.6, gps.speed(), 1e-4);
    }

    // --- Neo6Full: GGA-derived HDOP ---

    @Test
    void ggaHdop() throws IOException {
        Neo6ConnectionMock conn = new Neo6ConnectionMock();
        Neo6Full gps = new Neo6Full(conn);
        feed(gps, conn, nmeaSentence(GGA_FIX));
        assertEquals(1.03, gps.hdop(), EPS);
    }

    // --- Neo6Full: sendUbx frames a correct message ---

    @Test
    void sendUbxFramesCorrectly() throws IOException {
        Neo6ConnectionMock conn = new Neo6ConnectionMock();
        Neo6Full gps = new Neo6Full(conn);
        gps.sendUbx(0x06, 0x08, new byte[]{1, 2, 3});
        assertArrayEquals(ubxFrame(0x06, 0x08, new byte[]{1, 2, 3}), lastWrite(conn));
    }

    // --- Neo6Full: setRate / setPlatform / coldStart / saveConfig ---

    @Test
    void setRateSendsCfgRate() throws IOException {
        Neo6ConnectionMock conn = new Neo6ConnectionMock();
        Neo6Full gps = new Neo6Full(conn);
        gps.setRate(5);
        int measRateMs = 1000 / 5;
        byte[] expected = {
                (byte) (measRateMs & 0xFF), (byte) ((measRateMs >> 8) & 0xFF),
                1, 0,
                0, 0
        };
        assertArrayEquals(ubxFrame(0x06, 0x08, expected), lastWrite(conn));
    }

    @Test
    void setPlatformSendsCfgNav5() throws IOException {
        Neo6ConnectionMock conn = new Neo6ConnectionMock();
        Neo6Full gps = new Neo6Full(conn);
        gps.setPlatform(4);
        byte[] expected = new byte[36];
        expected[0] = 0x01;
        expected[2] = 4;
        assertArrayEquals(ubxFrame(0x06, 0x24, expected), lastWrite(conn));
    }

    @Test
    void coldStartSendsCfgRst() throws IOException {
        Neo6ConnectionMock conn = new Neo6ConnectionMock();
        Neo6Full gps = new Neo6Full(conn);
        gps.coldStart();
        byte[] expected = {(byte) 0xFF, (byte) 0xFF, 0x02, 0x00};
        assertArrayEquals(ubxFrame(0x06, 0x04, expected), lastWrite(conn));
    }

    @Test
    void saveConfigSendsCfgCfg() throws IOException {
        Neo6ConnectionMock conn = new Neo6ConnectionMock();
        Neo6Full gps = new Neo6Full(conn);
        gps.saveConfig();
        byte[] expected = {
                0, 0, 0, 0,
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
                0, 0, 0, 0,
                0x07
        };
        assertArrayEquals(ubxFrame(0x06, 0x09, expected), lastWrite(conn));
    }

    // --- Neo6Full: pollUbx returns the response payload on a matching frame ---

    @Test
    void pollUbxReturnsPayload() throws IOException {
        Neo6ConnectionMock conn = new Neo6ConnectionMock();
        Neo6Full gps = new Neo6Full(conn);
        byte[] responsePayload = new byte[28];
        for (int i = 0; i < 28; i++) responsePayload[i] = (byte) i;
        conn.queueBytes(ubxFrame(0x01, 0x02, responsePayload));
        byte[] payload = gps.pollUbx(0x01, 0x02);
        assertArrayEquals(responsePayload, payload);
        assertArrayEquals(ubxFrame(0x01, 0x02), conn.writes().get(0));
    }

    // --- Neo6Full: pollUbx throws IOException on an ACK-NAK response ---

    @Test
    void pollUbxNakThrows() throws IOException {
        Neo6ConnectionMock conn = new Neo6ConnectionMock();
        Neo6Full gps = new Neo6Full(conn);
        conn.queueBytes(ubxFrame(0x05, 0x00, new byte[]{0x06, 0x08})); // ACK-NAK for CFG-RATE
        assertThrows(IOException.class, () -> gps.pollUbx(0x06, 0x08));
    }

    private static byte[] lastWrite(Neo6ConnectionMock conn) {
        List<byte[]> writes = conn.writes();
        return writes.get(writes.size() - 1);
    }
}
