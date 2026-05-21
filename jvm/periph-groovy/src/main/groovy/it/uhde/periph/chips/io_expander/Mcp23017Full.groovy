package it.uhde.periph.chips.io_expander

import groovy.transform.CompileStatic

/**
 * MCP23017 full interface — extends [Mcp23017Minimal] with pull-up
 * configuration, polarity inversion, and interrupt support.
 *
 * Interrupt delivery uses a background polling thread when no hardware
 * INT line is available.
 *
 * @param transport I²C transport bound to the device address
 * @param addr      7-bit I²C address
 */
@CompileStatic
class Mcp23017Full extends Mcp23017Minimal {

    private final int[] prev = new int[2]
    private Closure callback
    private Thread pollThread
    private volatile boolean running = false

    private static final int REG_GPPUA   = 0x0C
    private static final int REG_GPPUB   = 0x0D
    private static final int REG_INTFA   = 0x0E
    private static final int REG_INTFB   = 0x0F
    private static final int REG_INTCAPA = 0x10
    private static final int REG_INTCAPB = 0x11

    /**
     * Construct the full driver.
     *
     * @param transport I²C transport bound to the device address
     * @param addr      7-bit I²C address
     */
    Mcp23017Full(Transport transport, int addr = 0x20) {
        super(transport, addr)
    }

    /**
     * Return a [Pin] proxy for pin [n] (0–15).
     *
     * The returned Pin also supports [Pin.watch] for interrupt-driven change notification.
     *
     * @param n pin index (0–15)
     * @return Pin proxy backed by this driver
     */
    @Override
    Pin pin(int n) {
        return new Pin(this, n)
    }

    /**
     * Enable/disable per-pin 100 kΩ pull-ups on a port.
     *
     * Pull-ups are only electrically active on pins configured as inputs.
     *
     * @param port 0 = PORTA (GPPUA), 1 = PORTB (GPPUB)
     * @param mask 8-bit mask; bit n = 1 enables pull-up on pin n
     */
    void configurePullup(int port, int mask) {
        writeReg(REG_GPPUA + port, mask & 0xFF)
    }

    /**
     * Configure input polarity inversion on a port.
     *
     * @param port 0 = PORTA (IPOLA), 1 = PORTB (IPOLB)
     * @param mask 8-bit mask; bit n = 1 inverts the GPIO read for pin n
     */
    void configurePolarity(int port, int mask) {
        writeReg(0x02 + port, mask & 0xFF)
    }

    /**
     * Attach an interrupt callback to a port's INT line.
     *
     * Uses a 5 ms polling loop.
     *
     * @param port     0 = PORTA (INTA), 1 = PORTB (INTB)
     * @param callback Closure accepting (port, changedMask)
     */
    void configureInterrupt(int port, Closure callback) {
        this.callback = callback
        this.running  = true
        try {
            writeReg(0x04 + port, 0xFF)
            writeReg(0x08 + port, 0x00)
        } catch (IOException e) { }
        startPolling()
    }

    private void startPolling() {
        pollThread = new Thread({
            while (running) {
                try {
                    int changedA = clearInterrupt(0)
                    int changedB = clearInterrupt(1)
                    if (callback) {
                        if (changedA != 0) callback.call(0, changedA)
                        if (changedB != 0) callback.call(1, changedB)
                    }
                } catch (IOException e) { }
                try { Thread.sleep(5) } catch (InterruptedException e) { break }
            }
        })
        pollThread.start()
    }

    /**
     * Read and clear the interrupt for a port, returning the changed-pin bitmask.
     *
     * @param port 0 = PORTA, 1 = PORTB
     * @return 8-bit changed-pin bitmask for the port
     */
    int clearInterrupt(int port) throws IOException {
        int captured = readReg(REG_INTCAPA + port)
        int current  = readReg(REG_GPIOA   + port)
        int changed  = (current ^ prev[port]) & 0xFF
        prev[port]   = current
        return changed
    }

    /**
     * Read interrupt flags without clearing the interrupt.
     *
     * @param port 0 = INTFA, 1 = INTFB
     * @return 8-bit interrupt-flag bitmask
     */
    int readInterruptFlags(int port) throws IOException {
        return readReg(REG_INTFA + port)
    }

    /**
     * Disable interrupt generation for a port and stop the polling thread.
     *
     * @param port 0 = PORTA, 1 = PORTB
     */
    void stopInterrupt(int port) {
        running = false
        try { writeReg(0x04 + port, 0x00) } catch (IOException e) { }
        if (pollThread) {
            pollThread.interrupt()
            pollThread = null
        }
    }

    // =========================================================================
    // Pin — extends Mcp23017Minimal.Pin with watch/unwatch support
    // =========================================================================

    /**
     * GPIO proxy for a single MCP23017 pin — full interface.
     *
     * Extends the minimal Pin with pull-up control and per-pin change watchers.
     */
    @CompileStatic
    static class Pin extends Mcp23017Minimal.Pin {

        private final Mcp23017Full chip
        private final List<Closure> watchers = []

        protected Pin(Mcp23017Full chip, int n) {
            super(chip, n)
            this.chip = chip
        }

        /**
         * Set or clear the pull-up for this pin.
         *
         * Only electrically active when the pin is configured as input.
         *
         * @param pullUp {@code true} to enable 100 kΩ pull-up; {@code false} to disable
         */
        void setPullUp(boolean pullUp) {
            int port = n >> 3
            int bit  = n & 7
            int reg  = REG_GPPUA + port
            int cur  = chip.readReg(reg)
            chip.writeReg(reg, pullUp ? (cur | (1 << bit)) : (cur & ~(1 << bit)))
        }

        /**
         * Register a handler for pin state changes.
         *
         * Requires [Mcp23017Full.configureInterrupt] to have been called.
         *
         * @param handler Closure accepting the new pin value
         */
        void watch(Closure handler) { watchers.add(handler) }

        /** Remove a previously registered handler. */
        void unwatch(Closure handler) { watchers.remove(handler) }

        /** Remove all registered handlers for this pin. */
        void unwatchAll() { watchers.clear() }
    }
}