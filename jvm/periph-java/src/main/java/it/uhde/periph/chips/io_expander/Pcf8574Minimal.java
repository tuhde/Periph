package it.uhde.periph.chips.io_expander;

import it.uhde.periph.transport.Transport;

import java.io.IOException;

/**
 * PCF8574 8-bit quasi-bidirectional I/O port expander — minimal interface.
 *
 * <p>Exposes all eight pins (P0–P7) as GPIO objects via the {@link #pin(int)} factory.
 * Direction is implicit: writing 1 puts a pin in input mode (weak ~100 µA pull-up);
 * writing 0 drives it strongly low (up to 25 mA sink). A shadow register tracks the
 * output latch for bit-level operations without a read-modify-write bus transaction.
 *
 * <p>Initialises all pins to input mode (shadow = {@code 0xFF}) at construction.
 *
 * <p>Two address-range variants share identical behaviour:
 * <ul>
 *   <li><b>PCF8574</b> — default address {@code 0x20} (A2=A1=A0=0)</li>
 *   <li><b>PCF8574A</b> — default address {@code 0x38} (A2=A1=A0=0); overlaps common OLED range</li>
 * </ul>
 */
public class Pcf8574Minimal {

    protected final Transport transport;

    /** Output latch shadow — bit n = last value written to pin n. Initialised to {@code 0xFF}. */
    protected int shadow = 0xFF;

    /**
     * Construct the driver and initialise all pins to input mode (shadow = {@code 0xFF}).
     *
     * @param transport I²C transport bound to the PCF8574 device address (100 kHz max)
     * @throws IOException on I²C error
     */
    public Pcf8574Minimal(Transport transport) throws IOException {
        this.transport = transport;
        writePort(0xFF);
    }

    // -------------------------------------------------------------------------
    // Port-level API
    // -------------------------------------------------------------------------

    /**
     * Read all 8 pins as a bitmask.
     *
     * <p>Returns the actual logic level at each pin regardless of the shadow register.
     * Bit 0 = P0, bit 7 = P7.
     *
     * @param port port index (ignored; the PCF8574 has exactly one port)
     * @return 8-bit bitmask of current pin states
     * @throws IOException on I²C error
     */
    public int readPort(int port) throws IOException {
        return transport.read(1)[0] & 0xFF;
    }

    /**
     * Read all 8 pins as a bitmask.
     * Equivalent to {@link #readPort(int) readPort(0)}.
     *
     * @return 8-bit bitmask of current pin states (bit 0 = P0)
     * @throws IOException on I²C error
     */
    public int readPort() throws IOException {
        return readPort(0);
    }

    /**
     * Write all 8 pins at once and update the shadow register.
     *
     * @param port port index (ignored; the PCF8574 has exactly one port)
     * @param mask 8-bit output mask; 1 = input mode (weak pull-up), 0 = drive low
     * @throws IOException on I²C error
     */
    public void writePort(int port, int mask) throws IOException {
        shadow = mask & 0xFF;
        transport.write(new byte[]{ (byte) shadow });
    }

    /**
     * Write all 8 pins at once and update the shadow register.
     * Equivalent to {@link #writePort(int, int) writePort(0, mask)}.
     *
     * @param mask 8-bit output mask; 1 = input mode (weak pull-up), 0 = drive low
     * @throws IOException on I²C error
     */
    public void writePort(int mask) throws IOException {
        writePort(0, mask);
    }

    // -------------------------------------------------------------------------
    // Pin factory
    // -------------------------------------------------------------------------

    /**
     * Return a {@link Pin} proxy for pin {@code n} (0–7).
     *
     * @param n pin index (0 = P0, 7 = P7)
     * @return Pin proxy backed by this driver's shadow register
     */
    public Pin pin(int n) {
        return new Pin(this, n);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Set or clear a single pin and update the shadow register.
     *
     * @param n    pin index (0–7)
     * @param high {@code true} to release to input mode (write 1);
     *             {@code false} to drive low (write 0)
     * @throws IOException on I²C error
     */
    protected void setPin(int n, boolean high) throws IOException {
        if (high) shadow |=   (1 << n);
        else      shadow &= ~((1 << n));
        shadow &= 0xFF;
        transport.write(new byte[]{ (byte) shadow });
    }

    // =========================================================================
    // Pin — GPIO proxy for a single PCF8574 pin
    // =========================================================================

    /**
     * GPIO proxy for a single PCF8574 pin.
     *
     * <p>Obtain via {@link Pcf8574Minimal#pin(int)}. Do not instantiate directly.
     *
     * <p>Direction is implicit: {@link #setHigh()} releases the pin to quasi-input
     * (internal ~100 µA pull-up); {@link #setLow()} drives the pin low via the
     * 25 mA open-drain sink. {@link #setInput()} and {@link #setOutput()} are
     * convenience aliases for {@link #setHigh()} and {@link #setLow()} respectively.
     */
    public static class Pin {

        protected final Pcf8574Minimal chip;
        protected final int n;

        /**
         * Construct a Pin proxy.
         *
         * @param chip parent {@link Pcf8574Minimal} driver
         * @param n    pin index (0–7)
         */
        protected Pin(Pcf8574Minimal chip, int n) {
            this.chip = chip;
            this.n    = n;
        }

        /**
         * Release the pin to input mode by writing 1 to the shadow bit.
         *
         * @throws IOException on I²C error
         */
        public void setInput() throws IOException { chip.setPin(n, true); }

        /**
         * Drive the pin low by writing 0 to the shadow bit.
         *
         * <p>Note: the PCF8574 cannot actively drive high. To release the pin
         * high after {@code setOutput()}, call {@link #setHigh()}.
         *
         * @throws IOException on I²C error
         */
        public void setOutput() throws IOException { chip.setPin(n, false); }

        /**
         * Write 1 to this pin — releases it to quasi-input mode (internal pull-up).
         *
         * @throws IOException on I²C error
         */
        public void setHigh() throws IOException { chip.setPin(n, true); }

        /**
         * Write 0 to this pin — drives it low (open-drain sink, up to 25 mA).
         *
         * @throws IOException on I²C error
         */
        public void setLow() throws IOException { chip.setPin(n, false); }

        /**
         * Read the actual logic level at the pin.
         *
         * <p>Performs a bus read; the result reflects the external signal rather
         * than the shadow register.
         *
         * @return {@code true} if the pin is high; {@code false} if low
         * @throws IOException on I²C error
         */
        public boolean read() throws IOException {
            return (((chip.transport.read(1)[0] & 0xFF) >> n) & 1) == 1;
        }

        /**
         * Invert the shadow bit for this pin.
         *
         * <p>If the last written value was 1 (input mode) the pin is driven low;
         * if the last written value was 0 (driven low) the pin is released to input.
         *
         * @throws IOException on I²C error
         */
        public void toggle() throws IOException {
            chip.setPin(n, ((chip.shadow >> n) & 1) == 0);
        }
    }
}
