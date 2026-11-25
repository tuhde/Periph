package it.uhde.periph.chips.io_expander;

import it.uhde.periph.connection.Connection;
import it.uhde.periph.connection.EdgeHandler;
import it.uhde.periph.connection.EdgeTrigger;
import it.uhde.periph.connection.InputPin;

import java.io.IOException;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Shared Full-stage implementation of the MCP23008 / MCP23S08 drivers: pull-ups,
 * polarity inversion, interrupt-on-change and raw register access. Use
 * {@link Mcp23008Full} (I²C) or {@link Mcp23S08Full} (SPI).
 *
 * <p>Interrupt delivery uses the {@link InputPin} given to {@code onInterrupt}
 * (or {@code connection.intPin()}); without one a 5&nbsp;ms polling thread calls
 * {@link #pollInterrupt()}. Per-pin {@link Pin#watch} arms the pin in GPINTEN and
 * starts delivery on first use.
 */
public class Mcp23x08Full extends Mcp23x08Minimal {

    private int pullup  = 0x00;
    private int gpinten = 0x00;
    private int iocon;
    private boolean activeHigh = false;

    private volatile IntConsumer callback;
    private InputPin intPinUsed;
    private Thread pollThread;
    private volatile boolean polling;
    private boolean active;
    private final PinWatch[] watches = new PinWatch[8];

    /**
     * Fixed edge handler, reused across onEdge()/offEdge() calls so offEdge()
     * can remove the exact instance that was registered.
     */
    private final EdgeHandler edgeHandler = this::handleEdge;

    private static final class PinWatch {
        Consumer<Pin> handler;
        EdgeTrigger trigger;
    }

    /**
     * Construct the Full driver.
     *
     * @param bus        register access for the chip's serial interface
     * @param connection the underlying connection (for its INT pin)
     * @throws IOException on bus error during init
     */
    protected Mcp23x08Full(Bus bus, Connection connection) throws IOException {
        super(bus, connection);
        this.iocon = bus.baseIocon();
    }

    /**
     * Return a Full {@link Pin} proxy for pin {@code n} (0–7).
     *
     * @param n pin index (0–7)
     * @return Pin proxy with pull-up and {@link Pin#watch watch}/{@link Pin#unwatch unwatch} support
     */
    @Override
    public Pin pin(int n) {
        return new Pin(this, n & 7);
    }

    /**
     * Enable/disable per-pin 100 kΩ pull-ups (GPPU ← mask). Only effective on inputs.
     *
     * @param mask 8-bit mask; bit n = 1 enables the pull-up on GPn
     * @throws IOException on bus error
     */
    public void configurePullup(int mask) throws IOException {
        pullup = mask & 0xFF;
        bus.write(REG_GPPU, pullup);
    }

    /**
     * Configure input polarity inversion (IPOL ← mask).
     *
     * @param mask 8-bit mask; bit n = 1 inverts the GPIO read of GPn
     * @throws IOException on bus error
     */
    public void configurePolarity(int mask) throws IOException {
        bus.write(REG_IPOL, mask & 0xFF);
    }

    /**
     * Set DEFVAL for default-compare interrupts.
     *
     * @param mask 8-bit default compare value
     * @throws IOException on bus error
     */
    public void setDefaultValue(int mask) throws IOException {
        bus.write(REG_DEFVAL, mask & 0xFF);
    }

    /**
     * Arm interrupt-on-change pins (GPINTEN ← mask). Only inputs can interrupt.
     *
     * @param mask 8-bit mask; bit n = 1 arms GPn
     * @throws IOException on bus error
     */
    public void enablePinInterrupt(int mask) throws IOException {
        gpinten = mask & 0xFF;
        bus.write(REG_GPINTEN, gpinten);
    }

    /**
     * Configure the INT output (IOCON ODR / INTPOL).
     *
     * @param openDrain  {@code true} for an open-drain INT (overrides {@code activeHigh})
     * @param activeHigh {@code true} for active-high when push-pull
     * @throws IOException on bus error
     */
    public void configureIntPin(boolean openDrain, boolean activeHigh) throws IOException {
        this.activeHigh = activeHigh && !openDrain;
        iocon &= ~(IOCON_ODR | IOCON_INTPOL);
        if (openDrain) iocon |= IOCON_ODR;
        else if (activeHigh) iocon |= IOCON_INTPOL;
        bus.write(REG_IOCON, iocon);
    }

    /**
     * Enable or disable sequential register access (IOCON SEQOP). The driver
     * itself never relies on a value other than enabled.
     *
     * @param enabled {@code true} (default, auto-increment) or {@code false}
     * @throws IOException on bus error
     */
    public void setSequential(boolean enabled) throws IOException {
        iocon &= ~IOCON_SEQOP;
        if (!enabled) iocon |= IOCON_SEQOP;
        bus.write(REG_IOCON, iocon);
    }

    /**
     * Raw register read (debug / escape hatch).
     *
     * @param reg register address 0x00–0x0A
     * @return register value
     * @throws IOException on bus error
     */
    public int readRegister(int reg) throws IOException {
        return bus.read(reg);
    }

    /**
     * Raw register write; the IODIR / OLAT / IOCON / GPPU / GPINTEN shadows are kept in sync.
     *
     * @param reg   register address 0x00–0x0A
     * @param value 8-bit value
     * @throws IOException on bus error
     */
    public void writeRegister(int reg, int value) throws IOException {
        value &= 0xFF;
        switch (reg) {
            case REG_IODIR:   direction = value; break;
            case REG_OLAT:    shadow = value; break;
            case REG_IOCON:   iocon = value; break;
            case REG_GPPU:    pullup = value; break;
            case REG_GPINTEN: gpinten = value; break;
            default: break;
        }
        bus.write(reg, value);
    }

    /**
     * Rewrite every register to its reset value and clear the shadows. The chip
     * has no reset command (RESET is an external pin). On the MCP23S08 IOCON is
     * rewritten to HAEN (not 0) so addressed access keeps working.
     *
     * @throws IOException on bus error
     */
    public void reset() throws IOException {
        offInterrupt();
        for (int reg : new int[]{REG_IPOL, REG_GPINTEN, REG_DEFVAL, REG_INTCON, REG_GPPU, REG_OLAT}) {
            bus.write(reg, 0x00);
        }
        bus.write(REG_IODIR, 0xFF);
        iocon = bus.baseIocon();
        bus.write(REG_IOCON, iocon);
        shadow = 0x00;
        direction = 0xFF;
        pullup = 0x00;
        gpinten = 0x00;
        activeHigh = false;
    }

    /**
     * Subscribe to INT assertions using {@code connection.intPin()} and
     * interrupt-on-change comparison.
     *
     * @param callback called with the INTF mask of the pins that caused the interrupt
     * @throws IOException on bus error
     */
    public void onInterrupt(IntConsumer callback) throws IOException {
        onInterrupt(callback, null, false);
    }

    /**
     * Subscribe to INT assertions with interrupt-on-change comparison.
     *
     * @param callback called with the INTF mask of the pins that caused the interrupt
     * @param intPin   overrides {@code connection.intPin()}, or {@code null}
     * @throws IOException on bus error
     */
    public void onInterrupt(IntConsumer callback, InputPin intPin) throws IOException {
        onInterrupt(callback, intPin, false);
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
     * @throws IOException           on bus error
     * @throws IllegalStateException if no pin has been armed
     */
    public synchronized void onInterrupt(IntConsumer callback, InputPin intPin, boolean compareToDefault)
            throws IOException {
        if (gpinten == 0) throw new IllegalStateException("no pins armed: call enablePinInterrupt() first");
        this.callback = callback;
        bus.write(REG_INTCON, compareToDefault ? gpinten : 0x00);
        startDelivery(intPin != null ? intPin : connection.intPin());
    }

    private void startDelivery(InputPin pin) {
        stopDelivery();
        active = true;
        intPinUsed = pin;
        if (pin != null) {
            pin.onEdge(edgeHandler, activeHigh ? EdgeTrigger.RISING : EdgeTrigger.FALLING);
        } else {
            polling = true;
            pollThread = new Thread(() -> {
                while (polling) {
                    handleEdge();
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }, "mcp23x08-poll");
            pollThread.setDaemon(true);
            pollThread.start();
        }
    }

    private void stopDelivery() {
        if (intPinUsed != null) {
            intPinUsed.offEdge(edgeHandler);
            intPinUsed = null;
        }
        polling = false;
        if (pollThread != null) {
            pollThread.interrupt();
            pollThread = null;
        }
        active = false;
    }

    /** Unsubscribe and stop delivery (GPINTEN is left untouched). */
    public synchronized void offInterrupt() {
        stopDelivery();
        callback = null;
    }

    private void handleEdge() {
        try {
            int status = bus.read(REG_INTF);
            if (status == 0) return;
            int captured = bus.read(REG_INTCAP); // clears the interrupt
            IntConsumer cb = callback;
            if (cb != null) cb.accept(status);
            for (int n = 0; n < 8; n++) {
                if ((status & (1 << n)) == 0) continue;
                PinWatch w = watches[n];
                if (w == null || w.handler == null) continue;
                boolean high = ((captured >> n) & 1) == 1;
                boolean fire = w.trigger == EdgeTrigger.CHANGE
                    || (w.trigger == EdgeTrigger.RISING && high)
                    || (w.trigger == EdgeTrigger.FALLING && !high);
                if (fire) w.handler.accept(new Pin(this, n));
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
     * @throws IOException on bus error
     */
    public int pollInterrupt() throws IOException {
        int flags = bus.read(REG_INTF);
        bus.read(REG_INTCAP);
        return flags;
    }

    /**
     * Read INTCAP: the port state captured at the interrupt. Also clears the interrupt.
     *
     * @return 8-bit captured port value
     * @throws IOException on bus error
     */
    public int readCapture() throws IOException {
        return bus.read(REG_INTCAP);
    }

    // =========================================================================
    // Pin — extends Mcp23x08Minimal.Pin with pull-up and watch/unwatch support
    // =========================================================================

    /**
     * GPIO proxy for a single pin — full interface. Adds pull-up control and
     * per-pin change watchers.
     */
    public static class Pin extends Mcp23x08Minimal.Pin {

        private final Mcp23x08Full full;

        protected Pin(Mcp23x08Full chip, int n) {
            super(chip, n);
            this.full = chip;
        }

        /**
         * Set or clear the pull-up for this pin (its GPPU bit). Only
         * electrically active when the pin is an input.
         *
         * @param pullUp {@code true} to enable the 100 kΩ pull-up
         * @throws IOException on bus error
         */
        public void setPullUp(boolean pullUp) throws IOException {
            int mask = full.pullup;
            if (pullUp) mask |= (1 << n);
            else        mask &= ~(1 << n);
            full.configurePullup(mask);
        }

        /**
         * Subscribe to this pin's changes (trigger {@link EdgeTrigger#CHANGE}).
         *
         * @param handler called with this pin when its state matches the trigger
         * @throws IOException           on bus error
         * @throws IllegalStateException if the pin is an output
         */
        public void watch(Consumer<Pin> handler) throws IOException {
            watch(handler, EdgeTrigger.CHANGE);
        }

        /**
         * Subscribe to this pin's changes. Arms the pin in GPINTEN and starts
         * delivery (INT line or 5&nbsp;ms polling) if none is running. The chip
         * reports changes only; the rising/falling filter is applied in software.
         * At most one handler per pin; a second call replaces the first.
         *
         * @param handler called with this pin when its state matches {@code trigger}
         * @param trigger which edge(s) to fire on
         * @throws IOException           on bus error
         * @throws IllegalStateException if the pin is an output (outputs never raise INT)
         */
        public void watch(Consumer<Pin> handler, EdgeTrigger trigger) throws IOException {
            if (((full.direction >> n) & 1) == 0) {
                throw new IllegalStateException("cannot watch an output pin");
            }
            PinWatch w = new PinWatch();
            w.handler = handler;
            w.trigger = trigger;
            full.watches[n] = w;
            full.enablePinInterrupt(full.gpinten | (1 << n));
            synchronized (full) {
                if (!full.active) {
                    full.callback = null;
                    full.startDelivery(full.connection.intPin());
                }
            }
        }

        /**
         * Unsubscribe this pin's handler and clear its GPINTEN bit. No-op if not registered.
         *
         * @throws IOException on bus error
         */
        public void unwatch() throws IOException {
            full.watches[n] = null;
            full.enablePinInterrupt(full.gpinten & ~(1 << n));
        }
    }
}
