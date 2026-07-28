package it.uhde.periph.connection;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link InputPin} backed by the Linux sysfs GPIO interface, blocking on
 * {@code poll(2)} (via FFM) for lower latency than {@link PollingInputPin}'s
 * 5&nbsp;ms sampling.
 *
 * <p>The GPIO must already be exported (e.g. {@code echo N >
 * /sys/class/gpio/export}) and owned by the current user. Opt-in: use this
 * when the INT line is wired and exported and lower latency matters;
 * otherwise prefer {@link PollingInputPin}, which works with no prior setup.
 * Requires {@code --enable-native-access=ALL-UNNAMED} (Java 21+).
 */
public final class SysfsInputPin implements InputPin {

    private static final int O_RDONLY = 0;
    private static final short POLLPRI = 0x0002;

    private static final MethodHandle openMH;
    private static final MethodHandle pollMH;
    private static final MethodHandle lseekMH;
    private static final MethodHandle readMH;
    private static final MethodHandle closeMH;

    static {
        Linker linker = Linker.nativeLinker();
        var lookup = linker.defaultLookup();
        openMH = linker.downcallHandle(
            lookup.find("open").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        // struct pollfd { int fd; short events; short revents; } — 8 bytes
        pollMH = linker.downcallHandle(
            lookup.find("poll").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
        lseekMH = linker.downcallHandle(
            lookup.find("lseek").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
        readMH = linker.downcallHandle(
            lookup.find("read").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        closeMH = linker.downcallHandle(
            lookup.find("close").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    }

    private final Path valuePath;
    private final Path edgePath;
    private final int valueFd;
    private final List<EdgeHandler> handlers = new CopyOnWriteArrayList<>();
    private volatile EdgeTrigger trigger = EdgeTrigger.FALLING;
    private final AtomicBoolean armed = new AtomicBoolean(false);
    private volatile boolean closed;
    private Thread thread;

    /**
     * @param gpioNumber sysfs GPIO number (the {@code N} in {@code /sys/class/gpio/gpioN})
     */
    public SysfsInputPin(int gpioNumber) {
        Path base = Path.of("/sys/class/gpio/gpio" + gpioNumber);
        this.valuePath = base.resolve("value");
        this.edgePath = base.resolve("edge");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment path = arena.allocateFrom(valuePath.toString());
            this.valueFd = (int) openMH.invoke(path, O_RDONLY);
            if (valueFd < 0) throw new UncheckedIOException(new IOException("open(" + valuePath + ") failed"));
        } catch (Throwable t) {
            throw new UncheckedIOException(new IOException(t));
        }
    }

    @Override
    public synchronized void onEdge(EdgeHandler handler, EdgeTrigger trigger) {
        handlers.add(handler);
        this.trigger = trigger;
        if (armed.compareAndSet(false, true)) {
            try {
                Files.writeString(edgePath, "both");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            thread = new Thread(this::loop, "periph-sysfs-input-pin");
            thread.setDaemon(true);
            thread.start();
        }
    }

    @Override
    public synchronized void offEdge(EdgeHandler handler) {
        handlers.remove(handler);
    }

    private void loop() {
        int last = readValue();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pfd = arena.allocate(8);
            pfd.set(ValueLayout.JAVA_INT, 0, valueFd);
            pfd.set(ValueLayout.JAVA_SHORT, 4, POLLPRI);
            while (!closed) {
                pfd.set(ValueLayout.JAVA_SHORT, 6, (short) 0); // revents
                int rc = (int) pollMH.invoke(pfd, 1L, 100); // 100 ms timeout so close() is noticed promptly
                if (rc <= 0) continue;
                int current = readValue();
                if (current == last) continue;
                boolean rising  = current != 0 && last == 0;
                boolean falling = current == 0 && last != 0;
                last = current;
                if (trigger == EdgeTrigger.CHANGE
                    || (trigger == EdgeTrigger.RISING && rising)
                    || (trigger == EdgeTrigger.FALLING && falling)) {
                    for (EdgeHandler h : handlers) h.onEdge();
                }
            }
        } catch (Throwable ignored) {
            // exit the loop; close() has already torn down the fd
        }
    }

    private int readValue() {
        try {
            lseekMH.invoke(valueFd, 0L, 0); // SEEK_SET
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buf = arena.allocate(1);
                long n = (long) readMH.invoke(valueFd, buf, 1L);
                if (n <= 0) return 0;
                return (buf.get(ValueLayout.JAVA_BYTE, 0) == '1') ? 1 : 0;
            }
        } catch (Throwable t) {
            return 0;
        }
    }

    @Override
    public void close() {
        closed = true;
        if (thread != null) {
            try { thread.join(200); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        try { closeMH.invoke(valueFd); } catch (Throwable ignored) {}
    }
}
