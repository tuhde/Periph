package it.uhde.periph.transport;

import java.io.IOException;
import java.lang.foreign.*;
import java.lang.invoke.*;

/**
 * NeoPixel transport for WS2812B-compatible addressable LEDs, using Linux spidev via FFM.
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

    private static final int O_RDWR = 2;

    // ioctl request codes for ARM Linux (from linux/spi/spidev.h)
    private static final long SPI_IOC_WR_MODE          = 0x40016b01L;
    private static final long SPI_IOC_WR_BITS_PER_WORD = 0x40016b03L;
    private static final long SPI_IOC_WR_MAX_SPEED_HZ  = 0x40046b04L;

    private static final MethodHandle openMH;
    private static final MethodHandle ioctlPtrMH;
    private static final MethodHandle writeMH;
    private static final MethodHandle closeMH;

    static {
        var linker = Linker.nativeLinker();
        var lookup  = linker.defaultLookup();
        openMH = linker.downcallHandle(
            lookup.find("open").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        ioctlPtrMH = linker.downcallHandle(
            lookup.find("ioctl").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
        writeMH = linker.downcallHandle(
            lookup.find("write").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        closeMH = linker.downcallHandle(
            lookup.find("close").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    }

    private final int fd;

    /**
     * Open an SPI device for NeoPixel output.
     *
     * @param busNum    SPI bus number (e.g. 1 for /dev/spidev1.x)
     * @param deviceNum SPI device number / chip select (e.g. 0 for /dev/spidevx.0)
     * @throws IOException if the device cannot be opened or configured
     */
    public NeoPixelTransport(int busNum, int deviceNum) throws IOException {
        this.fd = openDevice(busNum, deviceNum);
    }

    private static int openDevice(int busNum, int deviceNum) throws IOException {
        int fd = -1;
        try (var arena = Arena.ofConfined()) {
            var path = arena.allocateFrom("/dev/spidev" + busNum + "." + deviceNum);
            fd = (int) openMH.invoke(path, O_RDWR);
            if (fd < 0) throw new IOException(
                "open(/dev/spidev" + busNum + "." + deviceNum + ") failed");

            var u8  = arena.allocate(ValueLayout.JAVA_BYTE);
            var u32 = arena.allocate(ValueLayout.JAVA_INT);

            u8.set(ValueLayout.JAVA_BYTE, 0, (byte) 0); // SPI_MODE_0
            int rc = (int) ioctlPtrMH.invoke(fd, SPI_IOC_WR_MODE, u8);
            if (rc < 0) throw new IOException("SPI_IOC_WR_MODE ioctl failed: " + rc);

            u8.set(ValueLayout.JAVA_BYTE, 0, (byte) 8); // 8 bits per word
            rc = (int) ioctlPtrMH.invoke(fd, SPI_IOC_WR_BITS_PER_WORD, u8);
            if (rc < 0) throw new IOException("SPI_IOC_WR_BITS_PER_WORD ioctl failed: " + rc);

            u32.set(ValueLayout.JAVA_INT, 0, 2_400_000); // 2.4 MHz
            rc = (int) ioctlPtrMH.invoke(fd, SPI_IOC_WR_MAX_SPEED_HZ, u32);
            if (rc < 0) throw new IOException("SPI_IOC_WR_MAX_SPEED_HZ ioctl failed: " + rc);

            return fd;
        } catch (IOException e) {
            if (fd >= 0) { try { closeMH.invoke(fd); } catch (Throwable ignored) {} }
            throw e;
        } catch (Throwable t) {
            if (fd >= 0) { try { closeMH.invoke(fd); } catch (Throwable ignored) {} }
            throw new IOException(t);
        }
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
        byte[] encoded = encode(data);
        try (var arena = Arena.ofConfined()) {
            var buf = arena.allocate(encoded.length);
            buf.copyFrom(MemorySegment.ofArray(encoded));
            long n = (long) writeMH.invoke(fd, buf, (long) encoded.length);
            if (n < 0) throw new IOException("write() failed: " + n);
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException(t);
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
            closeMH.invoke(fd);
        } catch (Throwable t) {
            throw new IOException(t);
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
