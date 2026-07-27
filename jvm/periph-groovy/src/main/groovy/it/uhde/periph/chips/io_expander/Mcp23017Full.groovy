package it.uhde.periph.chips.io_expander

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin

import java.util.function.Consumer
import java.util.function.IntConsumer

/**
 * MCP23017 full interface — extends {@link Mcp23017Minimal} with pull-up
 * configuration, polarity inversion, and interrupt support.
 *
 * <p>Level 3: the chip has two independent INT lines (INTA/INTB), one per
 * port. {@link #onInterrupt} / {@link #offInterrupt} subscribe/unsubscribe
 * both ports at once (delivered via one shared {@link InputPin} — wire
 * IOCON.MIRROR or external wiring so a single physical line carries both);
 * {@link #onInterruptPort} / {@link #offInterruptPort} target one port with
 * its own {@link InputPin}. {@link #pollInterrupt} reads and clears INTF;
 * {@link #readCapture} reads INTCAP (the latched pin state) separately.
 * Per-pin {@link Pin#watch(Consumer)} is also available.
 */
@CompileStatic
class Mcp23017Full extends Mcp23017Minimal {

    /** Called with (port, status) when either port's INT fires. */
    @FunctionalInterface
    static interface PortStatusHandler {
        void accept(int port, int status)
    }

    private static final int REG_GPINTENA = 0x04
    private static final int REG_GPINTENB = 0x05
    private static final int REG_DEFVALA  = 0x06
    private static final int REG_DEFVALB  = 0x07
    private static final int REG_INTCONA  = 0x08
    private static final int REG_INTCONB  = 0x09
    private static final int REG_IOCON    = 0x0A
    private static final int REG_INTFA    = 0x0E
    private static final int REG_INTFB    = 0x0F
    private static final int REG_INTCAPA  = 0x10
    private static final int REG_INTCAPB  = 0x11

    private volatile PortStatusHandler callbackBoth
    private final IntConsumer[] callbackPort = new IntConsumer[2]
    private final InputPin[] intPinUsed = new InputPin[2]
    private final Thread[] pollThread = new Thread[2]
    private final boolean[] polling = new boolean[2]
    private final PinWatch[][] watches = new PinWatch[2][8]

    /**
     * Fixed per-port edge handler instances, reused across onEdge()/offEdge()
     * calls — a fresh Closure per armPort() call would not be guaranteed to
     * equal the one passed to onEdge() earlier, so offEdge() could silently
     * fail to remove it.
     */
    private final EdgeHandler[] edgeHandlers = [
        { -> handleEdge(0) } as EdgeHandler,
        { -> handleEdge(1) } as EdgeHandler
    ]

    private static final class PinWatch {
        Consumer<Pin> handler
        EdgeTrigger trigger
        boolean lastState
    }

    /**
     * Construct the full driver.
     *
     * @param connection I²C connection bound to the device address
     * @param addr       7-bit I²C address
     */
    Mcp23017Full(Connection connection, int addr = 0x20) {
        super(connection, addr)
    }

    /**
     * Return a Full {@link Pin} proxy for pin {@code n} (0–15).
     *
     * @param n pin index (0–15)
     * @return Pin proxy with {@link Pin#watch}/{@link Pin#unwatch} support
     */
    @Override
    Pin pin(int n) {
        return new Pin(this, n)
    }

    /**
     * Enable/disable per-pin 100 kΩ pull-ups on a port.
     *
     * <p>Pull-ups are only electrically active on pins configured as inputs.
     * Enabling them on output pins has no hardware effect.
     *
     * @param port 0 = PORTA (GPPUA), 1 = PORTB (GPPUB)
     * @param mask 8-bit mask; bit n = 1 enables pull-up on pin n
     */
    void configurePullup(int port, int mask) {
        writeReg(REG_GPPUA + (port & 1), mask & 0xFF)
    }

    /**
     * Configure input polarity inversion on a port.
     *
     * @param port 0 = PORTA (IPOLA), 1 = PORTB (IPOLB)
     * @param mask 8-bit mask; bit n = 1 inverts the GPIO read for pin n
     */
    void configurePolarity(int port, int mask) {
        writeReg(REG_IPOLA + (port & 1), mask & 0xFF)
    }

    /**
     * Set DEFVAL register for default-compare interrupt mode.
     *
     * @param port 0 = PORTA (DEFVALA), 1 = PORTB (DEFVALB)
     * @param mask 8-bit default compare value
     */
    void setDefaultValue(int port, int mask) {
        writeReg(REG_DEFVALA + (port & 1), mask & 0xFF)
    }

    private void armPort(int port, InputPin intPin) {
        port &= 1
        writeReg(REG_INTCONA + port, 0x00) // interrupt-on-change mode
        writeReg(REG_GPINTENA + port, 0xFF)
        intPinUsed[port] = intPin
        stopPolling(port)
        if (intPin != null) {
            intPin.onEdge(edgeHandlers[port], EdgeTrigger.FALLING)
        } else {
            startPolling(port)
        }
    }

    /**
     * Subscribe to INT assertions on both ports.
     *
     * <p>The common case: both INT lines share one physical GPIO, either via
     * IOCON.MIRROR ({@code mirror = true}) or external wiring. Wires
     * {@code intPin} (or {@code connection.intPin()} if {@code null}) to poll
     * both ports on every edge. Falls back to two independent 5&nbsp;ms
     * polling threads if no {@link InputPin} is available.
     *
     * @param callback called with (port, status) on any input change
     * @param intPin   overrides {@code connection.intPin()}, or {@code null}
     * @param mirror   set IOCON.MIRROR so either port's interrupt activates both INTA and INTB
     */
    void onInterrupt(PortStatusHandler callback, InputPin intPin = null, boolean mirror = false) {
        this.callbackBoth = callback
        int iocon = readReg(REG_IOCON)
        writeReg(REG_IOCON, mirror ? (iocon | (1 << 6)) : iocon)
        InputPin pin = intPin != null ? intPin : connection.intPin()
        armPort(0, pin)
        armPort(1, pin)
    }

    /**
     * Subscribe to INT assertions on a single port.
     *
     * <p>Use this (with a dedicated {@link InputPin} per call) when INTA and
     * INTB are wired to two separate GPIOs.
     *
     * @param port     0 = PORTA (INTA), 1 = PORTB (INTB)
     * @param callback called with the port's INTF flag mask
     * @param intPin   overrides {@code connection.intPin()}, or {@code null}
     */
    void onInterruptPort(int port, IntConsumer callback, InputPin intPin = connection.intPin()) {
        port &= 1
        this.callbackPort[port] = callback
        armPort(port, intPin)
    }

    /** Unsubscribe and stop delivery on both ports. */
    void offInterrupt() {
        offInterruptPort(0)
        offInterruptPort(1)
        callbackBoth = null
    }

    /**
     * Unsubscribe and stop delivery on a single port.
     *
     * @param port 0 = PORTA, 1 = PORTB
     */
    void offInterruptPort(int port) {
        port &= 1
        writeReg(REG_GPINTENA + port, 0x00)
        if (intPinUsed[port] != null) {
            intPinUsed[port].offEdge(edgeHandlers[port])
            intPinUsed[port] = null
        }
        stopPolling(port)
        callbackPort[port] = null
    }

    private void startPolling(int port) {
        polling[port] = true
        pollThread[port] = new Thread({
            while (polling[port]) {
                handleEdge(port)
                try {
                    Thread.sleep(5)
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        } as Runnable, "mcp23017-poll-${port}")
        pollThread[port].daemon = true
        pollThread[port].start()
    }

    private void stopPolling(int port) {
        polling[port] = false
        if (pollThread[port] != null) {
            pollThread[port].interrupt()
            pollThread[port] = null
        }
    }

    private void handleEdge(int port) {
        try {
            int status = pollInterrupt(port)
            if (status == 0) return
            if (callbackBoth != null) callbackBoth.accept(port, status)
            if (callbackPort[port] != null) callbackPort[port].accept(status)

            int raw = readPort(port)
            for (int bit = 0; bit < 8; bit++) {
                if ((status & (1 << bit)) == 0) continue
                PinWatch w = watches[port][bit]
                if (w == null || w.handler == null) continue
                boolean current = ((raw >> bit) & 1) == 1
                boolean rising  = current && !w.lastState
                boolean falling = !current && w.lastState
                w.lastState = current
                boolean fire = w.trigger == EdgeTrigger.CHANGE ||
                    (w.trigger == EdgeTrigger.RISING && rising) ||
                    (w.trigger == EdgeTrigger.FALLING && falling)
                if (fire) w.handler.accept(new Pin(this, port * 8 + bit))
            }
        } catch (IOException ignored) {
            // bus error; wait for the next edge/tick rather than propagating
        }
    }

    /**
     * Read &amp; clear interrupt status; returns the raw INTF flag register.
     *
     * <p>Reads INTFA/INTFB (which pins triggered) then INTCAPA/INTCAPB (which
     * clears the interrupt latch and re-arms it). Note that reading either
     * INTCAP or GPIO clears the interrupt condition on this chip — call
     * {@link #pollInterrupt} or {@link #readCapture} per event, not both.
     *
     * @param port 0 = PORTA, 1 = PORTB
     * @return 8-bit interrupt flag mask
     */
    int pollInterrupt(int port) {
        port &= 1
        int flags = readReg(REG_INTFA + port)
        readReg(REG_INTCAPA + port) // clears and re-arms the interrupt; value discarded
        return flags
    }

    /**
     * Read INTCAP: the port state latched at the moment of the interrupt.
     *
     * <p>Also clears the interrupt latch (see {@link #pollInterrupt} doc) —
     * call this instead of {@link #pollInterrupt} when the captured pin state
     * is wanted rather than the flag register.
     *
     * @param port 0 = PORTA, 1 = PORTB
     * @return 8-bit captured port bitmask at the moment of interrupt
     */
    int readCapture(int port) {
        return readReg(REG_INTCAPA + (port & 1))
    }

    // =========================================================================
    // Pin — extends Mcp23017Minimal.Pin with watch/unwatch support
    // =========================================================================

    /**
     * GPIO proxy for a single MCP23017 pin — full interface.
     *
     * <p>Extends the minimal Pin with pull-up control and per-pin change watchers.
     */
    @CompileStatic
    static class Pin extends Mcp23017Minimal.Pin {

        private final Mcp23017Full chip

        protected Pin(Mcp23017Full chip, int n) {
            super(chip, n)
            this.chip = chip
        }

        /**
         * Set or clear the pull-up for this pin.
         *
         * <p>Only electrically active when the pin is configured as input.
         *
         * @param pullUp {@code true} to enable 100 kΩ pull-up; {@code false} to disable
         */
        void setPullUp(boolean pullUp) {
            int port = n >> 3
            int bit  = n & 7
            int reg  = REG_GPPUA + port
            int cur  = chip.readReg(reg)
            if (pullUp) cur |=   (1 << bit)
            else        cur &= ~((1 << bit))
            chip.writeReg(reg, cur)
        }

        /**
         * Subscribe to this pin's edge events (default trigger: {@link EdgeTrigger#CHANGE}).
         *
         * <p>At most one handler per pin at a time; a second call replaces the
         * first. Call the chip's {@link Mcp23017Full#onInterrupt}/{@link
         * Mcp23017Full#onInterruptPort} (for this pin's port) first to arm delivery.
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
            int port = n >> 3
            int bit  = n & 7
            PinWatch w = new PinWatch()
            w.handler = handler
            w.trigger = trigger
            w.lastState = ((chip.readPort(port) >> bit) & 1) == 1
            chip.watches[port][bit] = w
        }

        /** Unsubscribe this pin's handler. No-op if not registered. */
        void unwatch() {
            chip.watches[n >> 3][n & 7] = null
        }
    }
}
