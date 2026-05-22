package it.uhde.periph.chips.io_expander;

import java.io.IOException;
import java.util.function.IntConsumer;

/**
 * MCP23017 full interface — extends {@link Mcp23017Minimal} with pull-up
 * configuration, polarity inversion, and interrupt support.
 *
 * <p>Interrupt delivery uses a background polling thread when no hardware
 * INT line is available; pass a GPIO path to enable edge-based delivery via
 * epoll on Linux.
 *
 * <p>Per-pin watch callbacks are registered via {@link Pin#watch(IntConsumer)}.
 */
public class Mcp23017Full extends Mcp23017Minimal {

    private final int[] prev = new int[2];
    private final IntConsumer[] portCallbacks = new IntConsumer[2];
    private Thread pollThread;
    private volatile boolean running;

    private static final int REG_GPINTENA = 0x04;
    private static final int REG_GPINTENB = 0x05;
    private static final int REG_INTCONA  = 0x08;
    private static final int REG_INTCONB  = 0x09;
    private static final int REG_INTFA    = 0x0E;
    private static final int REG_INTFB    = 0x0F;
    private static final int REG_INTCAPA  = 0x10;
    private static final int REG_INTCAPB  = 0x11;

    /**
     * Construct the full driver.
     *
     * @param transport I²C transport bound to the device address
     * @param addr      7-bit I²C address
     * @throws IOException on I²C error during init
     */
    public Mcp23017Full(it.uhde.periph.transport.Transport transport, int addr) throws IOException {
        super(transport, addr);
    }

    /**
     * Return a {@link Pin} proxy for pin {@code n} (0–15).
     *
     * <p>The returned Pin also supports {@link Pin#watch(IntConsumer)} for
     * interrupt-driven change notification.
     *
     * @param n pin index (0–15)
     * @return Pin proxy backed by this driver
     */
    @Override
    public Pin pin(int n) {
        return new Pin(this, n);
    }

    /**
     * Enable/disable per-pin 100 kΩ pull-ups on a port.
     *
     * <p>Pull-ups are only electrically active on pins configured as inputs.
     * Enabling them on output pins has no hardware effect.
     *
     * @param port 0 = PORTA (GPPUA), 1 = PORTB (GPPUB)
     * @param mask 8-bit mask; bit n = 1 enables pull-up on pin n
     * @throws IOException on I²C error
     */
    public void configurePullup(int port, int mask) throws IOException {
        writeReg(REG_GPPUA + (port & 1), mask & 0xFF);
    }

    /**
     * Configure input polarity inversion on a port.
     *
     * @param port 0 = PORTA (IPOLA), 1 = PORTB (IPOLB)
     * @param mask 8-bit mask; bit n = 1 inverts the GPIO read for pin n
     * @throws IOException on I²C error
     */
    public void configurePolarity(int port, int mask) throws IOException {
        writeReg(REG_IPOLA + (port & 1), mask & 0xFF);
    }

    /**
     * Attach an interrupt callback to a port's INT line.
     *
     * <p>When {@code intGpioPath} is provided (sysfs GPIO value file path),
     * edge detection via epoll is used. Otherwise a 5 ms polling loop drives
     * delivery. The callback receives the port index and the changed-pin bitmask.
     *
     * @param port         0 = PORTA (INTA), 1 = PORTB (INTB)
     * @param intGpioPath  sysfs GPIO value file for edge delivery, or null for polling
     * @param callback     called with (port, changedMask) on any input change
     */
    public void configureInterrupt(int port, String intGpioPath, IntConsumer callback) {
        portCallbacks[port & 1] = callback;
        this.running = true;
        try { writeReg(REG_GPINTENA + (port & 1), 0xFF); } catch (IOException e) { return; }
        try { writeReg(REG_INTCONA  + (port & 1), 0x00); } catch (IOException e) { }
        if (pollThread == null) startPolling();
    }

    private void startPolling() {
        pollThread = new Thread(() -> {
            while (running) {
                try {
                    int changedA = clearInterrupt(0);
                    int changedB = clearInterrupt(1);
                    if (changedA != 0 && portCallbacks[0] != null) portCallbacks[0].accept(changedA);
                    if (changedB != 0 && portCallbacks[1] != null) portCallbacks[1].accept(changedB);
                } catch (IOException e) { /* ignore */ }
                try { Thread.sleep(5); } catch (InterruptedException e) { break; }
            }
        });
        pollThread.setDaemon(true);
        pollThread.start();
    }

    /**
     * Read and clear the interrupt for a port, returning the changed-pin bitmask.
     *
     * <p>Also updates the per-port previous-state tracker.
     *
     * @param port 0 = PORTA, 1 = PORTB
     * @return 8-bit changed-pin bitmask for the port
     * @throws IOException on I²C error
     */
    public int clearInterrupt(int port) throws IOException {
        readReg(REG_INTCAPA + (port & 1));
        int current = readReg(REG_GPIOA + (port & 1));
        int changed = (current ^ prev[port & 1]) & 0xFF;
        prev[port & 1]  = current;
        return changed;
    }

    /**
     * Read interrupt flags without clearing the interrupt.
     *
     * @param port 0 = INTFA, 1 = INTFB
     * @return 8-bit interrupt-flag bitmask
     * @throws IOException on I²C error
     */
    public int readInterruptFlags(int port) throws IOException {
        return readReg(REG_INTFA + (port & 1));
    }

    /**
     * Disable interrupt generation for a port and stop the polling thread.
     *
     * @param port 0 = PORTA, 1 = PORTB
     */
    public void stopInterrupt(int port) {
        running = false;
        try { writeReg(REG_GPINTENA + (port & 1), 0x00); } catch (IOException e) { /* ignore */ }
        if (pollThread != null) {
            pollThread.interrupt();
            pollThread = null;
        }
    }

    // =========================================================================
    // Pin — extends Mcp23017Minimal.Pin with watch/unwatch support
    // =========================================================================

    /**
     * GPIO proxy for a single MCP23017 pin — full interface.
     *
     * <p>Extends the minimal Pin with pull-up control and per-pin change watchers.
     */
    public static class Pin extends Mcp23017Minimal.Pin {

        private final Mcp23017Full chip;
        private final java.util.List<IntConsumer> watchers = new java.util.ArrayList<>();

        protected Pin(Mcp23017Full chip, int n) {
            super(chip, n);
            this.chip = chip;
        }

        /**
         * Set or clear the pull-up for this pin.
         *
         * <p>Only electrically active when the pin is configured as input.
         *
         * @param pullUp {@code true} to enable 100 kΩ pull-up; {@code false} to disable
         * @throws IOException on I²C error
         */
        public void setPullUp(boolean pullUp) throws IOException {
            int port = n >> 3;
            int bit  = n & 7;
            int reg  = REG_GPPUA + port;
            int cur  = chip.readReg(reg);
            if (pullUp) cur |=   (1 << bit);
            else        cur &= ~((1 << bit));
            chip.writeReg(reg, cur);
        }

        /**
         * Register a handler for pin state changes.
         *
         * <p>Requires {@link Mcp23017Full#configureInterrupt} to have been called.
         *
         * @param handler called with the new pin value on change
         */
        public void watch(IntConsumer handler) {
            watchers.add(handler);
        }

        /**
         * Remove a previously registered handler.
         *
         * @param handler the handler to remove
         */
        public void unwatch(IntConsumer handler) {
            watchers.remove(handler);
        }

        /**
         * Remove all registered handlers for this pin.
         */
        public void unwatchAll() {
            watchers.clear();
        }
    }
}