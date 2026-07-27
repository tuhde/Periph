package it.uhde.periph.chips.io_expander

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin

import java.util.function.Consumer
import java.util.function.IntConsumer

/**
 * PCF8575 full driver — extends {@link Pcf8575Minimal} with interrupt-on-change support.
 *
 * <p>{@link #onInterrupt(IntConsumer)} subscribes to the chip's INT line; delivery
 * uses {@code connection.intPin()} (or an explicit override) if wired, otherwise
 * falls back to a 5&nbsp;ms polling thread automatically. {@link #pollInterrupt()}
 * reads current pin states and returns the 16-bit bitmask of changed pins
 * (bits 0–7 = Port 0, bits 8–15 = Port 1). Per-pin {@link Pin#watch(Consumer)}
 * is also available.
 */
@CompileStatic
class Pcf8575Full extends Pcf8575Minimal {

    private int[] prev = [0xFF, 0xFF] as int[]
    private volatile IntConsumer callback
    private InputPin intPin
    private volatile boolean polling = false
    private Thread pollThread
    private final PinWatch[] watches = new PinWatch[16]

    /**
     * Fixed edge handler instance, reused across onEdge()/offEdge() calls — a
     * fresh Closure per call would not be guaranteed to be identical/equal to
     * the one passed to onEdge() earlier.
     */
    private final EdgeHandler edgeHandler = { -> handleEdge() } as EdgeHandler

    private static final class PinWatch {
        Consumer<Pin> handler
        EdgeTrigger trigger
        boolean lastState
    }

    Pcf8575Full(Connection connection) {
        super(connection)
        byte[] buf = connection.read(2)
        prev[0] = buf[0] & 0xFF
        prev[1] = buf[1] & 0xFF
    }

    @Override
    Pin pin(int n) {
        return new Pin(this, n)
    }

    /**
     * Subscribe to INT assertions using {@code connection.intPin()} (or a 5&nbsp;ms
     * polling thread if none is wired).
     *
     * @param callback called with the 16-bit changed-pin bitmask on any input change
     */
    void onInterrupt(IntConsumer callback) {
        onInterrupt(callback, connection.intPin())
    }

    /**
     * Subscribe to INT assertions, overriding which {@link InputPin} delivers edges.
     *
     * @param callback called with the 16-bit changed-pin bitmask on any input change
     * @param intPin   INT-line pin to arm, or {@code null} to force the 5&nbsp;ms polling fallback
     */
    void onInterrupt(IntConsumer callback, InputPin intPin) {
        this.callback = callback
        if (intPin != null) {
            this.intPin = intPin
            intPin.onEdge(edgeHandler, EdgeTrigger.FALLING)
        } else {
            startPolling()
        }
    }

    /** Unsubscribe and stop delivery. */
    void offInterrupt() {
        callback = null
        if (intPin != null) {
            intPin.offEdge(edgeHandler)
            intPin = null
        }
        stopPolling()
    }

    private void startPolling() {
        polling = true
        pollThread = new Thread({
            while (polling) {
                handleEdge()
                try {
                    Thread.sleep(5)
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        } as Runnable, 'pcf8575-poll')
        pollThread.daemon = true
        pollThread.start()
    }

    private void stopPolling() {
        polling = false
        if (pollThread != null) {
            pollThread.interrupt()
            pollThread = null
        }
    }

    private void handleEdge() {
        try {
            int changed = pollInterrupt()
            if (changed == 0) return
            if (callback != null) callback.accept(changed)
            for (int n = 0; n < 16; n++) {
                if ((changed & (1 << n)) == 0) continue
                PinWatch w = watches[n]
                if (w == null || w.handler == null) continue
                int port = n / 8
                int bit = n % 8
                boolean current = ((prev[port] >> bit) & 1) == 1
                boolean rising  = current && !w.lastState
                boolean falling = !current && w.lastState
                w.lastState = current
                boolean fire = w.trigger == EdgeTrigger.CHANGE ||
                    (w.trigger == EdgeTrigger.RISING && rising) ||
                    (w.trigger == EdgeTrigger.FALLING && falling)
                if (fire) w.handler.accept(new Pin(this, n))
            }
        } catch (IOException ignored) {
            // bus error; wait for the next edge/tick rather than propagating
        }
    }

    /**
     * Read current pin states and return the 16-bit bitmask of pins that changed
     * since last read. Also clears the chip's INT output.
     *
     * @return 16-bit bitmask; bits 0–7 = Port 0 changed, bits 8–15 = Port 1 changed
     */
    int pollInterrupt() {
        byte[] current = connection.read(2)
        int ch0 = (current[0] ^ prev[0]) & 0xFF
        int ch1 = (current[1] ^ prev[1]) & 0xFF
        prev[0] = current[0] & 0xFF
        prev[1] = current[1] & 0xFF
        return ch0 | (ch1 << 8)
    }

    /** GPIO proxy for a single PCF8575 pin — full interface, adds {@link #watch}/{@link #unwatch}. */
    @CompileStatic
    static class Pin extends Pcf8575Minimal.Pin {

        private final Pcf8575Full chip

        protected Pin(Pcf8575Full chip, int n) {
            super(chip, n)
            this.chip = chip
        }

        /**
         * Subscribe to this pin's edge events (default trigger: {@link EdgeTrigger#CHANGE}).
         *
         * <p>At most one handler per pin at a time; a second call replaces the
         * first. Call {@link Pcf8575Full#onInterrupt} first to arm delivery.
         *
         * @param handler called with this pin when its state matches the trigger
         */
        void watch(Consumer<Pin> handler) {
            watch(handler, EdgeTrigger.CHANGE)
        }

        /**
         * Subscribe to this pin's edge events.
         *
         * @param handler called with this pin when its state matches {@code trigger}
         * @param trigger which edge(s) to fire on
         */
        void watch(Consumer<Pin> handler, EdgeTrigger trigger) {
            PinWatch w = new PinWatch()
            w.handler = handler
            w.trigger = trigger
            int port = n / 8
            int bit = n % 8
            w.lastState = ((chip.prev[port] >> bit) & 1) == 1
            chip.watches[n] = w
        }

        /** Unsubscribe this pin's handler. No-op if not registered. */
        void unwatch() {
            chip.watches[n] = null
        }
    }
}
