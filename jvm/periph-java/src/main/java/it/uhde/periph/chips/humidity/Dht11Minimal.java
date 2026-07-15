package it.uhde.periph.chips.humidity;

import it.uhde.periph.transport.DHTxxTransport;

import java.io.IOException;

/**
 * DHT11 — combined temperature and humidity sensor (minimal driver).
 *
 * <p>Reads a 40-bit frame (humidity integer + decimal, temperature integer +
 * decimal, checksum) over the DHTxx single-wire transport. The driver is
 * responsible for checksum validation and data conversion; the transport
 * handles all GPIO direction switching, timing, and bit decoding.
 *
 * <p>Default configuration (baked in at construction):
 * <ul>
 *   <li>Single read attempt; throws on checksum mismatch.</li>
 *   <li>Caller responsible for respecting the ≥ 2 s sampling interval.</li>
 * </ul>
 */
public class Dht11Minimal {

    /** Raised on checksum error or invalid frame length. */
    public static class Dht11Exception extends IOException {
        public Dht11Exception(String detail) { super(detail); }
    }

    protected final DHTxxTransport transport;

    /**
     * Construct the driver.
     *
     * @param transport Configured DHTxx transport bound to the chip's DATA pin.
     */
    public Dht11Minimal(DHTxxTransport transport) {
        this.transport = transport;
    }

    /**
     * Decode a 5-byte frame into a {@code (temperature, humidity)} pair.
     *
     * @param frame 5-byte raw frame.
     * @return 2-element array: [temperature_C, humidity_RH].
     * @throws Dht11Exception on checksum mismatch or invalid length.
     */
    protected double[] decode(byte[] frame) throws Dht11Exception {
        if (frame == null || frame.length != 5) {
            throw new Dht11Exception("frame must be 5 bytes, got " + (frame == null ? "null" : frame.length));
        }
        int humInt  = frame[0] & 0xFF;
        int humDec  = frame[1] & 0xFF;
        int tempInt = frame[2] & 0xFF;
        int tempDec = frame[3] & 0xFF;
        int checksum = frame[4] & 0xFF;
        int expected = (humInt + humDec + tempInt + tempDec) & 0xFF;
        if (expected != checksum) {
            throw new Dht11Exception(String.format("checksum mismatch: expected 0x%02X, got 0x%02X", expected, checksum));
        }
        double humidity = humInt + humDec / 10.0;
        int sign = (tempDec & 0x80) != 0 ? -1 : 1;
        int tempDecValue = tempDec & 0x7F;
        double temperature = sign * (tempInt + tempDecValue / 10.0);
        return new double[]{temperature, humidity};
    }

    /**
     * Read both temperature and humidity in a single transaction.
     *
     * @return 2-element array: [temperature_C, humidity_RH].
     * @throws IOException    on transport error.
     * @throws Dht11Exception on checksum mismatch.
     */
    public double[] read() throws IOException, Dht11Exception {
        byte[] frame = transport.read();
        return decode(frame);
    }

    /** @return temperature in degrees Celsius. */
    public double readTemperature() throws IOException, Dht11Exception {
        return read()[0];
    }

    /** @return humidity in %RH. */
    public double readHumidity() throws IOException, Dht11Exception {
        return read()[1];
    }
}
