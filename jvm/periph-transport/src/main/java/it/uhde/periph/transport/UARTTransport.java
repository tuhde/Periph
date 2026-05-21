package it.uhde.periph.transport;

import java.io.IOException;
import java.lang.foreign.*;
import java.lang.invoke.*;

/**
 * UART transport backed by Linux termios via FFM (no native libraries required).
 *
 * <p>Opens the serial device with {@code open(path, O_RDWR | O_NOCTTY)} and
 * configures it via {@code tcgetattr} / {@code tcsetattr} in raw mode (VMIN=0,
 * VTIME-based read timeout). Subsequent {@link #write} / {@link #read} calls map
 * directly to libc {@code write} / {@code read} + {@code tcdrain}.
 *
 * <p>For RS-485, the transport first tries kernel RS-485 mode via
 * {@code ioctl(fd, TIOCSRS485, ...)} with {@code SER_RS485_ENABLED | SER_RS485_RTS_ON_SEND}.
 * If the kernel driver does not support it and {@code dePinNum != -1}, it falls back
 * to manual GPIO toggling via {@code /dev/gpiochipN} (ioctl GPIOHANDLE_REQUEST +
 * GPIOHANDLE_SET_LINE_VALUES_IOCTL).
 *
 * <p>Requires {@code --enable-native-access=ALL-UNNAMED} (Java 21+).
 */
public final class UARTTransport implements Transport {

    // open() flags
    private static final int O_RDWR   = 2;
    private static final int O_NOCTTY = 0400;

    // termios c_cflag bits
    private static final int CREAD  = 0200;
    private static final int CLOCAL = 04000;
    private static final int CSIZE  = 060;
    private static final int CS5    = 0;
    private static final int CS6    = 020;
    private static final int CS7    = 040;
    private static final int CS8    = 060;
    private static final int CSTOPB = 0100;
    private static final int PARENB = 01000;
    private static final int PARODD = 02000;

    // termios c_iflag
    private static final int IGNBRK = 01;
    private static final int BRKINT = 02;
    private static final int PARMRK = 010;
    private static final int ISTRIP = 040;
    private static final int INLCR  = 0100;
    private static final int IGNCR  = 0200;
    private static final int ICRNL  = 0400;
    private static final int IXON   = 02000;

    // termios c_oflag
    private static final int OPOST = 01;

    // termios c_lflag
    private static final int ECHO   = 010;
    private static final int ECHONL = 040;
    private static final int ICANON = 02;
    private static final int ISIG   = 01;
    private static final int IEXTEN = 0100000;

    // struct termios indices (x86_64 Linux layout, 60 bytes)
    // c_iflag(0), c_oflag(4), c_cflag(8), c_lflag(12), c_line(16),
    // c_cc[0..31](17..48), c_ispeed(52), c_ospeed(56)
    private static final int TERMIOS_IFLAG  = 0;
    private static final int TERMIOS_OFLAG  = 4;
    private static final int TERMIOS_CFLAG  = 8;
    private static final int TERMIOS_LFLAG  = 12;
    private static final int TERMIOS_CC     = 17;
    private static final int TERMIOS_ISPEED = 52;
    private static final int TERMIOS_OSPEED = 56;
    private static final int TERMIOS_SIZE   = 60;

    // c_cc indices
    private static final int VMIN  = 6;
    private static final int VTIME = 5;

    // tcsetattr action
    private static final int TCSANOW = 0;

    // RS-485 ioctl
    private static final long TIOCSRS485        = 0x542F;
    private static final int  SER_RS485_ENABLED = 1;
    private static final int  SER_RS485_RTS_ON_SEND = 2;
    // struct serial_rs485 is 64 bytes on Linux
    private static final int  SERIAL_RS485_SIZE = 64;

    private static final MethodHandle openMH;
    private static final MethodHandle ioctlAddrMH;
    private static final MethodHandle ioctlLongMH;
    private static final MethodHandle tcgetattrMH;
    private static final MethodHandle tcsetattrMH;
    private static final MethodHandle cfsetispeedMH;
    private static final MethodHandle cfsetospeedMH;
    private static final MethodHandle writeMH;
    private static final MethodHandle readMH;
    private static final MethodHandle tcdrainMH;
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
        ioctlLongMH = linker.downcallHandle(
            lookup.find("ioctl").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
        tcgetattrMH = linker.downcallHandle(
            lookup.find("tcgetattr").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        tcsetattrMH = linker.downcallHandle(
            lookup.find("tcsetattr").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        cfsetispeedMH = linker.downcallHandle(
            lookup.find("cfsetispeed").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        cfsetospeedMH = linker.downcallHandle(
            lookup.find("cfsetospeed").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        writeMH = linker.downcallHandle(
            lookup.find("write").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        readMH = linker.downcallHandle(
            lookup.find("read").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        tcdrainMH = linker.downcallHandle(
            lookup.find("tcdrain").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        closeMH = linker.downcallHandle(
            lookup.find("close").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    }

    private final int fd;
    private final boolean rs485Kernel;
    private final int dePinNum;

    /**
     * Open a UART device.
     *
     * @param port      Serial device path (e.g. {@code /dev/ttyS0}).
     * @param baudRate  Baud rate; default 9600.
     * @param dataBits  Data bits (5–8); default 8.
     * @param stopBits  Stop bits (1, 1.5, or 2); default 1.0.
     * @param parity    Parity — {@code 'N'} none, {@code 'E'} even, {@code 'O'} odd.
     * @param timeoutMs Read timeout in milliseconds; default 1000.
     * @param dePinNum  GPIO line number for RS-485 DE (active high); -1 disables.
     * @throws IOException if the port cannot be opened or configured.
     */
    public UARTTransport(String port, int baudRate, int dataBits, double stopBits,
                         char parity, int timeoutMs, int dePinNum) throws IOException {
        int[] result = openDevice(port, baudRate, dataBits, stopBits, parity, timeoutMs, dePinNum);
        this.fd          = result[0];
        this.rs485Kernel = result[1] != 0;
        this.dePinNum    = dePinNum;
    }

    /**
     * Open a UART device with default parameters (9600 8N1, 1000 ms timeout, no RS-485).
     *
     * @param port Serial device path (e.g. {@code /dev/ttyS0}).
     * @throws IOException if the port cannot be opened or configured.
     */
    public UARTTransport(String port) throws IOException {
        this(port, 9600, 8, 1.0, 'N', 1000, -1);
    }

    private static int baudToSpeed(int baud) throws IOException {
        return switch (baud) {
            case 50     -> 0000001;
            case 75     -> 0000002;
            case 110    -> 0000003;
            case 134    -> 0000004;
            case 150    -> 0000005;
            case 200    -> 0000006;
            case 300    -> 0000007;
            case 600    -> 0000010;
            case 1200   -> 0000011;
            case 1800   -> 0000012;
            case 2400   -> 0000013;
            case 4800   -> 0000014;
            case 9600   -> 0000015;
            case 19200  -> 0000016;
            case 38400  -> 0000017;
            case 57600  -> 0010001;
            case 115200 -> 0010002;
            case 230400 -> 0010003;
            case 460800 -> 0010004;
            case 921600 -> 0010007;
            default -> throw new IOException("Unsupported baud rate: " + baud);
        };
    }

    private static int[] openDevice(String port, int baudRate, int dataBits, double stopBits,
                                     char parity, int timeoutMs, int dePinNum) throws IOException {
        int fd = -1;
        try (var arena = Arena.ofConfined()) {
            var pathSeg = arena.allocateFrom(port);
            fd = (int) openMH.invoke(pathSeg, O_RDWR | O_NOCTTY);
            if (fd < 0) throw new IOException("open(" + port + ") failed");

            var tty = arena.allocate(TERMIOS_SIZE, 4);
            int rc = (int) tcgetattrMH.invoke(fd, tty);
            if (rc < 0) throw new IOException("tcgetattr failed");

            // Raw mode: clear input, output, local flags
            tty.set(ValueLayout.JAVA_INT, TERMIOS_IFLAG,
                    tty.get(ValueLayout.JAVA_INT, TERMIOS_IFLAG)
                    & ~(IGNBRK | BRKINT | PARMRK | ISTRIP | INLCR | IGNCR | ICRNL | IXON));
            tty.set(ValueLayout.JAVA_INT, TERMIOS_OFLAG,
                    tty.get(ValueLayout.JAVA_INT, TERMIOS_OFLAG) & ~OPOST);
            tty.set(ValueLayout.JAVA_INT, TERMIOS_LFLAG,
                    tty.get(ValueLayout.JAVA_INT, TERMIOS_LFLAG)
                    & ~(ECHO | ECHONL | ICANON | ISIG | IEXTEN));

            // Data bits
            int cflag = tty.get(ValueLayout.JAVA_INT, TERMIOS_CFLAG) & ~CSIZE;
            cflag |= switch (dataBits) {
                case 5  -> CS5;
                case 6  -> CS6;
                case 7  -> CS7;
                default -> CS8;
            };

            // Stop bits
            if (stopBits == 2.0) cflag |= CSTOPB;
            else                  cflag &= ~CSTOPB;

            // Parity
            cflag &= ~(PARENB | PARODD);
            if      (parity == 'E') cflag |= PARENB;
            else if (parity == 'O') cflag |= PARENB | PARODD;

            cflag |= CREAD | CLOCAL;
            tty.set(ValueLayout.JAVA_INT, TERMIOS_CFLAG, cflag);

            // Baud rate
            int speed = baudToSpeed(baudRate);
            cfsetispeedMH.invoke(tty, speed);
            cfsetospeedMH.invoke(tty, speed);

            // Timeout: VMIN=0, VTIME in 0.1 s units
            tty.set(ValueLayout.JAVA_BYTE, TERMIOS_CC + VMIN,  (byte) 0);
            tty.set(ValueLayout.JAVA_BYTE, TERMIOS_CC + VTIME, (byte) ((timeoutMs + 99) / 100));

            rc = (int) tcsetattrMH.invoke(fd, TCSANOW, tty);
            if (rc < 0) throw new IOException("tcsetattr failed");

            // RS-485 kernel mode
            boolean kernelRs485 = false;
            if (dePinNum >= 0) {
                var rs485 = arena.allocate(SERIAL_RS485_SIZE, 4);
                rs485.fill((byte) 0);
                rs485.set(ValueLayout.JAVA_INT, 0, SER_RS485_ENABLED | SER_RS485_RTS_ON_SEND);
                int irc = (int) ioctlAddrMH.invoke(fd, TIOCSRS485, rs485);
                kernelRs485 = (irc == 0);
            }

            return new int[]{fd, kernelRs485 ? 1 : 0};

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
            int rc = (int) tcdrainMH.invoke(fd);
            if (rc < 0) throw new IOException("tcdrain() failed");
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
            int got = 0;
            while (got < n) {
                long r = (long) readMH.invoke(fd, buf.asSlice(got), (long) (n - got));
                if (r <= 0) throw new IOException("UART read timeout after " + got + " of " + n + " bytes");
                got += (int) r;
            }
            return buf.toArray(ValueLayout.JAVA_BYTE);
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException(t);
        }
    }

    /**
     * Writes then reads as two sequential operations (write drains via tcdrain,
     * then read begins).
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
