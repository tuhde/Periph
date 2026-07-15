package it.uhde.periph.transport;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.foreign.Linker;

/**
 * DHTxx single-wire transport for JVM (Linux host, GPIO v1 character device
 * via FFM, no native libraries required).
 *
 * <p>Implements the host side of the DHT11 / DHT22 single-wire protocol: a
 * bidirectional DATA line, externally pulled up to VCC via a 4.7 kΩ resistor.
 * Direction switching requires releasing and re-requesting the line via the
 * kernel's v1 character device {@code GPIO_GET_LINEHANDLE_IOCTL}.
 *
 * <p>µs-level timing on a non-RTOS kernel is inherently imprecise under load.
 * Read failures are expected on a busy system; callers should use the chip
 * driver's retry mechanism rather than relying on single-shot reads.
 *
 * <p>Optional two-pin open-drain variant: pass a non-negative {@code lineOffsetOut}
 * to request a second line wired to the same physical DATA net, requested
 * once with {@code GPIOHANDLE_REQUEST_OPEN_DRAIN}. The original line stays
 * as input for the lifetime of the transport. This avoids the
 * release/re-request entirely.
 *
 * <p>Requires {@code --enable-native-access=ALL-UNNAMED} (Java 21+).
 */
public class DHTxxTransport implements AutoCloseable {

    /** Raised when the DHTxx transport cannot complete a read. */
    public static class DHTxxTransportException extends IOException {
        public enum Kind { TIMEOUT, FRAMING }
        public final Kind kind;
        public DHTxxTransportException(Kind kind, String detail) {
            super(kind + (detail != null ? ": " + detail : ""));
            this.kind = kind;
        }
    }

    private static final int  O_RDWR                       = 2;

    // GPIO character device ioctls (v1, deprecated but still present in
    // modern kernels and the simplest GPIO interface for bare FFM).
    private static final long GPIO_GET_LINEHANDLE_IOCTL    = 0xC16CB403L;
    private static final long GPIOHANDLE_GET_LINE_VALUES_IOCTL = 0xC040B408L;
    private static final long GPIOHANDLE_SET_LINE_VALUES_IOCTL = 0xC040B409L;

    private static final int  GPIOHANDLE_REQUEST_INPUT     = 1 << 0;
    private static final int  GPIOHANDLE_REQUEST_OUTPUT    = 1 << 1;
    private static final int  GPIOHANDLE_REQUEST_OPEN_DRAIN = 1 << 3;
    private static final int  GPIOHANDLE_REQUEST_BIAS_PULL_UP = 1 << 5;

    // struct gpiohandle_request layout (364 bytes):
    //   lineoffsets[64] u32 @ 0, flags u32 @ 256, default_values[64] u8 @ 260,
    //   consumer_label[32] char @ 324, lines u32 @ 356, fd int @ 360
    private static final int GR_SIZE     = 364;
    private static final int GR_OFFSETS  = 0;
    private static final int GR_FLAGS    = 256;
    private static final int GR_DEFAULTS = 260;
    private static final int GR_LINES    = 356;
    private static final int GR_FD       = 360;

    // struct gpiohandle_data: values[64] u8 (64 bytes)
    private static final int GD_SIZE = 64;

    private static final int START_LOW_MS         = 20;
    private static final int RESPONSE_TIMEOUT_US  = 200;
    private static final int BIT_TIMEOUT_US       = 200;
    private static final int BIT_THRESHOLD_US     = 40;

    private static final MethodHandle openMH;
    private static final MethodHandle ioctlAddrMH;
    private static final MethodHandle closeMH;

    static {
        Linker linker = Linker.nativeLinker();
        var lookup = linker.defaultLookup();
        openMH = linker.downcallHandle(
            lookup.find("open").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        ioctlAddrMH = linker.downcallHandle(
            lookup.find("ioctl").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
        closeMH = linker.downcallHandle(
            lookup.find("close").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    }

    private final String chipPath;
    private final int    lineOffset;
    private final int    lineOffsetOut;
    private int    chipFd      = -1;
    private int    inputFd     = -1;
    private int    outputFd    = -1;
    private int    outputOutFd = -1;
    private boolean twoPin;
    private boolean closed;

    /**
     * @param chipPath   Path to the gpiochip device (e.g. {@code /dev/gpiochip0}).
     * @param lineOffset GPIO line offset for the DATA line.
     */
    public DHTxxTransport(String chipPath, int lineOffset) {
        this(chipPath, lineOffset, -1);
    }

    /**
     * @param chipPath      Path to the gpiochip device.
     * @param lineOffset    GPIO line offset for the DATA line (input).
     * @param lineOffsetOut Optional second line offset (open-drain output) for
     *                      the two-pin variant, or -1 for the single-pin variant.
     */
    public DHTxxTransport(String chipPath, int lineOffset, int lineOffsetOut) {
        this.chipPath = chipPath;
        this.lineOffset = lineOffset;
        this.lineOffsetOut = lineOffsetOut;
        this.twoPin = lineOffsetOut >= 0;
        try {
            open();
        } catch (IOException e) {
            throw new RuntimeException("DHTxxTransport open failed: " + e.getMessage(), e);
        }
    }

    private void open() throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pathSeg = arena.allocateFrom(chipPath);
            chipFd = (int) openMH.invoke(pathSeg, O_RDWR);
            if (chipFd < 0) {
                throw new IOException("open(" + chipPath + ") failed");
            }
        } catch (Throwable t) {
            throw new IOException(t);
        }
        try {
            inputFd = requestHandle(lineOffset, GPIOHANDLE_REQUEST_INPUT | GPIOHANDLE_REQUEST_BIAS_PULL_UP);
        } catch (IOException e) {
            closeAll();
            throw e;
        }
        if (twoPin) {
            try {
                outputOutFd = requestHandle(lineOffsetOut,
                        GPIOHANDLE_REQUEST_OUTPUT | GPIOHANDLE_REQUEST_OPEN_DRAIN);
            } catch (IOException e) {
                closeAll();
                throw e;
            }
        }
    }

    private int requestHandle(int lineOffset, int flags) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment req = arena.allocate(GR_SIZE, 4);
            req.fill((byte) 0);
            req.set(ValueLayout.JAVA_INT, GR_OFFSETS, lineOffset);
            req.set(ValueLayout.JAVA_INT, GR_FLAGS, flags);
            req.set(ValueLayout.JAVA_INT, GR_LINES, 1);
            int rc = (int) ioctlAddrMH.invoke(chipFd, GPIO_GET_LINEHANDLE_IOCTL, req);
            if (rc < 0) {
                throw new IOException("GPIO_GET_LINEHANDLE_IOCTL failed for line " + lineOffset);
            }
            return req.get(ValueLayout.JAVA_INT, GR_FD);
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException(t);
        }
    }

    private int readInputValue() throws IOException {
        if (inputFd < 0) throw new IOException("input line not open");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = arena.allocate(GD_SIZE, 1);
            data.fill((byte) 0);
            int rc = (int) ioctlAddrMH.invoke(inputFd, GPIOHANDLE_GET_LINE_VALUES_IOCTL, data);
            if (rc < 0) throw new IOException("GPIOHANDLE_GET_LINE_VALUES_IOCTL failed");
            return data.get(ValueLayout.JAVA_BYTE, 0) & 0xFF;
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException(t);
        }
    }

    private void setOutputValue(int fd, int value) throws IOException {
        if (fd < 0) throw new IOException("output line not open");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = arena.allocate(GD_SIZE, 1);
            data.fill((byte) 0);
            data.set(ValueLayout.JAVA_BYTE, 0, (byte) value);
            int rc = (int) ioctlAddrMH.invoke(fd, GPIOHANDLE_SET_LINE_VALUES_IOCTL, data);
            if (rc < 0) throw new IOException("GPIOHANDLE_SET_LINE_VALUES_IOCTL failed");
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException(t);
        }
    }

    private void driveLow() throws IOException {
        if (twoPin) {
            setOutputValue(outputOutFd, 1);
        } else {
            // Single-pin: release input handle, request as output.
            if (inputFd >= 0) {
                try { closeMH.invoke(inputFd); } catch (Throwable ignored) {}
                inputFd = -1;
            }
            if (outputFd < 0) {
                outputFd = requestHandle(lineOffset, GPIOHANDLE_REQUEST_OUTPUT);
            }
            setOutputValue(outputFd, 1);
        }
    }

    private void releaseBus() throws IOException {
        if (twoPin) {
            setOutputValue(outputOutFd, 0);
        } else {
            if (outputFd >= 0) {
                try { closeMH.invoke(outputFd); } catch (Throwable ignored) {}
                outputFd = -1;
            }
            if (inputFd < 0) {
                inputFd = requestHandle(lineOffset,
                        GPIOHANDLE_REQUEST_INPUT | GPIOHANDLE_REQUEST_BIAS_PULL_UP);
            }
        }
    }

    private long measurePulse(boolean level, int timeoutUs) throws IOException {
        long deadline = System.nanoTime() + (long) timeoutUs * 1000L;
        int value;
        do {
            value = readInputValue();
            if (System.nanoTime() >= deadline) return -1;
        } while ((value != 0) != level);
        long pulseStart = System.nanoTime();
        do {
            value = readInputValue();
            if (System.nanoTime() >= deadline) return -1;
        } while ((value != 0) == level);
        return (System.nanoTime() - pulseStart) / 1000L;
    }

    /**
     * Execute the full DHTxx transaction and return the raw 5-byte frame.
     *
     * @return 5 bytes — [hum_int, hum_dec, temp_int, temp_dec, checksum].
     * @throws DHTxxTransportException On timeout or framing error.
     */
    public byte[] read() throws IOException {
        driveLow();
        Thread.sleep(START_LOW_MS);
        releaseBus();
        long elapsed = measurePulse(false, RESPONSE_TIMEOUT_US);
        if (elapsed < 0) {
            throw new DHTxxTransportException(DHTxxTransportException.Kind.TIMEOUT,
                    "sensor did not pull DATA low within " + RESPONSE_TIMEOUT_US + " us");
        }
        elapsed = measurePulse(true, RESPONSE_TIMEOUT_US);
        if (elapsed < 0) {
            throw new DHTxxTransportException(DHTxxTransportException.Kind.TIMEOUT,
                    "sensor did not release after response low");
        }

        byte[] frame = new byte[5];
        for (int byteIdx = 0; byteIdx < 5; byteIdx++) {
            int value = 0;
            for (int bitIdx = 0; bitIdx < 8; bitIdx++) {
                elapsed = measurePulse(false, BIT_TIMEOUT_US);
                if (elapsed < 0) {
                    throw new DHTxxTransportException(DHTxxTransportException.Kind.FRAMING,
                            "bit " + (byteIdx * 8 + bitIdx) + " start-low missing");
                }
                elapsed = measurePulse(true, BIT_TIMEOUT_US);
                if (elapsed < 0) {
                    throw new DHTxxTransportException(DHTxxTransportException.Kind.FRAMING,
                            "bit " + (byteIdx * 8 + bitIdx) + " high-pulse missing");
                }
                value = (value << 1) | ((elapsed > BIT_THRESHOLD_US) ? 1 : 0);
            }
            frame[byteIdx] = (byte) value;
        }
        return frame;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        closeAll();
    }

    private void closeAll() {
        if (inputFd     >= 0) { try { closeMH.invoke(inputFd);     } catch (Throwable ignored) {} inputFd     = -1; }
        if (outputFd    >= 0) { try { closeMH.invoke(outputFd);    } catch (Throwable ignored) {} outputFd    = -1; }
        if (outputOutFd >= 0) { try { closeMH.invoke(outputOutFd); } catch (Throwable ignored) {} outputOutFd = -1; }
        if (chipFd      >= 0) { try { closeMH.invoke(chipFd);      } catch (Throwable ignored) {} chipFd      = -1; }
    }
}
