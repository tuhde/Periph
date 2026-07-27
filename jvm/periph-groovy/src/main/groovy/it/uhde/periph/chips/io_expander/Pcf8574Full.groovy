package it.uhde.periph.chips.io_expander

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin

import java.util.function.Consumer
import java.util.function.IntConsumer

/**
 * PCF8574 full driver — extends {@link Pcf8574Minimal} with interrupt-on-change support.
 *
 * <p>{@link #onInterrupt(IntConsumer)} subscribes to the chip's INT line; delivery
 * uses {@code connection.intPin()} (or an explicit override) if wired, otherwise
 * falls back to a 5&nbsp;ms polling thread automatically. {@link #pollInterrupt()}
 * reads current pin states and returns the bitmask of changed pins. Per-pin
 * {@link Pin#watch(Consumer)} is also available.
 */
@CompileStatic
class Pcf8574Full extends Pcf8574Minimal {

    /** Port value from the previous read — used to detect changes. */
    private int prev

    /** Callback invoked with the changed-pin bitmask on any input change. */
    private volatile IntConsumer callback

    /** INT-line pin currently armed, or {@code null} if polling. */
    private InputPin intPin

    /** {@code true} while the polling-fallback thread is running. */
    private volatile boolean polling = false

    /** Background daemon thread used only when no {@link InputPin} is available. */
    private Thread pollThread

    /** Per-pin watchers, indexed by pin number (0–7). */
    private final PinWatch[] watches = new PinWatch[8]

    /**
     * Fixed edge handler instance, reused across onEdge()/offEdge() calls — a
     * fresh Closure per call would not be guaranteed to be identical/equal to
     * the one passed to onEdge() earlier, so offEdge() could silently fail to
     * remove it.
     */
    private final EdgeHandler edgeHandler = { -> handleEdge() } as EdgeHandler

    private static final class PinWatch {
        Consumer<Pin> handler
        EdgeTrigger trigger
        boolean lastState
    }

    /**
     * Construct the full driver and initialise all pins to input mode.
     *
     * @param connection I²C connection bound to the PCF8574 device address
     */
    Pcf8574Full(Connection connection) {
        super(connection)
        prev = readPort()
    }

    /**
     * Return a Full {@link Pin} proxy for pin {@code n} (0–7).
     *
     * @param n pin index (0 = P0, 7 = P7)
     * @return Full Pin proxy with {@link Pin#watch}/{@link Pin#unwatch} support
     */
    @Override
    Pin pin(int n) {
        return new Pin(this, n)
    }

    // -------------------------------------------------------------------------
    // Interrupt API
    // -------------------------------------------------------------------------

    /**
     * Subscribe to INT assertions using {@code connection.intPin()} (or a 5&nbsp;ms
     * polling thread if none is wired).
     *
     * @param callback called with the changed-pin bitmask on any input change
     */
    void onInterrupt(IntConsumer callback) {
        onInterrupt(callback, connection.intPin())
    }

    /**
     * Subscribe to INT assertions, overriding which {@link InputPin} delivers edges.
     *
     * @param callback called with the changed-pin bitmask on any input change
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
        } as Runnable, 'pcf8574-poll')
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
            for (int n = 0; n < 8; n++) {
                if ((changed & (1 << n)) == 0) continue
                PinWatch w = watches[n]
                if (w == null || w.handler == null) continue
                boolean current = ((prev >> n) & 1) == 1
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
     * Read current pin states and return the bitmask of pins that changed since last read.
     *
     * <p>Reading the port also clears the chip's INT output. The previous-read value
     * is updated each time this method is called.
     *
     * @return 8-bit bitmask; bit n = 1 if pin n changed since the previous read
     */
    int pollInterrupt() {
        int current = readPort()
        int changed = current ^ prev
        prev = current
        return changed
    }

    // =========================================================================
    // Pin — extends Pcf8574Minimal.Pin with watch/unwatch support
    // =========================================================================

    /** GPIO proxy for a single PCF8574 pin — full interface, adds {@link #watch}/{@link #unwatch}. */
    @CompileStatic
    static class Pin extends Pcf8574Minimal.Pin {

        private final Pcf8574Full chip

        protected Pin(Pcf8574Full chip, int n) {
            super(chip, n)
            this.chip = chip
        }

        /**
         * Subscribe to this pin's edge events (default trigger: {@link EdgeTrigger#CHANGE}).
         *
         * <p>At most one handler per pin at a time; a second call replaces the
         * first. Call {@link Pcf8574Full#onInterrupt} first to arm delivery.
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
            w.lastState = ((chip.prev >> n) & 1) == 1
            chip.watches[n] = w
        }

        /** Unsubscribe this pin's handler. No-op if not registered. */
        void unwatch() {
            chip.watches[n] = null
        }
    }
}
