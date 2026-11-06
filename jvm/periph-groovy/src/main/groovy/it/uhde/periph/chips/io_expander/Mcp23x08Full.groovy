package it.uhde.periph.chips.io_expander

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin

import java.util.function.Consumer
import java.util.function.IntConsumer

/**
 * Shared Full-stage implementation of the MCP23008 / MCP23S08 drivers: pull-ups,
 * polarity inversion, interrupt-on-change and raw register access. Use
 * {@link Mcp23008Full} (I²C) or {@link Mcp23S08Full} (SPI).
 *
 * <p>Interrupt delivery uses the {@link InputPin} given to {@code onInterrupt}
 * (or {@code connection.intPin()}); without one a 5&nbsp;ms polling thread
 * calls the interrupt handler. Per-pin {@link Pin#watch} arms the pin in GPINTEN
 * and starts delivery on first use.
 */
@CompileStatic
class Mcp23x08Full extends Mcp23x08Minimal {

    protected int pullup  = 0x00
    protected int gpinten = 0x00
    protected int iocon
    protected boolean activeHigh = false

    protected volatile IntConsumer callback
    protected InputPin intPinUsed
    protected Thread pollThread
    protected volatile boolean polling
    protected boolean active
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
     * @param bus        register access for the chip's serial interface
     * @param connection the underlying connection (for its INT pin)
     */
    protected Mcp23x08Full(Bus bus, Connection connection) {
        super(bus, connection)
        this.iocon = bus.baseIocon()
    }

    /**
     * Return a Full {@link Pin} proxy for pin {@code n} (0–7).
     *
     * @param n pin index (0–7)
     * @return Pin proxy with pull-up and {@link Pin#watch watch}/{@link Pin#unwatch unwatch} support
     */
    @Override
    Pin pin(int n) {
        return new Pin(this, n & 7)
    }

    /**
     * Enable/disable per-pin 100 kΩ pull-ups (GPPU ← mask). Only effective on inputs.
     *
     * @param mask 8-bit mask; bit n = 1 enables the pull-up on GPn
     */
    void configurePullup(int mask) {
        pullup = mask & 0xFF
        bus.write(REG_GPPU, pullup)
    }

    /**
     * Configure input polarity inversion (IPOL ← mask).
     *
     * @param mask 8-bit mask; bit n = 1 inverts the GPIO read of GPn
     */
    void configurePolarity(int mask) {
        bus.write(REG_IPOL, mask & 0xFF)
    }

    /**
     * Set DEFVAL for default-compare interrupts.
     *
     * @param mask 8-bit default compare value
     */
    void setDefaultValue(int mask) {
        bus.write(REG_DEFVAL, mask & 0xFF)
    }

    /**
     * Arm interrupt-on-change pins (GPINTEN ← mask). Only inputs can interrupt.
     *
     * @param mask 8-bit mask; bit n = 1 arms GPn
     */
    void enablePinInterrupt(int mask) {
        gpinten = mask & 0xFF
        bus.write(REG_GPINTEN, gpinten)
    }

    /**
     * Configure the INT output (IOCON ODR / INTPOL).
     *
     * @param openDrain  {@code true} for an open-drain INT (overrides {@code activeHigh})
     * @param activeHigh {@code true} for active-high when push-pull
     */
    void configureIntPin(boolean openDrain = false, boolean activeHigh = false) {
        this.activeHigh = activeHigh && !openDrain
        iocon &= ~(IOCON_ODR | IOCON_INTPOL)
        if (openDrain) iocon |= IOCON_ODR
        else if (activeHigh) iocon |= IOCON_INTPOL
        bus.write(REG_IOCON, iocon)
    }

    /**
     * Enable or disable sequential register access (IOCON SEQOP). The driver
     * itself never relies on a value other than enabled.
     *
     * @param enabled {@code true} (default, auto-increment) or {@code false}
     */
    void setSequential(boolean enabled) {
        iocon &= ~IOCON_SEQOP
        if (!enabled) iocon |= IOCON_SEQOP
        bus.write(REG_IOCON, iocon)
    }

    /**
     * Raw register read (debug / escape hatch).
     *
     * @param reg register address 0x00–0x0A
     * @return register value
     */
    int readRegister(int reg) {
        return bus.read(reg)
    }

    /**
     * Raw register write; the IODIR / OLAT / IOCON / GPPU / GPINTEN shadows are kept in sync.
     *
     * @param reg   register address 0x00–0x0A
     * @param value 8-bit value
     */
    void writeRegister(int reg, int value) {
        int v = value & 0xFF
        switch (reg) {
            case REG_IODIR:   direction = v; break
            case REG_OLAT:    shadow = v; break
            case REG_IOCON:   iocon = v; break
            case REG_GPPU:    pullup = v; break
            case REG_GPINTEN: gpinten = v; break
            default: break
        }
        bus.write(reg, v)
    }

    /**
     * Rewrite every register to its reset value and clear the shadows. The chip
     * has no reset command (RESET is an external pin). On the MCP23S08 IOCON is
     * rewritten to HAEN (not 0) so addressed access keeps working.
     */
    void reset() {
        offInterrupt()
        [REG_IPOL, REG_GPINTEN, REG_DEFVAL, REG_INTCON, REG_GPPU, REG_OLAT].each { int reg ->
            bus.write(reg, 0x00)
        }
        bus.write(REG_IODIR, 0xFF)
        iocon = bus.baseIocon()
        bus.write(REG_IOCON, iocon)
        shadow = 0x00
        direction = 0xFF
        pullup = 0x00
        gpinten = 0x00
        activeHigh = false
    }

    /**
     * Subscribe to INT assertions. Every pin armed via {@link #enablePinInterrupt}
     * or {@link Pin#watch} is used. Falls back to a 5&nbsp;ms polling thread if no
     * {@link InputPin} is available.
     *
     * @param callback         called with the INTF mask of the pins that caused the interrupt
     * @param intPin           overrides {@code connection.intPin()}, or {@code null}
     * @param compareToDefault {@code true} to compare against DEFVAL (INTCON = armed pins),
     *                         {@code false} to compare against the previous pin value
     * @throws IllegalStateException if no pin has been armed
     */
    synchronized void onInterrupt(IntConsumer callback, InputPin intPin = null, boolean compareToDefault = false) {
        if (gpinten == 0) throw new IllegalStateException('no pins armed: call enablePinInterrupt() first')
        this.callback = callback
        bus.write(REG_INTCON, compareToDefault ? gpinten : 0x00)
        startDelivery(intPin != null ? intPin : connection.intPin())
    }

    protected void startDelivery(InputPin pin) {
        stopDelivery()
        active = true
        intPinUsed = pin
        if (pin != null) {
            pin.onEdge(edgeHandler, activeHigh ? EdgeTrigger.RISING : EdgeTrigger.FALLING)
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
            } as Runnable, 'mcp23x08-poll')
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

    /** Unsubscribe and stop delivery (GPINTEN is left untouched). */
    synchronized void offInterrupt() {
        stopDelivery()
        callback = null
    }

    protected void handleEdge() {
        try {
            int status = bus.read(REG_INTF)
            if (status == 0) return
            int captured = bus.read(REG_INTCAP) // clears the interrupt
            IntConsumer cb = callback
            if (cb != null) cb.accept(status)
            for (int n = 0; n < 8; n++) {
                if ((status & (1 << n)) == 0) continue
                PinWatch w = watches[n]
                if (w == null || w.handler == null) continue
                boolean high = ((captured >> n) & 1) == 1
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
     * Read INTF, then INTCAP (clearing the interrupt). The captured port value
     * is discarded — use {@link #readCapture()}.
     *
     * @return INTF mask; bit n = 1 if GPn caused the interrupt
     */
    int pollInterrupt() {
        int flags = bus.read(REG_INTF)
        bus.read(REG_INTCAP)
        return flags
    }

    /**
     * Read INTCAP: the port state captured at the interrupt. Also clears the interrupt.
     *
     * @return 8-bit captured port value
     */
    int readCapture() {
        return bus.read(REG_INTCAP)
    }

    // =========================================================================
    // Pin — extends Mcp23x08Minimal.Pin with pull-up and watch/unwatch support
    // =========================================================================

    /**
     * GPIO proxy for a single pin — full interface. Adds pull-up control and
     * per-pin change watchers.
     */
    @CompileStatic
    static class Pin extends Mcp23x08Minimal.Pin {

        private final Mcp23x08Full full

        protected Pin(Mcp23x08Full chip, int n) {
            super(chip, n)
            this.full = chip
        }

        /**
         * Set or clear the pull-up for this pin (its GPPU bit). Only
         * electrically active when the pin is an input.
         *
         * @param pullUp {@code true} to enable the 100 kΩ pull-up
         */
        void setPullUp(boolean pullUp) {
            int mask = full.pullup
            if (pullUp) mask |= (1 << n)
            else        mask &= ~(1 << n)
            full.configurePullup(mask)
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
         * Subscribe to this pin's changes. Arms the pin in GPINTEN and starts
         * delivery (INT line or 5&nbsp;ms polling) if none is running. The chip
         * reports changes only; the rising/falling filter is applied in software.
         * At most one handler per pin; a second call replaces the first.
         *
         * @param handler called with this pin when its state matches {@code trigger}
         * @param trigger which edge(s) to fire on
         * @throws IllegalStateException if the pin is an output (outputs never raise INT)
         */
        void watch(Consumer<Pin> handler, EdgeTrigger trigger) {
            if (((full.direction >> n) & 1) == 0) {
                throw new IllegalStateException('cannot watch an output pin')
            }
            PinWatch w = new PinWatch()
            w.handler = handler
            w.trigger = trigger
            full.watches[n] = w
            full.enablePinInterrupt(full.gpinten | (1 << n))
            synchronized (full) {
                if (!full.active) {
                    full.callback = null
                    full.startDelivery(full.connection.intPin())
                }
            }
        }

        /** Unsubscribe this pin's handler and clear its GPINTEN bit. No-op if not registered. */
        void unwatch() {
            full.watches[n] = null
            full.enablePinInterrupt(full.gpinten & ~(1 << n))
        }
    }
}
