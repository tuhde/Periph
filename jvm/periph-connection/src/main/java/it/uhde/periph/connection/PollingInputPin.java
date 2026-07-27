package it.uhde.periph.connection;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * {@link InputPin} fallback: samples a GPIO line on a 5&nbsp;ms {@link
 * ScheduledExecutorService} tick.
 *
 * <p>Uses the Linux GPIO v1 character device ({@code GPIO_GET_LINEHANDLE_IOCTL}
 * / {@code GPIOHANDLE_GET_LINE_VALUES_IOCTL} via FFM) — the same mechanism
 * already used internally by {@link DHTxxConnection} and {@link SiPoConnection}
 * — so it works out of the box on any Raspberry Pi with no prior sysfs export
 * step, unlike {@link SysfsInputPin}. Requires {@code --enable-native-access=
 * ALL-UNNAMED} (Java 21+).
 */
public final class PollingInputPin implements InputPin {

    private static final int O_RDWR = 2;

    private static final long GPIO_GET_LINEHANDLE_IOCTL        = 0xC16CB403L;
    private static final long GPIOHANDLE_GET_LINE_VALUES_IOCTL = 0xC040B408L;
    private static final int  GPIOHANDLE_REQUEST_INPUT         = 1 << 0;

    private static final int GR_SIZE     = 364;
    private static final int GR_OFFSETS  = 0;
    private static final int GR_FLAGS    = 256;
    private static final int GR_LINES    = 356;
    private static final int GR_FD       = 360;
    private static final int GD_SIZE     = 64;

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

    private static final ScheduledExecutorService SCHEDULER =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "periph-polling-input-pin");
            t.setDaemon(true);
            return t;
        });

    private final int lineFd;
    private final List<EdgeHandler> handlers = new CopyOnWriteArrayList<>();
    private volatile EdgeTrigger trigger = EdgeTrigger.FALLING;
    private volatile int last;
    private ScheduledFuture<?> task;

    /**
     * @param chipPath   path to the gpiochip device (e.g. {@code /dev/gpiochip0})
     * @param lineOffset GPIO line offset to watch
     * @throws IOException if the line cannot be requested as input
     */
    public PollingInputPin(String chipPath, int lineOffset) throws IOException {
        this.lineFd = requestLine(chipPath, lineOffset);
        this.last = readValue();
    }

    private static int requestLine(String chipPath, int lineOffset) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pathSeg = arena.allocateFrom(chipPath);
            int chipFd = (int) openMH.invoke(pathSeg, O_RDWR);
            if (chipFd < 0) throw new IOException("open(" + chipPath + ") failed");
            try {
                MemorySegment req = arena.allocate(GR_SIZE, 4);
                req.fill((byte) 0);
                req.set(ValueLayout.JAVA_INT, GR_OFFSETS, lineOffset);
                req.set(ValueLayout.JAVA_INT, GR_FLAGS, GPIOHANDLE_REQUEST_INPUT);
                req.set(ValueLayout.JAVA_INT, GR_LINES, 1);
                int rc = (int) ioctlAddrMH.invoke(chipFd, GPIO_GET_LINEHANDLE_IOCTL, req);
                if (rc < 0) throw new IOException("GPIO_GET_LINEHANDLE_IOCTL failed for line " + lineOffset);
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

    private int readValue() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = arena.allocate(GD_SIZE, 1);
            data.fill((byte) 0);
            int rc = (int) ioctlAddrMH.invoke(lineFd, GPIOHANDLE_GET_LINE_VALUES_IOCTL, data);
            if (rc < 0) return last;
            return data.get(ValueLayout.JAVA_BYTE, 0) & 0xFF;
        } catch (Throwable t) {
            return last;
        }
    }

    @Override
    public synchronized void onEdge(EdgeHandler handler, EdgeTrigger trigger) {
        handlers.add(handler);
        this.trigger = trigger;
        if (task == null) {
            task = SCHEDULER.scheduleAtFixedRate(this::tick, 5, 5, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public synchronized void offEdge(EdgeHandler handler) {
        handlers.remove(handler);
        if (handlers.isEmpty() && task != null) {
            task.cancel(false);
            task = null;
        }
    }

    private void tick() {
        int current = readValue();
        if (current == last) return;
        boolean rising  = current != 0 && last == 0;
        boolean falling = current == 0 && last != 0;
        last = current;
        if (trigger == EdgeTrigger.CHANGE
            || (trigger == EdgeTrigger.RISING && rising)
            || (trigger == EdgeTrigger.FALLING && falling)) {
            for (EdgeHandler h : handlers) h.onEdge();
        }
    }

    @Override
    public void close() {
        if (task != null) task.cancel(false);
        try { closeMH.invoke(lineFd); } catch (Throwable ignored) {}
    }
}
