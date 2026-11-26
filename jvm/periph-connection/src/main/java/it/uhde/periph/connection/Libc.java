package it.uhde.periph.connection;

import java.io.IOException;
import java.lang.foreign.*;
import java.lang.invoke.*;

/**
 * Shared libc downcalls for the Linux FFM connections, each capturing {@code errno}.
 *
 * <p>Every handle takes a capture segment from {@link #capture(Arena)} as its first
 * argument; after a failed call, {@link #error(String, MemorySegment)} turns it into an
 * {@link IOException} whose message names the call and carries {@code strerror(errno)},
 * so a fault reads {@code "write(/dev/i2c-1) failed: Remote I/O error (errno 121)"}
 * instead of a bare return code.
 */
final class Libc {

    private Libc() {}

    private static final StructLayout CAPTURE = Linker.Option.captureStateLayout();
    private static final VarHandle ERRNO =
        CAPTURE.varHandle(MemoryLayout.PathElement.groupElement("errno"));

    /** {@code int open(const char*, int)} */
    static final MethodHandle OPEN;
    /** {@code int close(int)} */
    static final MethodHandle CLOSE;
    /** {@code ssize_t read(int, void*, size_t)} */
    static final MethodHandle READ;
    /** {@code ssize_t write(int, const void*, size_t)} */
    static final MethodHandle WRITE;
    /** {@code int ioctl(int, unsigned long, void*)} */
    static final MethodHandle IOCTL_PTR;
    /** {@code int ioctl(int, unsigned long, unsigned long)} */
    static final MethodHandle IOCTL_LONG;
    /** {@code int tcdrain(int)} */
    static final MethodHandle TCDRAIN;
    /** {@code off_t lseek(int, off_t, int)} */
    static final MethodHandle LSEEK;

    private static final MethodHandle STRERROR;

    static {
        var linker = Linker.nativeLinker();
        var lookup = linker.defaultLookup();
        var errno = Linker.Option.captureCallState("errno");
        var variadic = Linker.Option.firstVariadicArg(2);
        OPEN = linker.downcallHandle(lookup.find("open").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
            variadic, errno);
        CLOSE = linker.downcallHandle(lookup.find("close").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT), errno);
        READ = linker.downcallHandle(lookup.find("read").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG), errno);
        WRITE = linker.downcallHandle(lookup.find("write").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG), errno);
        var ioctl = lookup.find("ioctl").orElseThrow();
        IOCTL_PTR = linker.downcallHandle(ioctl,
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS), variadic, errno);
        IOCTL_LONG = linker.downcallHandle(ioctl,
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG), variadic, errno);
        TCDRAIN = linker.downcallHandle(lookup.find("tcdrain").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT), errno);
        LSEEK = linker.downcallHandle(lookup.find("lseek").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT), errno);
        STRERROR = linker.downcallHandle(lookup.find("strerror").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    }

    /** Allocate the errno capture segment passed as the first argument of every handle here. */
    static MemorySegment capture(Arena arena) {
        return arena.allocate(CAPTURE);
    }

    /** The {@code errno} recorded by the most recent call made with {@code capture}. */
    static int errno(MemorySegment capture) {
        return (int) ERRNO.get(capture, 0L);
    }

    /** {@code strerror(errno)}, or {@code "errno N"} if libc returns nothing. */
    static String strerror(int errno) {
        try {
            var p = (MemorySegment) STRERROR.invoke(errno);
            if (p.equals(MemorySegment.NULL)) return "errno " + errno;
            return p.reinterpret(256).getString(0);
        } catch (Throwable t) {
            return "errno " + errno;
        }
    }

    /** An IOException for a failed call: {@code "<what> failed: <strerror> (errno N)"}. */
    static IOException error(String what, MemorySegment capture) {
        int e = errno(capture);
        return new IOException(what + " failed: " + strerror(e) + " (errno " + e + ")");
    }

    /** {@code close(fd)}, ignoring errors (cleanup paths); a negative fd is skipped. */
    static void closeQuietly(int fd) {
        if (fd < 0) return;
        try (var arena = Arena.ofConfined()) {
            CLOSE.invoke(capture(arena), fd);
        } catch (Throwable ignored) {}
    }

    /** {@code close(fd)}, throwing with errno if it fails; a negative fd is skipped. */
    static void close(int fd, String what) throws IOException {
        if (fd < 0) return;
        try (var arena = Arena.ofConfined()) {
            var cap = capture(arena);
            if ((int) CLOSE.invoke(cap, fd) < 0) throw error("close(" + what + ")", cap);
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException(t);
        }
    }

    /** An IOException for a call that moved fewer bytes than requested. */
    static IOException shortTransfer(String what, long got, long want) {
        return new IOException(what + " transferred " + got + " of " + want + " bytes");
    }
}
