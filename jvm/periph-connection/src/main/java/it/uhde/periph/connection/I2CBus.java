package it.uhde.periph.connection;

import java.io.IOException;
import java.lang.foreign.*;
import java.lang.invoke.*;

/**
 * Bus-level I²C handle for scanning (Linux i2c-dev via FFM, no native libraries).
 *
 * <p>An {@link I2CConnection} is bound to one device address; scanning needs the
 * whole bus, so it lives here. {@link #probe} mirrors {@code i2cdetect}: an SMBus
 * quick write, or a read byte for EEPROM-class ranges and adapters without quick-write
 * support (the caller picks, see {@code it.uhde.periph.discovery.Discovery}).
 */
public final class I2CBus implements AutoCloseable {

    /** What one probe of one address told us. */
    public enum Probe {
        /** The device ACKed. */
        PRESENT,
        /** NACK: nothing at this address. */
        ABSENT,
        /** A kernel driver owns the address ({@code EBUSY}); counts as present. */
        KERNEL_BOUND,
        /** Any other bus error. */
        FAILED
    }

    private static final int O_RDWR = 2;
    private static final long I2C_SLAVE = 0x0703L;
    private static final long I2C_FUNCS = 0x0705L;
    private static final long I2C_SMBUS = 0x0720L;
    private static final long I2C_FUNC_SMBUS_QUICK = 0x00010000L;
    private static final int I2C_SMBUS_WRITE = 0;
    private static final int I2C_SMBUS_READ = 1;
    private static final int I2C_SMBUS_QUICK = 0;
    private static final int I2C_SMBUS_BYTE = 1;
    private static final int ENXIO = 6;
    private static final int EBUSY = 16;
    private static final int EREMOTEIO = 121;

    private static final MethodHandle openMH;
    private static final MethodHandle closeMH;
    private static final MethodHandle ioctlValueMH;
    private static final MethodHandle ioctlPtrMH;
    private static final StructLayout CAPTURE = Linker.Option.captureStateLayout();
    private static final VarHandle ERRNO = CAPTURE.varHandle(MemoryLayout.PathElement.groupElement("errno"));

    static {
        var linker = Linker.nativeLinker();
        var lookup = linker.defaultLookup();
        openMH = linker.downcallHandle(
            lookup.find("open").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        closeMH = linker.downcallHandle(
            lookup.find("close").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        var ioctl = lookup.find("ioctl").orElseThrow();
        ioctlValueMH = linker.downcallHandle(ioctl,
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG),
            Linker.Option.firstVariadicArg(2), Linker.Option.captureCallState("errno"));
        ioctlPtrMH = linker.downcallHandle(ioctl,
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS),
            Linker.Option.firstVariadicArg(2), Linker.Option.captureCallState("errno"));
    }

    private final int bus;
    private final int fd;
    private final boolean quickWrite;

    private I2CBus(int bus, int fd, boolean quickWrite) {
        this.bus = bus;
        this.fd = fd;
        this.quickWrite = quickWrite;
    }

    /**
     * Open {@code /dev/i2c-<bus>} for scanning.
     *
     * @param bus I²C bus number (e.g. 1 for /dev/i2c-1)
     * @return the open bus
     * @throws IOException if the device cannot be opened or {@code I2C_FUNCS} fails
     */
    public static I2CBus open(int bus) throws IOException {
        int fd = -1;
        try (var arena = Arena.ofConfined()) {
            fd = (int) openMH.invoke(arena.allocateFrom("/dev/i2c-" + bus), O_RDWR);
            if (fd < 0) throw new IOException("open(/dev/i2c-" + bus + ") failed");
            var capture = arena.allocate(CAPTURE);
            var funcs = arena.allocate(ValueLayout.JAVA_LONG);
            int rc = (int) ioctlPtrMH.invoke(capture, fd, I2C_FUNCS, funcs);
            if (rc < 0) throw new IOException("I2C_FUNCS ioctl failed, errno " + (int) ERRNO.get(capture, 0L));
            return new I2CBus(bus, fd, (funcs.get(ValueLayout.JAVA_LONG, 0) & I2C_FUNC_SMBUS_QUICK) != 0);
        } catch (IOException e) {
            if (fd >= 0) { try { closeMH.invoke(fd); } catch (Throwable ignored) {} }
            throw e;
        } catch (Throwable t) {
            if (fd >= 0) { try { closeMH.invoke(fd); } catch (Throwable ignored) {} }
            throw new IOException(t);
        }
    }

    /** @return the bus number this handle was opened with */
    public int bus() { return bus; }

    /** @return true when the adapter supports SMBus quick write */
    public boolean quickWriteSupported() { return quickWrite; }

    /**
     * Probe one address.
     *
     * @param address  7-bit address
     * @param readByte true for an SMBus read byte, false for a quick write
     * @return what the probe found
     * @throws IOException if the FFM call itself fails
     */
    public Probe probe(int address, boolean readByte) throws IOException {
        try (var arena = Arena.ofConfined()) {
            var capture = arena.allocate(CAPTURE);
            int rc = (int) ioctlValueMH.invoke(capture, fd, I2C_SLAVE, (long) address);
            if (rc < 0) return classify((int) ERRNO.get(capture, 0L));

            var args = arena.allocate(16);  // struct i2c_smbus_ioctl_data: u8, u8, pad, u32 size, ptr
            var scratch = arena.allocate(34); // union i2c_smbus_data
            args.set(ValueLayout.JAVA_BYTE, 0, (byte) (readByte ? I2C_SMBUS_READ : I2C_SMBUS_WRITE));
            args.set(ValueLayout.JAVA_BYTE, 1, (byte) 0);
            args.set(ValueLayout.JAVA_INT, 4, readByte ? I2C_SMBUS_BYTE : I2C_SMBUS_QUICK);
            args.set(ValueLayout.ADDRESS, 8, readByte ? scratch : MemorySegment.NULL);
            rc = (int) ioctlPtrMH.invoke(capture, fd, I2C_SMBUS, args);
            return rc < 0 ? classify((int) ERRNO.get(capture, 0L)) : Probe.PRESENT;
        } catch (Throwable t) {
            throw new IOException(t);
        }
    }

    private static Probe classify(int errno) {
        if (errno == EBUSY) return Probe.KERNEL_BOUND;
        if (errno == ENXIO || errno == EREMOTEIO) return Probe.ABSENT;
        return Probe.FAILED;
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
