package it.uhde.periph.transport;

import java.io.IOException;
import java.lang.foreign.*;
import java.lang.invoke.*;

/**
 * I²C transport backed by Linux i2c-dev via FFM (no native libraries required).
 *
 * <p>Opens {@code /dev/i2c-<bus>} and sets the device address with {@code I2C_SLAVE} ioctl.
 * Subsequent {@link #write} / {@link #read} calls map directly to libc {@code write} /
 * {@code read}. {@link #writeRead} issues a stop-then-start between them; use a
 * platform-specific transport if the chip requires a true repeated-start.
 */
public final class I2CTransport implements Transport {

    private static final int O_RDWR = 2;
    private static final long I2C_SLAVE = 0x0703L;

    private static final MethodHandle openMH;
    private static final MethodHandle ioctlValueMH;
    private static final MethodHandle writeMH;
    private static final MethodHandle readMH;
    private static final MethodHandle closeMH;

    static {
        var linker = Linker.nativeLinker();
        var lookup  = linker.defaultLookup();
        openMH = linker.downcallHandle(
            lookup.find("open").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        ioctlValueMH = linker.downcallHandle(
            lookup.find("ioctl").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
        writeMH = linker.downcallHandle(
            lookup.find("write").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        readMH = linker.downcallHandle(
            lookup.find("read").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        closeMH = linker.downcallHandle(
            lookup.find("close").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    }

    private final int fd;

    /**
     * Open an I²C device.
     *
     * @param bus     I²C bus number (e.g. 1 for /dev/i2c-1)
     * @param address 7-bit device address
     * @throws IOException if the device cannot be opened or the address ioctl fails
     */
    public I2CTransport(int bus, int address) throws IOException {
        this.fd = openDevice(bus, address);
    }

    private static int openDevice(int bus, int address) throws IOException {
        int fd = -1;
        try (var arena = Arena.ofConfined()) {
            var path = arena.allocateFrom("/dev/i2c-" + bus);
            fd = (int) openMH.invoke(path, O_RDWR);
            if (fd < 0) throw new IOException("open(/dev/i2c-" + bus + ") failed");
            int rc = (int) ioctlValueMH.invoke(fd, I2C_SLAVE, (long) address);
            if (rc < 0) throw new IOException("I2C_SLAVE ioctl failed: " + rc);
            return fd;
        } catch (IOException e) {
            if (fd >= 0) { try { closeMH.invoke(fd); } catch (Throwable ignored) {} }
            throw e;
        } catch (Throwable t) {
            if (fd >= 0) { try { closeMH.invoke(fd); } catch (Throwable ignored) {} }
            throw new IOException(t);
        }
    }

    @Override
    public void write(byte[] data) throws IOException {
        try (var arena = Arena.ofConfined()) {
            var buf = arena.allocate(data.length);
            buf.copyFrom(MemorySegment.ofArray(data));
            long n = (long) writeMH.invoke(fd, buf, (long) data.length);
            if (n < 0) throw new IOException("write() failed: " + n);
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException(t);
        }
    }

    @Override
    public byte[] read(int n) throws IOException {
        try (var arena = Arena.ofConfined()) {
            var buf = arena.allocate(n);
            long got = (long) readMH.invoke(fd, buf, (long) n);
            if (got < 0) throw new IOException("read() failed: " + got);
            return buf.toArray(ValueLayout.JAVA_BYTE);
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException(t);
        }
    }

    /**
     * Writes then reads as two separate bus transactions (stop + start between them).
     * For chips requiring a true repeated-start, use a platform-specific transport.
     */
    @Override
    public byte[] writeRead(byte[] data, int n) throws IOException {
        write(data);
        return read(n);
    }

    @Override
    public void close() throws IOException {
        try {
            closeMH.invoke(fd);
        } catch (Throwable t) {
            throw new IOException(t);
        }
    }
}
