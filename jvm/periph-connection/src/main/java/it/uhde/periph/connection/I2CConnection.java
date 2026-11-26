package it.uhde.periph.connection;

import java.io.IOException;
import java.lang.foreign.*;

/**
 * I²C connection backed by Linux i2c-dev via FFM (no native libraries required).
 *
 * <p>Opens {@code /dev/i2c-<bus>} and sets the device address with {@code I2C_SLAVE} ioctl.
 * Subsequent {@link #write} / {@link #read} calls map directly to libc {@code write} /
 * {@code read}; a transfer that moves fewer bytes than requested throws, and every
 * {@link IOException} carries {@code strerror(errno)}. {@link #writeRead} is one
 * {@code I2C_RDWR} transaction (write message, repeated START, read message), so the
 * bus is not released between the two phases.
 */
public final class I2CConnection extends AbstractConnection implements RegisterConnection {

    private static final int O_RDWR = 2;
    private static final long I2C_SLAVE = 0x0703L;
    private static final long I2C_RDWR  = 0x0707L;
    private static final short I2C_M_RD = 0x0001;

    /** {@code struct i2c_msg { __u16 addr; __u16 flags; __u16 len; __u8 *buf; }} */
    private static final StructLayout I2C_MSG = MemoryLayout.structLayout(
        ValueLayout.JAVA_SHORT.withName("addr"),
        ValueLayout.JAVA_SHORT.withName("flags"),
        ValueLayout.JAVA_SHORT.withName("len"),
        MemoryLayout.paddingLayout(2),
        ValueLayout.ADDRESS.withName("buf"));
    /** {@code struct i2c_rdwr_ioctl_data { struct i2c_msg *msgs; __u32 nmsgs; }} */
    private static final StructLayout I2C_RDWR_DATA = ValueLayout.ADDRESS.byteSize() == 8
        ? MemoryLayout.structLayout(ValueLayout.ADDRESS.withName("msgs"),
                                    ValueLayout.JAVA_INT.withName("nmsgs"),
                                    MemoryLayout.paddingLayout(4))
        : MemoryLayout.structLayout(ValueLayout.ADDRESS.withName("msgs"),
                                    ValueLayout.JAVA_INT.withName("nmsgs"));
    private static final long MSG_ADDR  = I2C_MSG.byteOffset(MemoryLayout.PathElement.groupElement("addr"));
    private static final long MSG_FLAGS = I2C_MSG.byteOffset(MemoryLayout.PathElement.groupElement("flags"));
    private static final long MSG_LEN   = I2C_MSG.byteOffset(MemoryLayout.PathElement.groupElement("len"));
    private static final long MSG_BUF   = I2C_MSG.byteOffset(MemoryLayout.PathElement.groupElement("buf"));

    private final int fd;
    private final int address;
    private final int regBytes;
    private final String devPath;

    /**
     * Open an I²C device.
     *
     * @param bus      I²C bus number (e.g. 1 for /dev/i2c-1)
     * @param address  7-bit device address
     * @param intPin   optional INT-line {@link InputPin}, or {@code null}
     * @param enPin    optional EN-pin {@link OutputPin}, or {@code null}
     * @param regBytes register address width in bytes, big-endian (default 1)
     * @throws IOException if the device cannot be opened or the address ioctl fails
     */
    public I2CConnection(int bus, int address, InputPin intPin, OutputPin enPin, int regBytes) throws IOException {
        super(intPin, enPin);
        this.fd = openDevice(bus, address);
        this.address = address;
        this.regBytes = regBytes;
        this.devPath = "/dev/i2c-" + bus;
    }

    /**
     * Open an I²C device, register address width 1 byte.
     *
     * @param bus     I²C bus number (e.g. 1 for /dev/i2c-1)
     * @param address 7-bit device address
     * @param intPin  optional INT-line {@link InputPin}, or {@code null}
     * @param enPin   optional EN-pin {@link OutputPin}, or {@code null}
     * @throws IOException if the device cannot be opened or the address ioctl fails
     */
    public I2CConnection(int bus, int address, InputPin intPin, OutputPin enPin) throws IOException {
        this(bus, address, intPin, enPin, 1);
    }

    /**
     * Open an I²C device with no INT/EN pins.
     *
     * @param bus     I²C bus number (e.g. 1 for /dev/i2c-1)
     * @param address 7-bit device address
     * @throws IOException if the device cannot be opened or the address ioctl fails
     */
    public I2CConnection(int bus, int address) throws IOException {
        this(bus, address, null, null, 1);
    }

    @Override
    public int regBytes() { return regBytes; }

    private static int openDevice(int bus, int address) throws IOException {
        String path = "/dev/i2c-" + bus;
        int fd = -1;
        try (var arena = Arena.ofConfined()) {
            var cap = Libc.capture(arena);
            fd = (int) Libc.OPEN.invoke(cap, arena.allocateFrom(path), O_RDWR);
            if (fd < 0) throw Libc.error("open(" + path + ")", cap);
            int rc = (int) Libc.IOCTL_LONG.invoke(cap, fd, I2C_SLAVE, (long) address);
            if (rc < 0) throw Libc.error(String.format("I2C_SLAVE ioctl(%s, 0x%02X)", path, address), cap);
            return fd;
        } catch (IOException e) {
            Libc.closeQuietly(fd);
            throw e;
        } catch (Throwable t) {
            Libc.closeQuietly(fd);
            throw new IOException(t);
        }
    }

    /**
     * Send bytes to the device.
     *
     * @throws IOException if {@code write()} fails (e.g. NACK: {@code EREMOTEIO}) or sends
     *                     fewer bytes than requested
     */
    @Override
    protected void _write(byte[] data) throws IOException {
        try (var arena = Arena.ofConfined()) {
            var cap = Libc.capture(arena);
            var buf = arena.allocate(Math.max(data.length, 1));
            buf.copyFrom(MemorySegment.ofArray(data));
            long n = (long) Libc.WRITE.invoke(cap, fd, buf, (long) data.length);
            if (n < 0) throw Libc.error("write(" + devPath + ")", cap);
            if (n != data.length) throw Libc.shortTransfer("write(" + devPath + ")", n, data.length);
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException(t);
        }
    }

    /**
     * Read bytes from the device.
     *
     * @throws IOException if {@code read()} fails or returns fewer bytes than requested
     */
    @Override
    protected byte[] _read(int n) throws IOException {
        try (var arena = Arena.ofConfined()) {
            var cap = Libc.capture(arena);
            var buf = arena.allocate(Math.max(n, 1));
            long got = (long) Libc.READ.invoke(cap, fd, buf, (long) n);
            if (got < 0) throw Libc.error("read(" + devPath + ")", cap);
            if (got != n) throw Libc.shortTransfer("read(" + devPath + ")", got, n);
            return buf.asSlice(0, n).toArray(ValueLayout.JAVA_BYTE);
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException(t);
        }
    }

    /**
     * Write then read in one {@code I2C_RDWR} transaction with a repeated START between the
     * phases (no STOP), as register reads on most chips expect.
     *
     * @throws IOException if the ioctl fails or completes fewer than both messages
     */
    @Override
    protected byte[] _writeRead(byte[] data, int n) throws IOException {
        try (var arena = Arena.ofConfined()) {
            var cap = Libc.capture(arena);
            var wbuf = arena.allocate(Math.max(data.length, 1));
            wbuf.copyFrom(MemorySegment.ofArray(data));
            var rbuf = arena.allocate(Math.max(n, 1));

            var msgs = arena.allocate(I2C_MSG, 2);
            setMsg(msgs, 0, (short) 0, data.length, wbuf);
            setMsg(msgs, 1, I2C_M_RD, n, rbuf);

            var rdwr = arena.allocate(I2C_RDWR_DATA);
            rdwr.set(ValueLayout.ADDRESS, 0, msgs);
            rdwr.set(ValueLayout.JAVA_INT, ValueLayout.ADDRESS.byteSize(), 2);

            int rc = (int) Libc.IOCTL_PTR.invoke(cap, fd, I2C_RDWR, rdwr);
            if (rc < 0) throw Libc.error("I2C_RDWR ioctl(" + devPath + ")", cap);
            if (rc != 2) throw new IOException("I2C_RDWR ioctl(" + devPath + ") completed " + rc + " of 2 messages");
            return rbuf.asSlice(0, n).toArray(ValueLayout.JAVA_BYTE);
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException(t);
        }
    }

    private void setMsg(MemorySegment msgs, int i, short flags, int len, MemorySegment buf) {
        long base = i * I2C_MSG.byteSize();
        msgs.set(ValueLayout.JAVA_SHORT, base + MSG_ADDR,  (short) address);
        msgs.set(ValueLayout.JAVA_SHORT, base + MSG_FLAGS, flags);
        msgs.set(ValueLayout.JAVA_SHORT, base + MSG_LEN,   (short) len);
        msgs.set(ValueLayout.ADDRESS,    base + MSG_BUF,   buf);
    }

    @Override
    public void close() throws IOException {
        Libc.close(fd, devPath);
    }
}
