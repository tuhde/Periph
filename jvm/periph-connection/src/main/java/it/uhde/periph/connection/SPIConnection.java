package it.uhde.periph.connection;

import java.io.IOException;
import java.lang.foreign.*;
import java.lang.invoke.*;

/**
 * SPI connection for Linux host (wraps spidev, opens /dev/spidevBUS.DEVICE).
 *
 * <p>Open the SPI device at construction; pass bus and device numbers plus
 * optional mode (0–3) and clock frequency in Hz. CS is managed by the kernel
 * spidev driver — the caller never touches it.
 *
 * <p>Implements the {@link Connection} contract used by every chip driver:
 * {@link #write(byte[])} sends bytes, {@link #read(int)} reads bytes, and
 * {@link #writeRead(byte[], int)} does a full-duplex write-then-read with CS
 * held for the entire operation. The write-read method uses
 * {@code SPI_IOC_MESSAGE(2)} so the command phase and the read phase share a
 * single CS assertion (no inter-phase release).
 *
 * <p>Close the device with {@link #close()} when no longer needed.
 *
 * <p>Backed by raw {@code ioctl()}/{@code read()}/{@code write()} syscalls via
 * the Java 22+ Foreign Function & Memory API — no JNI, no native libraries.
 */
public final class SPIConnection implements Connection {

    private static final int O_RDWR = 2;

    private static final long SPI_IOC_WR_MODE         = 0x40016b01L;
    private static final long SPI_IOC_MESSAGE_1       = 0x40206b00L;
    private static final long SPI_IOC_MESSAGE_2       = 0x40206b02L;
    private static final int  SPI_IOC_XFER_SIZE       = 32;

    private static final MethodHandle openMH;
    private static final MethodHandle ioctlPtrMH;
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
        closeMH = linker.downcallHandle(
            lookup.find("close").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    }

    private final int _fd;
    private final int _mode;
    private final int _speedHz;

    /**
     * Open an SPI device.
     *
     * @param busNum       SPI bus number (e.g. 0 for /dev/spidev0.x).
     * @param deviceNum    Chip-select line on the bus (e.g. 0 for /dev/spidevx.0).
     * @param mode         SPI mode 0–3 (CPOL/CPHA); default 0.
     * @param maxSpeedHz   Clock frequency in Hz; default 1 000 000.
     * @throws IOException if the device cannot be opened or configured.
     */
    public SPIConnection(int busNum, int deviceNum, int mode, int maxSpeedHz) throws IOException {
        if (mode < 0 || mode > 3) {
            throw new IOException("SPI mode must be 0..3 (got " + mode + ")");
        }
        this._mode    = mode;
        this._speedHz = maxSpeedHz;
        this._fd      = openDevice(busNum, deviceNum, mode, maxSpeedHz);
    }

    /** Open with default mode 0 and 1 MHz clock. */
    public SPIConnection(int busNum, int deviceNum) throws IOException {
        this(busNum, deviceNum, 0, 1_000_000);
    }

    private static int openDevice(int busNum, int deviceNum, int mode, int maxSpeedHz) throws IOException {
        int fd = -1;
        try (var arena = Arena.ofConfined()) {
            var path = arena.allocateFrom("/dev/spidev" + busNum + "." + deviceNum);
            fd = (int) openMH.invoke(path, O_RDWR);
            if (fd < 0) throw new IOException(
                "open(/dev/spidev" + busNum + "." + deviceNum + ") failed");

            var u8  = arena.allocate(ValueLayout.JAVA_BYTE);
            var u32 = arena.allocate(ValueLayout.JAVA_INT);

            u8.set(ValueLayout.JAVA_BYTE, 0, (byte) mode);
            int rc = (int) ioctlPtrMH.invoke(fd, SPI_IOC_WR_MODE, u8);
            if (rc < 0) throw new IOException("SPI_IOC_WR_MODE ioctl failed: " + rc);

            u32.set(ValueLayout.JAVA_INT, 0, maxSpeedHz);
            rc = (int) ioctlPtrMH.invoke(fd, 0x40046b04L /* SPI_IOC_WR_MAX_SPEED_HZ */, u32);
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

    /** @return the configured SPI mode (0–3). */
    public int getMode()    { return _mode; }

    /** @return the configured clock frequency in Hz. */
    public int getSpeedHz() { return _speedHz; }

    @Override public void enable()  { /* always enabled */ }
    @Override public void disable() { /* no-op; per-AGENTS, default behaviour is no gate */ }
    @Override public boolean isEnabled() { return true; }
    @Override public InputPin  intPin() { return null; }
    @Override public OutputPin enPin()  { return null; }

    /**
     * Send bytes to the device.
     *
     * @param data bytes to send
     * @throws IOException on SPI error
     */
    @Override
    public void write(byte[] data) throws IOException {
        if (data == null || data.length == 0) return;
        try (var arena = Arena.ofConfined()) {
            var txBuf = arena.allocate(data.length);
            txBuf.copyFrom(MemorySegment.ofArray(data));
            var xfer = arena.allocate(SPI_IOC_XFER_SIZE);
            xfer.set(ValueLayout.JAVA_LONG, 0, txBuf.address());   // tx_buf
            xfer.set(ValueLayout.JAVA_LONG, 8, 0L);                // rx_buf
            xfer.set(ValueLayout.JAVA_INT,  16, data.length);      // len
            xfer.set(ValueLayout.JAVA_INT,  20, _speedHz);         // speed_hz
            int rc = (int) ioctlPtrMH.invoke(_fd, SPI_IOC_MESSAGE_1, xfer);
            if (rc < 0) throw new IOException("SPI_IOC_MESSAGE write failed: " + rc);
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException(t);
        }
    }

    /**
     * Read bytes from the device.
     *
     * @param n number of bytes to read
     * @return bytes received
     * @throws IOException on SPI error
     */
    @Override
    public byte[] read(int n) throws IOException {
        try (var arena = Arena.ofConfined()) {
            var rxBuf = arena.allocate(n);
            var xfer = arena.allocate(SPI_IOC_XFER_SIZE);
            xfer.set(ValueLayout.JAVA_LONG, 0, 0L);                 // tx_buf
            xfer.set(ValueLayout.JAVA_LONG, 8, rxBuf.address());    // rx_buf
            xfer.set(ValueLayout.JAVA_INT,  16, n);                 // len
            xfer.set(ValueLayout.JAVA_INT,  20, _speedHz);          // speed_hz
            int rc = (int) ioctlPtrMH.invoke(_fd, SPI_IOC_MESSAGE_1, xfer);
            if (rc < 0) throw new IOException("SPI_IOC_MESSAGE read failed: " + rc);
            return rxBuf.toArray(ValueLayout.JAVA_BYTE);
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException(t);
        }
    }

    /**
     * Write then read in a single {@code SPI_IOC_MESSAGE(2)} transfer (CS held).
     *
     * <p>Sends {@code data.length + n} bytes total; the first {@code data.length}
     * sent bytes are the command/address phase, the trailing {@code n} bytes are
     * dummy bytes that clock the response out. The {@code n} bytes received
     * during the read phase are returned.
     *
     * @param data command bytes to send
     * @param n    number of response bytes expected
     * @return the n response bytes
     * @throws IOException on SPI error
     */
    @Override
    public byte[] writeRead(byte[] data, int n) throws IOException {
        int cmdLen = data == null ? 0 : data.length;
        int total  = cmdLen + n;
        try (var arena = Arena.ofConfined()) {
            var txBuf = arena.allocate(Math.max(total, 1));
            var rxBuf = arena.allocate(Math.max(total, 1));
            if (cmdLen > 0) txBuf.asSlice(0, cmdLen).copyFrom(MemorySegment.ofArray(data));
            // Remaining bytes (the dummy read-phase TX) are already zero.

            var txSet = arena.allocate(SPI_IOC_XFER_SIZE);
            txSet.set(ValueLayout.JAVA_LONG, 0, txBuf.address());
            txSet.set(ValueLayout.JAVA_LONG, 8, rxBuf.address());
            txSet.set(ValueLayout.JAVA_INT,  16, total);
            txSet.set(ValueLayout.JAVA_INT,  20, _speedHz);

            int rc = (int) ioctlPtrMH.invoke(_fd, SPI_IOC_MESSAGE_2, txSet);
            if (rc < 0) throw new IOException("SPI_IOC_MESSAGE(2) failed: " + rc);

            byte[] full = rxBuf.toArray(ValueLayout.JAVA_BYTE);
            byte[] resp = new byte[n];
            System.arraycopy(full, cmdLen, resp, 0, n);
            return resp;
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException(t);
        }
    }

    /** Close the SPI device. */
    @Override
    public void close() throws IOException {
        try {
            closeMH.invoke(_fd);
        } catch (Throwable t) {
            throw new IOException(t);
        }
    }
}
