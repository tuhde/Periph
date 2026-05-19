package it.uhde.periph.transport;

import com.pi4j.context.Context;
import com.pi4j.io.spi.Spi;
import com.pi4j.io.spi.SpiMode;

import java.io.IOException;

/**
 * NeoPixel transport for WS2812B-compatible addressable LEDs, backed by Pi4J SPI.
 *
 * <p>Each NeoPixel bit is encoded as 3 SPI bits at 2.4 MHz (bit-0 → {@code 100},
 * bit-1 → {@code 110}). A 16-byte zero reset is appended after every frame
 * (≈53 µs), satisfying the ≥50 µs latch requirement. This transport is
 * write-only; {@link #read} and {@link #writeRead} throw
 * {@link UnsupportedOperationException}.
 *
 * <p>Connect the WS2812B DIN pin to the SPI MOSI pin. SCK, MISO, and CS are
 * unused by the LED strip.
 */
public final class NeoPixelTransport implements Transport {

    private final Spi spi;

    /**
     * Open an SPI device for NeoPixel output.
     *
     * @param pi4j      active Pi4J context (caller retains ownership)
     * @param busNum    SPI bus number (e.g. 0 for /dev/spidev0.x)
     * @param deviceNum SPI device number / chip select (e.g. 0 for /dev/spidevx.0)
     */
    public NeoPixelTransport(Context pi4j, int busNum, int deviceNum) {
        var config = Spi.newConfigBuilder(pi4j)
                .id("neopixel-spi-" + busNum + "-" + deviceNum)
                .bus(busNum)
                .chipSelect(deviceNum)
                .baud(2_400_000)
                .mode(SpiMode.MODE_0)
                .build();
        this.spi = pi4j.create(config);
    }

    /**
     * Encode {@code data} with the 3-bit SPI scheme and transmit, followed by
     * 16 zero-bytes (≈53 µs reset pulse).
     *
     * @param data raw GRB bytes to send (n pixels × 3 bytes each)
     * @throws IOException on SPI error
     */
    @Override
    public void write(byte[] data) throws IOException {
        try {
            byte[] encoded = encode(data);
            spi.write(encoded, 0, encoded.length);
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    /** Not supported — NeoPixel is write-only. */
    @Override
    public byte[] read(int n) {
        throw new UnsupportedOperationException("NeoPixel is write-only");
    }

    /** Not supported — NeoPixel is write-only. */
    @Override
    public byte[] writeRead(byte[] data, int n) {
        throw new UnsupportedOperationException("NeoPixel is write-only");
    }

    @Override
    public void close() throws IOException {
        try {
            spi.close();
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    // Each input byte → 3 SPI bytes (24 bits); 16 trailing zeros = ≥50 µs reset
    private static byte[] encode(byte[] data) {
        byte[] out = new byte[data.length * 3 + 16];
        for (int i = 0; i < data.length; i++) {
            int b = data[i] & 0xFF;
            int bits = 0;
            for (int bit = 7; bit >= 0; bit--) {
                bits = (bits << 3) | (((b >> bit) & 1) != 0 ? 0b110 : 0b100);
            }
            out[i * 3]     = (byte) ((bits >> 16) & 0xFF);
            out[i * 3 + 1] = (byte) ((bits >>  8) & 0xFF);
            out[i * 3 + 2] = (byte)  (bits        & 0xFF);
        }
        return out;
    }
}
