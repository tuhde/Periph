package it.uhde.periph.chips.io_expander

import groovy.transform.CompileStatic
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.RegisterConnection

import java.util.function.Consumer
import java.util.function.IntConsumer

/**
 * PCA9536 full interface — polarity inversion, whole-port direction, polling-based
 * change detection and raw register access on top of the minimal GPIO API.
 *
 * <p>The chip has no interrupt output, so change detection is by polling:
 * {@link #pollChanges()} derives the changed mask by comparing INPUT reads, and
 * {@link #watchChanges} runs a polling thread (5&nbsp;ms by default) that reports
 * it. Per-pin {@link Pin#watch} starts the thread on first use.
 */
@CompileStatic
class PCA9536Full extends PCA9536Minimal {

    protected volatile IntConsumer callback
    protected Thread pollThread
    protected volatile boolean polling
    protected volatile long intervalMs = 5L
    protected volatile int level
    protected final PinWatch[] watches = new PinWatch[4]

    protected static final class PinWatch {
        Consumer<Pin> handler
        EdgeTrigger trigger
    }

    /**
     * Construct the Full driver.
     *
     * @param connection I²C or SMBus (no PEC) register connection bound to the device address
     * @param addr       7-bit I²C address (fixed {@code 0x41})
     */
    PCA9536Full(RegisterConnection connection, int addr = 0x41) {
        super(connection, addr)
        this.@level = this.@lastInput
    }

    /**
     * Return a Full {@link Pin} proxy for pin {@code n} (0–3).
     *
     * @param n pin index (0–3)
     * @return Pin proxy with {@link Pin#watch watch}/{@link Pin#unwatch unwatch} support
     */
    @Override
    Pin pin(int n) {
        return new Pin(this, n & 3)
    }

    /**
     * Configure input polarity inversion (POLARITY ← mask). The cached input value is
     * refreshed, so the polarity change itself is not reported by {@link #pollChanges()}.
     *
     * @param mask 4-bit mask; bit n = 1 inverts the INPUT bit of Pn
     */
    void configurePolarity(int mask) {
        this.@polarity = mask & MASK
        writeReg(REG_POLARITY, this.@polarity)
        this.@lastInput = readReg(REG_INPUT) & MASK
    }

    /**
     * Return the shadow CONFIG register.
     *
     * @return 4-bit direction mask (1 = input)
     */
    int getDirection() {
        return this.@direction & MASK
    }

    /**
     * Write CONFIG in one transaction (1 = input, 0 = output). OUTPUT is written
     * first so pins turning into outputs never glitch; the cached input value is
     * refreshed afterwards.
     *
     * @param mask 4-bit direction mask; bit n = 1 makes Pn an input
     */
    void setDirection(int mask) {
        writeReg(REG_OUTPUT, this.@shadow)
        this.@direction = (mask & MASK) | 0xF0
        writeReg(REG_CONFIG, this.@direction)
        this.@lastInput = readReg(REG_INPUT) & MASK
    }

    /**
     * Raw register read (debug / escape hatch).
     *
     * @param reg register address 0–3
     * @return register value
     * @throws IllegalArgumentException if reg is outside 0–3
     */
    int readRegister(int reg) {
        if (reg < 0 || reg > 3) throw new IllegalArgumentException('register must be 0-3')
        return readReg(reg)
    }

    /**
     * Raw register write (1–3); the OUTPUT / POLARITY / CONFIG shadows are kept in sync.
     *
     * @param reg   register address 1–3 (INPUT is read-only)
     * @param value 8-bit value
     * @throws IllegalArgumentException if reg is 0 or outside 1–3
     */
    void writeRegister(int reg, int value) {
        if (reg < 1 || reg > 3) throw new IllegalArgumentException('register must be 1-3 (INPUT is read-only)')
        value &= 0xFF
        if (reg == REG_OUTPUT) this.@shadow = value
        else if (reg == REG_POLARITY) this.@polarity = value
        else this.@direction = value
        writeReg(reg, value)
    }

    /**
     * Write the reset values (OUTPUT 0xFF, POLARITY 0x00, CONFIG 0xFF) and refresh
     * the input cache. The chip has no RESET pin; only a power cycle clears a lock-up.
     */
    void reset() {
        unwatchChanges()
        writeReg(REG_OUTPUT, 0xFF)
        writeReg(REG_POLARITY, 0x00)
        writeReg(REG_CONFIG, 0xFF)
        this.@shadow = 0xFF
        this.@polarity = 0x00
        this.@direction = 0xFF
        this.@lastInput = readReg(REG_INPUT) & MASK
        this.@level = this.@lastInput
    }

    /**
     * Start a polling thread (5&nbsp;ms) that reports input changes.
     *
     * @param callback called with the mask of input pins whose level changed
     */
    void watchChanges(IntConsumer callback) {
        watchChanges(callback, 0.005d)
    }

    /**
     * Start a polling thread that calls {@link #pollChanges()} every
     * {@code intervalS} seconds and invokes {@code callback} when the mask is
     * non-zero. {@link #unwatchChanges()} stops it.
     *
     * @param callback  called with the mask of input pins whose level changed (may be {@code null})
     * @param intervalS polling interval in seconds
     */
    synchronized void watchChanges(IntConsumer callback, double intervalS) {
        stopPolling()
        this.callback = callback
        this.intervalMs = Math.max(1L, Math.round(intervalS * 1000d))
        startPolling()
    }

    protected void startPolling() {
        polling = true
        pollThread = new Thread({
            while (polling) {
                dispatch()
                try {
                    Thread.sleep(intervalMs)
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        } as Runnable, 'pca9536-poll')
        pollThread.daemon = true
        pollThread.start()
    }

    protected void stopPolling() {
        polling = false
        if (pollThread != null) {
            pollThread.interrupt()
            pollThread = null
        }
    }

    /** Stop the polling thread started by {@link #watchChanges} (per-pin watchers stay registered). */
    synchronized void unwatchChanges() {
        stopPolling()
        callback = null
    }

    protected void dispatch() {
        try {
            int changed = pollChanges()
            if (changed == 0) return
            IntConsumer cb = callback
            if (cb != null) cb.accept(changed)
            for (int n = 0; n < 4; n++) {
                if ((changed & (1 << n)) == 0) continue
                PinWatch w = watches[n]
                if (w == null || w.handler == null) continue
                boolean high = ((level >> n) & 1) == 1
                boolean fire = w.trigger == EdgeTrigger.CHANGE ||
                    (w.trigger == EdgeTrigger.RISING && high) ||
                    (w.trigger == EdgeTrigger.FALLING && !high)
                if (fire) w.handler.accept(new Pin(this, n))
            }
        } catch (IOException ignored) {
            // bus error; wait for the next tick rather than propagating
        }
    }

    /**
     * Read INPUT and return the changed-pin mask (software edge detection; the
     * chip has no interrupt output): the XOR of the new and the cached INPUT
     * value, limited to pins configured as inputs. The cache is updated.
     *
     * @return mask; bit n = 1 if input Pn changed
     */
    synchronized int pollChanges() {
        int now = readReg(REG_INPUT) & MASK
        int changed = (now ^ this.@lastInput) & this.@direction & MASK
        this.@lastInput = now
        this.@level = now
        return changed
    }

    // =========================================================================
    // Pin — extends PCA9536Minimal.Pin with watch/unwatch support
    // =========================================================================

    /**
     * GPIO proxy for a single pin — full interface. Adds per-pin change watchers.
     * The weak pull-up is fixed in hardware, so {@link #setPullUp} always rejects.
     */
    @CompileStatic
    static class Pin extends PCA9536Minimal.Pin {

        private final PCA9536Full full

        protected Pin(PCA9536Full chip, int n) {
            super(chip, n)
            this.full = chip
        }

        /**
         * Pull-up control is not supported: the PCA9536's weak pull-up is fixed in hardware.
         *
         * @param pullUp ignored
         * @throws UnsupportedOperationException always
         */
        void setPullUp(boolean pullUp) {
            throw new UnsupportedOperationException('PCA9536 pull-up is fixed in hardware')
        }

        /**
         * Subscribe to this pin's changes (trigger {@link EdgeTrigger#CHANGE}).
         *
         * @param handler called with this pin when its state matches the trigger
         */
        void watch(Consumer<Pin> handler) {
            watch(handler, EdgeTrigger.CHANGE)
        }

        /**
         * Subscribe to this pin's changes. Starts the 5&nbsp;ms polling thread
         * if none is running. The chip reports changes only; the
         * rising/falling filter is applied in software. At most one handler per
         * pin; a second call replaces the first.
         *
         * @param handler called with this pin when its state matches {@code trigger}
         * @param trigger which edge(s) to fire on
         * @throws IllegalStateException if the pin is an output (outputs never report changes)
         */
        void watch(Consumer<Pin> handler, EdgeTrigger trigger) {
            if (((full.@direction >> n) & 1) == 0) {
                throw new IllegalStateException('cannot watch an output pin')
            }
            PinWatch w = new PinWatch()
            w.handler = handler
            w.trigger = trigger
            full.watches[n] = w
            synchronized (full) {
                if (full.pollThread == null) {
                    full.startPolling()
                }
            }
        }

        /** Unsubscribe this pin's handler. No-op if not registered. */
        void unwatch() {
            full.watches[n] = null
        }
    }
}
