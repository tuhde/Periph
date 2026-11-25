package it.uhde.periph.chips.io_expander

import groovy.transform.CompileStatic
import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.RegisterConnection

import java.util.function.Consumer
import java.util.function.IntConsumer

/**
 * PCA6416A full interface — polarity inversion, whole-device direction, change-based
 * interrupts and raw register access on top of the minimal GPIO API.
 *
 * <p>Interrupt delivery uses the {@link InputPin} given to {@code onInterrupt}
 * (or {@code connection.intPin()}, falling edge); without one a 5&nbsp;ms polling
 * thread is used. Per-pin {@link Pin#watch} starts delivery on first use. The
 * chip has no interrupt-status register: the driver derives the changed mask by
 * comparing INPUT reads.
 */
@CompileStatic
class PCA6416AFull extends PCA6416AMinimal {

    protected volatile IntConsumer callback
    protected InputPin intPinUsed
    protected Thread pollThread
    protected volatile boolean polling
    protected boolean active
    protected volatile int level
    protected final PinWatch[] watches = new PinWatch[16]

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
    PCA6416AFull(RegisterConnection connection, int addr = 0x20) {
        super(connection, addr)
        this.@level = this.@lastInput
    }

    /**
     * Return a Full {@link Pin} proxy for pin {@code n} (0–15).
     *
     * @param n pin index (0–15)
     * @return Pin proxy with {@link Pin#watch watch}/{@link Pin#unwatch unwatch} support
     */
    @Override
    Pin pin(int n) {
        return new Pin(this, n & 15)
    }

    /**
     * Read both INPUT registers in one two-byte transaction.
     *
     * @return 16-bit value; bit n = pin n (bits 0–7 port 0, 8–15 port 1)
     */
    int readAll() {
        return read16(REG_INPUT)
    }

    /**
     * Write both OUTPUT registers in one two-byte transaction and update the shadow.
     *
     * @param value 16-bit output value; bit n = pin n
     */
    void writeAll(int value) {
        this.@shadow = value & 0xFFFF
        write16(REG_OUTPUT, this.@shadow)
    }

    /**
     * Configure input polarity inversion (POLARITY0/1 ← mask).
     *
     * @param mask 16-bit mask; bit n = 1 inverts the INPUT bit of pin n
     */
    void configurePolarity(int mask) {
        this.@polarity = mask & 0xFFFF
        write16(REG_POLARITY, this.@polarity)
        this.@lastInput = read16(REG_INPUT)
    }

    /**
     * Return the shadow CONFIG registers.
     *
     * @return 16-bit direction mask (1 = input)
     */
    int getDirection() {
        return this.@direction
    }

    /**
     * Write CONFIG0/1 in one two-byte transaction (1 = input, 0 = output). OUTPUT is
     * written first so pins turning into outputs never glitch; the cached input
     * value is refreshed afterwards to drop the false interrupt an output→input
     * change can raise.
     *
     * @param mask 16-bit direction mask; bit n = 1 makes pin n an input
     */
    void setDirection(int mask) {
        write16(REG_OUTPUT, this.@shadow)
        this.@direction = mask & 0xFFFF
        write16(REG_CONFIG, this.@direction)
        this.@lastInput = read16(REG_INPUT)
    }

    /**
     * Raw register read (debug / escape hatch).
     *
     * @param reg register address 0–7
     * @return register value
     * @throws IllegalArgumentException if reg is outside 0–7
     */
    int readRegister(int reg) {
        if (reg < 0 || reg > 7) throw new IllegalArgumentException('register must be 0-7')
        return readReg(reg)
    }

    /**
     * Raw register write (2–7); the OUTPUT / POLARITY / CONFIG shadows are kept in sync.
     *
     * @param reg   register address 2–7 (INPUT0/1 are read-only)
     * @param value 8-bit value
     * @throws IllegalArgumentException if reg is 0 or 1, or outside 2–7
     */
    void writeRegister(int reg, int value) {
        if (reg < 2 || reg > 7) throw new IllegalArgumentException('register must be 2-7 (INPUT is read-only)')
        value &= 0xFF
        int shift = 8 * (reg & 1)
        int keep = ~(0xFF << shift) & 0xFFFF
        if ((reg >> 1) == 1) this.@shadow = (this.@shadow & keep) | (value << shift)
        else if ((reg >> 1) == 2) this.@polarity = (this.@polarity & keep) | (value << shift)
        else this.@direction = (this.@direction & keep) | (value << shift)
        writeReg(reg, value)
    }

    /**
     * Write the reset values (OUTPUT 0xFF, POLARITY 0x00, CONFIG 0xFF, both ports) and
     * refresh the input cache. The RESET pin is external and not driven by the driver.
     */
    void reset() {
        offInterrupt()
        write16(REG_OUTPUT, 0xFFFF)
        write16(REG_POLARITY, 0x0000)
        write16(REG_CONFIG, 0xFFFF)
        this.@shadow = 0xFFFF
        this.@polarity = 0x0000
        this.@direction = 0xFFFF
        this.@lastInput = read16(REG_INPUT)
        this.@level = this.@lastInput
    }

    /**
     * Subscribe to INT assertions (falling edge). Falls back to a 5&nbsp;ms
     * polling thread if no {@link InputPin} is available.
     *
     * @param callback called with the 16-bit mask of input pins whose level changed
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
            } as Runnable, 'pca6416a-poll')
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
            for (int n = 0; n < 16; n++) {
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
     * Read both INPUT registers (clearing INT) and return the changed-pin mask: the XOR of the
     * new and the cached INPUT value, limited to pins configured as inputs. The
     * cache is updated.
     *
     * @return 16-bit mask; bit n = 1 if input pin n changed
     */
    synchronized int pollInterrupt() {
        int now = read16(REG_INPUT)
        int changed = (now ^ this.@lastInput) & this.@direction
        this.@lastInput = now
        this.@level = now
        return changed
    }

    // =========================================================================
    // Pin — extends PCA6416AMinimal.Pin with watch/unwatch support
    // =========================================================================

    /**
     * GPIO proxy for a single pin — full interface. Adds per-pin change watchers.
     * The chip has no pull-up resistors, so {@link #setPullUp} always rejects.
     */
    @CompileStatic
    static class Pin extends PCA6416AMinimal.Pin {

        private final PCA6416AFull full

        protected Pin(PCA6416AFull chip, int n) {
            super(chip, n)
            this.full = chip
        }

        /**
         * Pull-up control is not supported: the PCA6416A has no pull-up resistors.
         *
         * @param pullUp ignored
         * @throws UnsupportedOperationException always
         */
        void setPullUp(boolean pullUp) {
            throw new UnsupportedOperationException('PCA6416A has no pull-up resistors')
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
