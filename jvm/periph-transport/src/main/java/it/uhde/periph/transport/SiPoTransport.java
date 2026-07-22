package it.uhde.periph.transport;

import java.io.IOException;
import java.lang.foreign.*;
import java.lang.invoke.*;

/**
 * SiPo (serial-in/parallel-out shift register) transport for cascadable SIPO
 * shift registers (TPIC6B595, SN74HC595, etc.), using Linux spidev and the
 * GPIO character device via FFM (no native libraries required).
 *
 * <p>SER IN/SRCK are electrically an SPI MOSI/SCK pair. This transport shifts
 * data over either a hardware {@code /dev/spidevB.D} device ({@link
 * #hardware}) or a bit-banged pair of {@code /dev/gpiochip0} lines ({@link
 * #software}) — construct via whichever static factory matches the wiring.
 * RCK — and, if configured, SRCLR/G — are always plain GPIO character-device
 * lines, driven the same way as the DE pin in {@link UARTTransport}, regardless
 * of which SPI mode is used.
 *
 * <p>Write-only: {@link #read} and {@link #writeRead} throw {@link
 * UnsupportedOperationException}.
 *
 * <p>Requires {@code --enable-native-access=ALL-UNNAMED} (Java 21+).
 */
public final class SiPoTransport implements Transport {

    private static final int O_RDWR = 2;

    // SPI ioctl request codes for ARM Linux (from linux/spi/spidev.h)
    private static final long SPI_IOC_WR_MODE          = 0x40016b01L;
    private static final long SPI_IOC_WR_MAX_SPEED_HZ  = 0x40046b04L;
    // _IOW('k', 0, char[sizeof(spi_ioc_transfer)=32])
    private static final long SPI_IOC_MESSAGE_1        = 0x40206b00L;
    private static final int  SPI_IOC_XFER_SIZE        = 32;
    private static final int  SPI_SPEED_HZ             = 1_000_000;

    // GPIO character device ioctls (_IOWR(0xB4, ...)) — same layout as UARTTransport's DE pin.
    private static final long GPIO_GET_LINEHANDLE_IOCTL        = 0xC16CB403L;
    private static final long GPIOHANDLE_SET_LINE_VALUES_IOCTL = 0xC040B409L;
    private static final int  GPIOHANDLE_REQUEST_OUTPUT        = 2;
    // struct gpiohandle_request layout (364 bytes total):
    //   lineoffsets[64] u32 @ 0, flags u32 @ 256, default_values[64] u8 @ 260,
    //   consumer_label[32] char @ 324, lines u32 @ 356, fd int @ 360
    private static final int GR_SIZE     = 364;
    private static final int GR_OFFSETS  = 0;
    private static final int GR_FLAGS    = 256;
    private static final int GR_DEFAULTS = 260;
    private static final int GR_LINES    = 356;
    private static final int GR_FD       = 360;
    // struct gpiohandle_data: values[64] u8 (64 bytes)
    private static final int GD_SIZE     = 64;

    private static final MethodHandle openMH;
    private static final MethodHandle ioctlAddrMH;
    private static final MethodHandle closeMH;

    static {
        var linker = Linker.nativeLinker();
        var lookup  = linker.defaultLookup();
        openMH = linker.downcallHandle(
            lookup.find("open").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        ioctlAddrMH = linker.downcallHandle(
            lookup.find("ioctl").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
        closeMH = linker.downcallHandle(
            lookup.find("close").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    }

    private final int spiFd;    // -1 in software mode
    private final int serInFd;  // -1 in hardware mode
    private final int srckFd;   // -1 in hardware mode
    private final int rckFd;
    private final int srclrFd;  // -1 if not configured
    private final int gFd;      // -1 if not configured

    private SiPoTransport(int spiFd, int serInFd, int srckFd,
                          int rckFd, int srclrFd, int gFd) throws IOException {
        this.spiFd = spiFd;
        this.serInFd = serInFd;
        this.srckFd = srckFd;
        this.rckFd = rckFd;
        this.srclrFd = srclrFd;
        this.gFd = gFd;

        gpioSet(rckFd, 0);
        if (srclrFd >= 0) gpioSet(srclrFd, 1);
        if (gFd >= 0) gpioSet(gFd, 0);
    }

    /**
     * Create a hardware-SPI transport: SER IN/SRCK are the spidev MOSI/SCK pins.
     *
     * @param busNumber    SPI bus number (opens {@code /dev/spidevB.D})
     * @param deviceNumber SPI chip-select line on the bus
     * @param rckLine      {@code /dev/gpiochip0} line number for RCK (register clock)
     * @param srclrLine    {@code /dev/gpiochip0} line number for SRCLR; -1 disables it
     * @param gLine        {@code /dev/gpiochip0} line number for G (output enable); -1 disables it
     * @return a new hardware-mode transport
     * @throws IOException if the spidev device or any GPIO line cannot be opened
     */
    public static SiPoTransport hardware(int busNumber, int deviceNumber,
                                         int rckLine, int srclrLine, int gLine) throws IOException {
        int spiFd = openSpiDevice(busNumber, deviceNumber);
        try {
            int rckFd = openGpioLine(rckLine, 0);
            int srclrFd = srclrLine >= 0 ? openGpioLine(srclrLine, 1) : -1;
            int gFd = gLine >= 0 ? openGpioLine(gLine, 0) : -1;
            return new SiPoTransport(spiFd, -1, -1, rckFd, srclrFd, gFd);
        } catch (IOException e) {
            try { closeMH.invoke(spiFd); } catch (Throwable ignored) {}
            throw e;
        }
    }

    /**
     * Create a software (bit-banged) transport: SER IN/SRCK are plain
     * {@code /dev/gpiochip0} lines instead of a spidev device.
     *
     * @param serInLine {@code /dev/gpiochip0} line number for SER IN (serial data)
     * @param srckLine  {@code /dev/gpiochip0} line number for SRCK (shift register clock)
     * @param rckLine   {@code /dev/gpiochip0} line number for RCK (register clock)
     * @param srclrLine {@code /dev/gpiochip0} line number for SRCLR; -1 disables it
     * @param gLine     {@code /dev/gpiochip0} line number for G (output enable); -1 disables it
     * @return a new software-mode transport
     * @throws IOException if any GPIO line cannot be opened
     */
    public static SiPoTransport software(int serInLine, int srckLine,
                                         int rckLine, int srclrLine, int gLine) throws IOException {
        int serInFd = openGpioLine(serInLine, 0);
        int srckFd = openGpioLine(srckLine, 0);
        int rckFd = openGpioLine(rckLine, 0);
        int srclrFd = srclrLine >= 0 ? openGpioLine(srclrLine, 1) : -1;
        int gFd = gLine >= 0 ? openGpioLine(gLine, 0) : -1;
        return new SiPoTransport(-1, serInFd, srckFd, rckFd, srclrFd, gFd);
    }

    private static int openSpiDevice(int busNumber, int deviceNumber) throws IOException {
        int fd = -1;
        try (var arena = Arena.ofConfined()) {
            var path = arena.allocateFrom("/dev/spidev" + busNumber + "." + deviceNumber);
            fd = (int) openMH.invoke(path, O_RDWR);
            if (fd < 0) throw new IOException(
                "open(/dev/spidev" + busNumber + "." + deviceNumber + ") failed");

            var u8  = arena.allocate(ValueLayout.JAVA_BYTE);
            var u32 = arena.allocate(ValueLayout.JAVA_INT);

            u8.set(ValueLayout.JAVA_BYTE, 0, (byte) 0); // SPI_MODE_0
            int rc = (int) ioctlAddrMH.invoke(fd, SPI_IOC_WR_MODE, u8);
            if (rc < 0) throw new IOException("SPI_IOC_WR_MODE ioctl failed: " + rc);

            u32.set(ValueLayout.JAVA_INT, 0, SPI_SPEED_HZ);
            rc = (int) ioctlAddrMH.invoke(fd, SPI_IOC_WR_MAX_SPEED_HZ, u32);
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

    /** Open {@code /dev/gpiochip0} and request {@code lineNum} as an output line at {@code initialValue}. */
    private static int openGpioLine(int lineNum, int initialValue) throws IOException {
        try (var arena = Arena.ofConfined()) {
            var chipPath = arena.allocateFrom("/dev/gpiochip0");
            int chipFd = (int) openMH.invoke(chipPath, O_RDWR);
            if (chipFd < 0) throw new IOException("open(/dev/gpiochip0) failed");
            try {
                var req = arena.allocate(GR_SIZE, 4);
                req.fill((byte) 0);
                req.set(ValueLayout.JAVA_INT, GR_OFFSETS, lineNum);
                req.set(ValueLayout.JAVA_INT, GR_FLAGS, GPIOHANDLE_REQUEST_OUTPUT);
                req.set(ValueLayout.JAVA_BYTE, GR_DEFAULTS, (byte) initialValue);
                req.set(ValueLayout.JAVA_INT, GR_LINES, 1);
                int rc = (int) ioctlAddrMH.invoke(chipFd, GPIO_GET_LINEHANDLE_IOCTL, req);
                if (rc < 0) throw new IOException("GPIO_GET_LINEHANDLE_IOCTL failed for line " + lineNum);
                return req.get(ValueLayout.JAVA_INT, GR_FD);
            } finally {
                closeMH.invoke(chipFd);
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException(t);
        }
    }

    private static void gpioSet(int lineFd, int value) throws IOException {
        try (var arena = Arena.ofConfined()) {
            var data = arena.allocate(GD_SIZE, 1);
            data.fill((byte) 0);
            data.set(ValueLayout.JAVA_BYTE, 0, (byte) value);
            int rc = (int) ioctlAddrMH.invoke(lineFd, GPIOHANDLE_SET_LINE_VALUES_IOCTL, data);
            if (rc < 0) throw new IOException("GPIOHANDLE_SET_LINE_VALUES_IOCTL failed");
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException(t);
        }
    }

    /**
     * Shift {@code data} out MSB-first, then latch it into the output register.
     *
     * <p>In hardware mode this transfers {@code data} over spidev in a single
     * {@code SPI_IOC_MESSAGE} transfer; in software mode it bit-bangs SER
     * IN/SRCK. Either way, RCK is then pulsed HIGH then LOW to latch the
     * shifted data into the storage register that drives the outputs.
     *
     * @param data bytes to shift out, one byte per cascaded device
     * @throws IOException on SPI or GPIO error
     */
    @Override
    public void write(byte[] data) throws IOException {
        if (spiFd >= 0) {
            writeSpi(data);
        } else {
            for (byte b : data) {
                for (int bit = 7; bit >= 0; bit--) {
                    gpioSet(serInFd, (b >> bit) & 1);
                    gpioSet(srckFd, 1);
                    gpioSet(srckFd, 0);
                }
            }
        }
        gpioSet(rckFd, 1);
        gpioSet(rckFd, 0);
    }

    private void writeSpi(byte[] data) throws IOException {
        try (var arena = Arena.ofConfined()) {
            var txBuf = arena.allocate(data.length);
            txBuf.copyFrom(MemorySegment.ofArray(data));

            var xfer = arena.allocate(SPI_IOC_XFER_SIZE);
            xfer.set(ValueLayout.JAVA_LONG, 0, txBuf.address()); // tx_buf
            xfer.set(ValueLayout.JAVA_LONG, 8, 0L);              // rx_buf
            xfer.set(ValueLayout.JAVA_INT, 16, data.length);     // len
            xfer.set(ValueLayout.JAVA_INT, 20, SPI_SPEED_HZ);    // speed_hz

            int rc = (int) ioctlAddrMH.invoke(spiFd, SPI_IOC_MESSAGE_1, xfer);
            if (rc < 0) throw new IOException("SPI_IOC_MESSAGE ioctl failed: " + rc);
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException(t);
        }
    }

    /**
     * Pulse SRCLR LOW then HIGH to clear the shift register.
     *
     * <p>The storage register (and therefore the outputs) is unaffected until
     * the next {@link #write}.
     *
     * @throws IllegalStateException if SRCLR was not configured
     * @throws IOException on GPIO error
     */
    public void clear() throws IOException {
        if (srclrFd < 0) throw new IllegalStateException("SRCLR not configured");
        gpioSet(srclrFd, 0);
        gpioSet(srclrFd, 1);
    }

    /**
     * Drive G LOW ({@code enabled = true}) or HIGH ({@code enabled = false}).
     *
     * @param enabled {@code true} drives G LOW, letting the storage register
     *                drive the outputs. {@code false} drives G HIGH, forcing
     *                every output off without disturbing the storage
     *                register's contents.
     * @throws IllegalStateException if G was not configured
     * @throws IOException on GPIO error
     */
    public void setOutputEnable(boolean enabled) throws IOException {
        if (gFd < 0) throw new IllegalStateException("G not configured");
        gpioSet(gFd, enabled ? 0 : 1);
    }

    /** Not supported — SiPo is write-only. */
    @Override
    public byte[] read(int n) {
        throw new UnsupportedOperationException("SiPo is write-only");
    }

    /** Not supported — SiPo is write-only. */
    @Override
    public byte[] writeRead(byte[] data, int n) {
        throw new UnsupportedOperationException("SiPo is write-only");
    }

    /**
     * Release the spidev device (if opened) and all configured GPIO line handles.
     *
     * @throws IOException if any underlying {@code close()} call fails
     */
    @Override
    public void close() throws IOException {
        try {
            if (spiFd >= 0) closeMH.invoke(spiFd);
            if (serInFd >= 0) closeMH.invoke(serInFd);
            if (srckFd >= 0) closeMH.invoke(srckFd);
            closeMH.invoke(rckFd);
            if (srclrFd >= 0) closeMH.invoke(srclrFd);
            if (gFd >= 0) closeMH.invoke(gFd);
        } catch (Throwable t) {
            throw new IOException(t);
        }
    }
}
