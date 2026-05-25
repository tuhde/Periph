#pragma once
#include <stdint.h>
#include "../../transport/Transport.h"

/** @brief PCF8574 8-bit quasi-bidirectional I/O port expander — minimal interface.
 *
 * Exposes all eight pins (P0–P7) as GPIO objects via the pin() factory. Direction
 * is implicit: writing HIGH puts a pin in input mode (weak ~100 µA pull-up);
 * writing LOW drives it strongly low (up to 25 mA sink). A shadow register tracks
 * the output latch for bit-level operations without a read-modify-write transaction.
 *
 * Initialises all pins to input mode (shadow = 0xFF) at construction.
 *
 * @param transport Configured I²C transport pointing at the device (100 kHz max).
 * @param addr      7-bit I²C device address. PCF8574 default 0x20; PCF8574A 0x38.
 */
class PCF8574Minimal {
public:
    /** @brief GPIO proxy for a single PCF8574 pin.
     *
     * Obtain via PCF8574Minimal::pin(). Do not construct directly.
     * Reuses Arduino GPIO constants: INPUT, OUTPUT, HIGH, LOW.
     * The same class compiles on Arduino, Linux GCC, and Zephyr; interrupt
     * delivery is guarded with platform ifdefs in PCF8574Full.
     */
    class IOExpanderPin {
    public:
        /** @brief Construct a pin proxy.
         *  @param chip Parent PCF8574Minimal instance.
         *  @param n    Pin index 0–7.
         */
        IOExpanderPin(PCF8574Minimal& chip, uint8_t n);

        /** @brief Set pin direction.
         *  @param m INPUT or OUTPUT. INPUT_PULLUP is treated as INPUT
         *           (the PCF8574 always has an internal pull-up in input mode).
         */
        void mode(uint8_t m);

        /** @brief Write pin output latch.
         *  @param v HIGH (release to quasi-input) or LOW (drive low).
         */
        void write(uint8_t v);

        /** @brief Read actual pin logic level.
         *  @return HIGH or LOW.
         */
        uint8_t read();

        /** @brief Set pin high (release to quasi-input mode). */
        void high()   { write(HIGH); }

        /** @brief Drive pin low. */
        void low()    { write(LOW); }

        /** @brief Invert the shadow bit for this pin. */
        void toggle() { write(read() ^ 1); }

    protected:
        PCF8574Minimal& _chip;
        uint8_t         _n;
    };

    PCF8574Minimal(Transport& transport, uint8_t addr = 0x20);

    /** @brief Return a pin proxy for pin n (0–7). */
    IOExpanderPin pin(uint8_t n);

    /** @brief Read all 8 pins as a bitmask (bit 0 = P0, bit 7 = P7).
     *  @param port Port index (ignored; the PCF8574 has one port).
     *  @return 8-bit bitmask of actual pin logic levels.
     */
    uint8_t read_port(uint8_t port = 0);

    /** @brief Write all 8 pins and update the shadow register.
     *  @param port Port index (ignored).
     *  @param mask 8-bit mask; 1 = input mode, 0 = drive low.
     */
    void write_port(uint8_t port, uint8_t mask);

    uint8_t _shadow;

protected:
    Transport& _transport;
    uint8_t    _addr;

    void    _write_port(uint8_t mask);
    uint8_t _read_port();
    void    _set_pin(uint8_t n, uint8_t value);
};

/** @brief PCF8574 full interface — extends PCF8574Minimal with interrupt support.
 *
 * Adds configure_interrupt() to arm the chip's active-low INT output and
 * clear_interrupt() to read pin states and return a changed-pin bitmask.
 * IOExpanderPin gains attachInterrupt() / detachInterrupt().
 *
 * @param transport Configured I²C transport pointing at the device.
 * @param addr      7-bit I²C device address (default 0x20).
 */
class PCF8574Full : public PCF8574Minimal {
public:
    /** @brief GPIO proxy for a single PCF8574 pin — Full interface.
     *
     * Extends IOExpanderPin with attachInterrupt() / detachInterrupt().
     * Interrupt delivery on Linux uses a poll() thread on the INT GPIO sysfs fd.
     * On Zephyr, gpio_add_callback() is used.
     */
    class IOExpanderPin : public PCF8574Minimal::IOExpanderPin {
    public:
        /** @brief Construct a Full pin proxy.
         *  @param chip Parent PCF8574Full instance.
         *  @param n    Pin index 0–7.
         */
        IOExpanderPin(PCF8574Full& chip, uint8_t n);

        /** @brief Attach a per-pin interrupt handler.
         *
         *  The handler is called with this pin as the argument when its state
         *  matches the trigger. Requires the chip's INT line to have been
         *  configured via PCF8574Full::configure_interrupt().
         *
         *  @param handler Function pointer: void(*)(IOExpanderPin*).
         *  @param mode    FALLING (INT asserts on input low), RISING, or CHANGE.
         */
        void attachInterrupt(void (*handler)(IOExpanderPin*), uint8_t mode);

        /** @brief Remove the per-pin interrupt handler. */
        void detachInterrupt();

    private:
        PCF8574Full& _full_chip;
        void (*_handler)(IOExpanderPin*) = nullptr;
        uint8_t _irq_mode = 0;
        uint8_t _last_state = 0xFF;
        friend class PCF8574Full;
    };

    PCF8574Full(Transport& transport, uint8_t addr = 0x20);

    /** @brief Return a Full pin proxy for pin n (0–7). */
    IOExpanderPin pin(uint8_t n);

    /** @brief Configure the chip's INT line and a global change callback.
     *
     *  On Arduino, @p int_gpio_pin is the Arduino pin number connected to INT.
     *  On Linux, @p int_gpio_pin is the sysfs GPIO number (pass -1 to disable).
     *  On Zephyr, @p int_gpio_pin is the pin index on the INT GPIO port
     *  (the port itself is selected via DT_NODELABEL in the application).
     *
     *  The callback receives the 8-bit bitmask of pins that changed.
     *
     *  @param int_gpio_pin Hardware GPIO pin number for the INT signal.
     *  @param callback     void(*)(uint8_t changed_mask); called on any input change.
     */
    void configure_interrupt(int int_gpio_pin, void (*callback)(uint8_t));

    /** @brief Read port and return bitmask of pins that changed since last read.
     *
     *  Comparing current byte to previous read; also clears the INT line.
     *
     *  @return 8-bit bitmask; bit n = 1 if pin n changed.
     */
    uint8_t clear_interrupt();

private:
    uint8_t  _prev          = 0xFF;
    void   (*_callback)(uint8_t) = nullptr;

    static void _dispatch(PCF8574Full* chip, uint8_t changed);
};
