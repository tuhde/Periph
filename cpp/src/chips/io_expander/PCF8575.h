#pragma once
#include <stdint.h>
#include "../../transport/Transport.h"

/** @brief PCF8575 16-bit quasi-bidirectional I/O port expander — minimal interface.
 *
 * Exposes all 16 pins (P00–P07, P10–P17) as GPIO objects via the pin() factory.
 * Direction is implicit: writing HIGH puts a pin in input mode (weak ~100 µA pull-up);
 * writing LOW drives it strongly low (up to 25 mA sink). Two shadow registers track
 * the output latches for bit-level operations without a read-modify-write transaction.
 *
 * Initialises all pins to input mode (shadow = [0xFF, 0xFF]) at construction.
 *
 * @param transport Configured I²C transport pointing at the device (400 kHz max).
 * @param addr 7-bit I²C device address (default 0x20).
 */
class PCF8575Minimal {
public:
    /** @brief GPIO proxy for a single PCF8575 pin.
     *
     * Obtain via PCF8575Minimal::pin(). Do not construct directly.
     * Reuses Arduino GPIO constants: INPUT, OUTPUT, HIGH, LOW.
     */
    class IOExpanderPin {
    public:
        /** @brief Construct a pin proxy.
         *  @param chip Parent PCF8575Minimal instance.
         *  @param n Pin index 0–15.
         */
        IOExpanderPin(PCF8575Minimal& chip, uint8_t n);

        /** @brief Set pin direction.
         *  @param m INPUT or OUTPUT. INPUT_PULLUP is treated as INPUT
         *           (the PCF8575 always has an internal pull-up in input mode).
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
        PCF8575Minimal& _chip;
        uint8_t         _n;
    };

    /** @brief Construct and initialise the PCF8575.
     *  @param transport Configured I²C transport pointing at the device.
     *  @param addr 7-bit I²C device address (default 0x20).
     */
    PCF8575Minimal(Transport& transport, uint8_t addr = 0x20);

    /** @brief Return a pin proxy for pin n (0–15).
     *  @param n Pin index.
     *  @return IOExpanderPin proxy.
     */
    IOExpanderPin pin(uint8_t n);

    /** @brief Read all 8 pins of the given port as a bitmask.
     *  @param port Port index (0 = P00–P07, 1 = P10–P17).
     *  @return 8-bit bitmask of actual pin logic levels.
     */
    uint8_t read_port(uint8_t port);

    /** @brief Write all 8 pins of the given port and update the shadow register.
     *  @param port Port index (0 or 1).
     *  @param mask 8-bit mask; 1 = input mode, 0 = drive low.
     */
    void write_port(uint8_t port, uint8_t mask);

    uint8_t _shadow[2];

protected:
    Transport& _transport;
    uint8_t    _addr;

    void    _write_both();
    uint8_t _read_port(uint8_t port);
    void    _set_pin(uint8_t n, uint8_t value);
};

/** @brief PCF8575 full interface — extends PCF8575Minimal with interrupt support.
 *
 * Adds configure_interrupt() to arm the chip's active-low INT output and
 * clear_interrupt() to read pin states and return a 16-bit changed-pin bitmask.
 * IOExpanderPin gains attachInterrupt() / detachInterrupt().
 *
 * @param transport Configured I²C transport pointing at the device.
 * @param addr 7-bit I²C device address (default 0x20).
 */
class PCF8575Full : public PCF8575Minimal {
public:
    /** @brief GPIO proxy for a single PCF8575 pin — Full interface.
     *
     * Extends IOExpanderPin with attachInterrupt() / detachInterrupt().
     */
    class IOExpanderPin : public PCF8575Minimal::IOExpanderPin {
    public:
        /** @brief Construct a Full pin proxy.
         *  @param chip Parent PCF8575Full instance.
         *  @param n Pin index 0–15.
         */
        IOExpanderPin(PCF8575Full& chip, uint8_t n);

        /** @brief Attach a per-pin interrupt handler.
         *  @param handler Function pointer: void(*)(IOExpanderPin*).
         *  @param mode FALLING (INT asserts on input low), RISING, or CHANGE.
         */
        void attachInterrupt(void (*handler)(IOExpanderPin*), uint8_t mode);

        /** @brief Remove the per-pin interrupt handler. */
        void detachInterrupt();

    private:
        PCF8575Full& _full_chip;
        void (*_handler)(IOExpanderPin*) = nullptr;
        uint8_t _irq_mode = 0;
        uint8_t _last_state = 0xFF;
        friend class PCF8575Full;
    };

    /** @brief Construct and initialise the PCF8575Full.
     *  @param transport Configured I²C transport pointing at the device.
     *  @param addr 7-bit I²C device address (default 0x20).
     */
    PCF8575Full(Transport& transport, uint8_t addr = 0x20);

    /** @brief Return a Full pin proxy for pin n (0–15).
     *  @param n Pin index.
     *  @return IOExpanderPin proxy.
     */
    IOExpanderPin pin(uint8_t n);

    /** @brief Configure the chip's INT line and a global change callback.
     *  @param int_gpio_pin Hardware GPIO pin number for the INT signal (Arduino pin number,
     *         sysfs GPIO number on Linux, or pin index on Zephyr).
     *  @param callback void(*)(uint8_t changed_mask); called on any input change.
     */
    void configure_interrupt(int int_gpio_pin, void (*callback)(uint8_t));

    /** @brief Read both ports and return 16-bit bitmask of pins that changed.
     *  @return 16-bit bitmask; bits 0–7 = Port 0 changed, bits 8–15 = Port 1 changed.
     */
    uint16_t clear_interrupt();

private:
    uint8_t  _prev[2] = {0xFF, 0xFF};
    void   (*_callback)(uint8_t) = nullptr;

    static void _dispatch(PCF8575Full* chip, uint8_t changed);
};