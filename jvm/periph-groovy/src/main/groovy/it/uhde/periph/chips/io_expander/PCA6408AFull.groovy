package it.uhde.periph.chips.io_expander

import groovy.transform.CompileStatic
import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.RegisterConnection

import java.util.function.Consumer
import java.util.function.IntConsumer

/**
 * PCA6408A full interface — polarity inversion, whole-port direction, change-based
 * interrupts and raw register access on top of the minimal GPIO API.
 *
 * <p>Interrupt delivery uses the {@link InputPin} given to {@code onInterrupt}
 * (or {@code connection.intPin()}, falling edge); without one a 5&nbsp;ms polling
 * thread is used. Per-pin {@link Pin#watch} starts delivery on first use. The
 * chip has no interrupt-status register: the driver derives the changed mask by
 * comparing INPUT reads.
 */
@CompileStatic
class PCA6408AFull extends PCA6408AMinimal {

    protected volatile IntConsumer callback
    protected InputPin intPinUsed
    protected Thread pollThread
    protected volatile boolean polling
    protected boolean active
    protected volatile int level
    protected final PinWatch[] watches = new PinWatch[8]

    /**
     * Fixed edge handler, reused across onEdge()/offEdge() calls — a fresh
     * Closure per call would not equal the one passed to onEdge() earlier, so
     * offEdge() could silently fail to remove it.
     */
    protected final EdgeHandler edgeHandler = { -> handleEdge() } as EdgeHandler

    protected static final class PinWatch {
        Consumer<Pin> handler
        EdgeTrigger trigger
    }

    /**
     * Construct the Full driver.
     *
     * @param connection I²C or SMBus (no PEC) register connection bound to the device address
     * @param addr       7-bit I²C address ({@code 0x20} or {@code 0x21})
     */
    PCA6408AFull(RegisterConnection connection, int addr = 0x20) {
        super(connection, addr)
        this.@level = this.@lastInput
    }

    /**
     * Return a Full {@link Pin} proxy for pin {@code n} (0–7).
     *
     * @param n pin index (0–7)
     * @return Pin proxy with {@link Pin#watch watch}/{@link Pin#unwatch unwatch} support
     */
    @Override
    Pin pin(int n) {
        return new Pin(this, n & 7)
    }

    /**
     * Configure input polarity inversion (POLARITY ← mask).
     *
     * @param mask 8-bit mask; bit n = 1 inverts the INPUT bit of Pn
     */
    void configurePolarity(int mask) {
        this.@polarity = mask & 0xFF
        writeReg(REG_POLARITY, this.@polarity)
        this.@lastInput = readReg(REG_INPUT)
    }

    /**
     * Return the shadow CONFIG register.
     *
     * @return 8-bit direction mask (1 = input)
     */
    int getDirection() {
        return this.@direction
    }

    /**
     * Write CONFIG in one transaction (1 = input, 0 = output). OUTPUT is written
     * first so pins turning into outputs never glitch; the cached input value is
     * refreshed afterwards to drop the false interrupt an output→input change can
     * raise.
     *
     * @param mask 8-bit direction mask; bit n = 1 makes Pn an input
     */
    void setDirection(int mask) {
        writeReg(REG_OUTPUT, this.@shadow)
        this.@direction = mask & 0xFF
        writeReg(REG_CONFIG, this.@direction)
        this.@lastInput = readReg(REG_INPUT)
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
     * the input cache. The RESET pin is external and not driven by the driver.
     */
    void reset() {
        offInterrupt()
        writeReg(REG_OUTPUT, 0xFF)
        writeReg(REG_POLARITY, 0x00)
        writeReg(REG_CONFIG, 0xFF)
        this.@shadow = 0xFF
        this.@polarity = 0x00
        this.@direction = 0xFF
        this.@lastInput = readReg(REG_INPUT)
        this.@level = this.@lastInput
    }

    /**
     * Subscribe to INT assertions (falling edge). Falls back to a 5&nbsp;ms
     * polling thread if no {@link InputPin} is available.
     *
     * @param callback called with the mask of input pins whose level changed
     * @param intPin   overrides {@code connection.intPin()}, or {@code null}
     */
    synchronized void onInterrupt(IntConsumer callback, InputPin intPin = null) {
        this.callback = callback
        startDelivery(intPin != null ? intPin : connection.intPin())
    }

    protected void startDelivery(InputPin pin) {
        stopDelivery()
        active = true
        intPinUsed = pin
        if (pin != null) {
            pin.onEdge(edgeHandler, EdgeTrigger.FALLING)
        } else {
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
            } as Runnable, 'pca6408a-poll')
            pollThread.daemon = true
            pollThread.start()
        }
    }

    protected void stopDelivery() {
        if (intPinUsed != null) {
            intPinUsed.offEdge(edgeHandler)
            intPinUsed = null
        }
        polling = false
        if (pollThread != null) {
            pollThread.interrupt()
            pollThread = null
        }
        active = false
    }

    /** Unsubscribe and stop delivery. */
    synchronized void offInterrupt() {
        stopDelivery()
        callback = null
    }

    protected void handleEdge() {
        try {
            int changed = pollInterrupt()
            if (changed == 0) return
            IntConsumer cb = callback
            if (cb != null) cb.accept(changed)
            for (int n = 0; n < 8; n++) {
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
            // bus error; wait for the next edge/tick rather than propagating
        }
    }

    /**
     * Read INPUT (clearing INT) and return the changed-pin mask: the XOR of the
     * new and the cached INPUT value, limited to pins configured as inputs. The
     * cache is updated.
     *
     * @return mask; bit n = 1 if input Pn changed
     */
    synchronized int pollInterrupt() {
        int now = readReg(REG_INPUT)
        int changed = (now ^ this.@lastInput) & this.@direction
        this.@lastInput = now
        this.@level = now
        return changed
    }

    // =========================================================================
    // Pin — extends PCA6408AMinimal.Pin with watch/unwatch support
    // =========================================================================

    /**
     * GPIO proxy for a single pin — full interface. Adds per-pin change watchers.
     * The chip has no pull-up resistors, so {@link #setPullUp} always rejects.
     */
    @CompileStatic
    static class Pin extends PCA6408AMinimal.Pin {

        private final PCA6408AFull full

        protected Pin(PCA6408AFull chip, int n) {
            super(chip, n)
            this.full = chip
        }

        /**
         * Pull-up control is not supported: the PCA6408A has no pull-up resistors.
         *
         * @param pullUp ignored
         * @throws UnsupportedOperationException always
         */
        void setPullUp(boolean pullUp) {
            throw new UnsupportedOperationException('PCA6408A has no pull-up resistors')
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
         * Subscribe to this pin's changes. Starts delivery (INT line or 5&nbsp;ms
         * polling) if none is running. The chip reports changes only; the
         * rising/falling filter is applied in software. At most one handler per
         * pin; a second call replaces the first.
         *
         * @param handler called with this pin when its state matches {@code trigger}
         * @param trigger which edge(s) to fire on
         * @throws IllegalStateException if the pin is an output (outputs never raise INT)
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
                if (!full.active) {
                    full.callback = null
                    full.startDelivery(full.connection.intPin())
                }
            }
        }

        /** Unsubscribe this pin's handler. No-op if not registered. */
        void unwatch() {
            full.watches[n] = null
        }
    }
}
