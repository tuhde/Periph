package it.uhde.periph.chips.gnss;

import it.uhde.periph.transport.Transport;
import it.uhde.periph.transport.UARTTransport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * u-blox NEO-6 GNSS receiver: NMEA position, altitude, and fix status (minimal driver).
 *
 * <p>Reads bytes from the transport and assembles complete NMEA sentences
 * terminated by CR/LF. Works out of the box with the module's factory
 * defaults (NMEA output at 9600 baud, 1 Hz, all standard sentences enabled)
 * -- no chip-side configuration is sent.
 *
 * <p>Supports UART and I2C (DDC) transports; the JVM ecosystem has no SPI
 * transport, so {@link BusType} only offers those two.
 *
 * <p>A stray idle-filler byte (0xFF on I2C when the module has nothing
 * queued) can never start a sentence (NMEA sentences start with '$'); if one
 * lands mid-sentence during a buffer underrun, the resulting sentence simply
 * fails its checksum and is discarded, same as any other corrupted sentence.
 */
public class Neo6Minimal {

    /** Transport kind the driver was constructed with. */
    public enum BusType { UART, I2C }

    protected static final int SENTENCE_START = 0x24; // '$'
    protected static final int CR = 0x0D;
    protected static final int LF = 0x0A;
    protected static final int MAX_SENTENCE = 96;

    protected final Transport transport;
    protected final BusType busType;

    private final byte[] buf = new byte[MAX_SENTENCE];
    private int bufLen = 0;
    private boolean inSentence = false;

    private Double lat;
    private Double lon;
    private Double alt;
    private int fix = 0;
    private int satellites = 0;

    /**
     * Construct the driver over a UART transport.
     *
     * @param transport UART transport
     * @throws IOException never thrown here; declared for symmetry with the I2C constructor
     */
    public Neo6Minimal(Transport transport) throws IOException {
        this(transport, BusType.UART);
    }

    /**
     * Construct the driver.
     *
     * @param transport UART or I2C transport, matching busType
     * @param busType   which transport kind transport is
     * @throws IOException never thrown here; declared for symmetry with Full's UBX methods
     */
    public Neo6Minimal(Transport transport, BusType busType) throws IOException {
        this.transport = transport;
        this.busType = busType;
    }

    /**
     * Fetch one byte if available.
     *
     * @return the byte (0-255), or null if none is ready yet
     * @throws IOException on transport error
     */
    protected Integer readByte() throws IOException {
        if (busType == BusType.UART) {
            UARTTransport uart = (UARTTransport) transport;
            if (uart.available() <= 0) return null;
            byte[] b = transport.read(1);
            return b.length > 0 ? (b[0] & 0xFF) : null;
        }
        // I2C DDC random-read: set the register pointer to 0xFF, then read one
        // stream byte. The pointer saturates at 0xFF once set, so re-sending
        // it on every byte is redundant but harmless.
        byte[] b = transport.writeRead(new byte[]{(byte) 0xFF}, 1);
        return b.length > 0 ? (b[0] & 0xFF) : null;
    }

    /**
     * Read available bytes and parse at most one complete NMEA sentence.
     *
     * @return true if a GGA sentence with a valid fix (fix status &gt; 0) was parsed
     * @throws IOException on transport error
     */
    public boolean update() throws IOException {
        Integer byteVal = readByte();
        if (byteVal == null) return false;
        int b = byteVal;
        if (b == SENTENCE_START) {
            buf[0] = (byte) b;
            bufLen = 1;
            inSentence = true;
            return false;
        }
        if (!inSentence) return false;
        if (bufLen >= MAX_SENTENCE) {
            bufLen = 0;
            inSentence = false;
            return false;
        }
        buf[bufLen++] = (byte) b;
        if (b == LF && bufLen >= 2 && buf[bufLen - 2] == CR) {
            byte[] sentence = Arrays.copyOf(buf, bufLen);
            bufLen = 0;
            inSentence = false;
            return onSentence(sentence);
        }
        return false;
    }

    private boolean onSentence(byte[] sentence) {
        if (!checksumOk(sentence)) return false;
        int star = indexOfStar(sentence);
        if (star < 1) return false;
        String body = new String(sentence, 1, star - 1, StandardCharsets.US_ASCII);
        String[] fields = body.split(",", -1);
        if (fields[0].length() < 5) return false;
        String sentenceId = fields[0].substring(2, 5);
        boolean result = false;
        if (sentenceId.equals("GGA")) {
            result = parseGga(fields);
        }
        handleExtra(sentenceId, fields);
        return result;
    }

    private boolean parseGga(String[] fields) {
        if (fields.length < 15) return false;
        int f = parseIntOrZero(fields[6]);
        fix = f;
        satellites = parseIntOrZero(fields[7]);
        if (f > 0 && !fields[2].isEmpty() && !fields[3].isEmpty()
                && !fields[4].isEmpty() && !fields[5].isEmpty()) {
            try {
                lat = nmeaToDegrees(fields[2], fields[3]);
                lon = nmeaToDegrees(fields[4], fields[5]);
                alt = fields[9].isEmpty() ? null : Double.parseDouble(fields[9]);
            } catch (NumberFormatException ignored) {
                // leave previous values in place
            }
        }
        return f > 0;
    }

    /**
     * Hook for {@link Neo6Full} to parse additional sentence types. No-op here.
     *
     * @param sentenceId three-letter NMEA sentence id (e.g. "GGA", "RMC")
     * @param fields     comma-split sentence fields, including fields[0] (talker+id)
     */
    protected void handleExtra(String sentenceId, String[] fields) {
        // no-op in Minimal
    }

    /** @return latitude of the last valid fix, decimal degrees positive north, or null until the first fix */
    public Double latitude() { return lat; }

    /** @return longitude of the last valid fix, decimal degrees positive east, or null until the first fix */
    public Double longitude() { return lon; }

    /** @return height above mean sea level of the last valid fix in meters, or null until the first fix */
    public Double altitude() { return alt; }

    /** @return GGA fix quality of the last parsed GGA sentence: 0=no fix, 1=GPS, 2=DGPS */
    public int fix() { return fix; }

    /** @return number of satellites used in the last GGA fix (GGA field 7) */
    public int satellites() { return satellites; }

    // --- Shared helpers (package-visible for Neo6Full) ---

    static boolean checksumOk(byte[] sentence) {
        int star = indexOfStar(sentence);
        if (star < 1 || star + 4 > sentence.length) return false;
        int checksum = 0;
        for (int i = 1; i < star; i++) checksum ^= (sentence[i] & 0xFF);
        try {
            int expected = Integer.parseInt(
                    new String(sentence, star + 1, 2, StandardCharsets.US_ASCII), 16);
            return checksum == expected;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static int indexOfStar(byte[] sentence) {
        for (int i = 0; i < sentence.length; i++) {
            if (sentence[i] == '*') return i;
        }
        return -1;
    }

    static double nmeaToDegrees(String raw, String hemisphere) {
        double value = Double.parseDouble(raw);
        int deg = (int) (value / 100);
        double minutes = value - deg * 100;
        double decimal = deg + minutes / 60.0;
        if (hemisphere.equals("S") || hemisphere.equals("W")) decimal = -decimal;
        return decimal;
    }

    static int parseIntOrZero(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 8-bit Fletcher checksum over class, id, length, and payload bytes.
     *
     * @param data bytes to checksum
     * @return two-element array {@code {ckA, ckB}}
     */
    static int[] ubxChecksum(byte[] data) {
        int ckA = 0, ckB = 0;
        for (byte value : data) {
            ckA = (ckA + (value & 0xFF)) & 0xFF;
            ckB = (ckB + ckA) & 0xFF;
        }
        return new int[]{ckA, ckB};
    }
}
