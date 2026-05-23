#pragma once
#include <stdint.h>
#include <functional>
#include "../../transport/Transport.h"

/** @brief MCP23017 16-bit I/O port expander — minimal interface.
 *
 *  Provides 16 GPIO pins (GPA0–GPA7, GPB0–GPB7) as IOExpanderPin objects via pin().
 *  Direction is explicit: IODIR bit = 1 means input, 0 means output.
 *  GPA7 and GPB7 are output-only per the MCP23017 datasheet.
 *  A shadow register is maintained for OLATA/OLATB so individual output pins can
 *  be set/cleared/toggled without a read-modify-write transaction.
 *
 *  @param transport Configured I²C transport pointing at the device.
 *  @param addr 7-bit I²C device address (default 0x20, range 0x20–0x27).
 */
class MCP23017Minimal {
public:
    /** @brief GPIO proxy for a single MCP23017 pin.
     *
     *  Obtain via MCP23017Minimal::pin(). Do not construct directly.
     *  Reuses Arduino GPIO constants: INPUT, OUTPUT, INPUT_PULLUP, HIGH, LOW.
     *  GPA7 (n=7) and GPB7 (n=15) are output-only.
     */
    class IOExpanderPin {
    public:
        /** @brief Construct a pin proxy.
         *  @param chip Parent MCP23017Minimal instance.
         *  @param n Pin index 0–15. 0–7 = PORTA, 8–15 = PORTB.
         */
        IOExpanderPin(MCP23017Minimal& chip, uint8_t n);

        /** @brief Set pin direction.
         *  @param m INPUT, OUTPUT, or INPUT_PULLUP (pull-up is ignored in Minimal;
         *           use MCP23017Full for per-pin pull-up support).
         */
        void mode(uint8_t m);

        /** @brief Write pin output latch.
         *  @param v HIGH or LOW.
         */
        void write(uint8_t v);

        /** @brief Read actual pin logic level.
         *  @return HIGH or LOW.
         */
        uint8_t read();

        /** @brief Set pin high (output latch = 1). */
        void high()   { write(HIGH); }

        /** @brief Drive pin low (output latch = 0). */
        void low()    { write(LOW); }

        /** @brief Invert the current output latch bit for this pin. */
        void toggle() { write(read() ^ 1); }

    protected:
        MCP23017Minimal& _chip;
        uint8_t  _n;
        uint8_t  _port;
        uint8_t  _bit;
        uint8_t  _direction;
    };

    explicit MCP23017Minimal(Transport& transport, uint8_t addr = 0x20);

    /** @brief Return a pin proxy for pin n (0–15).
     *  @param n Pin index 0–15; 0–7 = PORTA (GPA0–GPA7), 8–15 = PORTB (GPB0–GPB7).
     */
    IOExpanderPin pin(uint8_t n);

    /** @brief Read all 8 pins of a port as a bitmask.
     *  @param port 0 = PORTA, 1 = PORTB.
     *  @return 8-bit bitmask; bit 0 = pin 0.
     */
    uint8_t read_port(uint8_t port);

    /** @brief Write all 8 output pins of a port and update the shadow register.
     *  @param port 0 = PORTA, 1 = PORTB.
     *  @param mask 8-bit output mask.
     */
    void write_port(uint8_t port, uint8_t mask);

protected:
    static constexpr uint8_t REG_IODIRA = 0x00;
    static constexpr uint8_t REG_IODIRB = 0x01;
    static constexpr uint8_t REG_IPOLA  = 0x02;
    static constexpr uint8_t REG_IPOLB  = 0x03;
    static constexpr uint8_t REG_GPPUA  = 0x0C;
    static constexpr uint8_t REG_GPPUB  = 0x0D;
    static constexpr uint8_t REG_GPIOA  = 0x12;
    static constexpr uint8_t REG_GPIOB  = 0x13;
    static constexpr uint8_t REG_OLATA  = 0x14;
    static constexpr uint8_t REG_OLATB  = 0x15;

    Transport& _transport;
    uint8_t   _addr;

public:
    uint8_t   _shadow[2]    = {0, 0};
    uint8_t   _direction[2] = {0x7F, 0x7F};
protected:

    void _write_reg(uint8_t reg, uint8_t value);
    uint8_t _read_reg(uint8_t reg);
    void _write_port(uint8_t port, uint8_t mask);
    uint8_t _read_port_raw(uint8_t port);
    void _set_pin(uint8_t n, uint8_t value);

    friend class IOExpanderPin;
};

/** @brief MCP23017 full interface — extends minimal with pull-ups, polarity, and interrupts.
 *
 *  Adds per-pin pull-up configuration (GPPU), optional INTA/INTB callbacks,
 *  interrupt-on-change or default-compare modes, and clear_interrupt().
 *
 *  @param transport Configured I²C transport pointing at the device.
 *  @param addr 7-bit I²C device address (default 0x20).
 */
class MCP23017Full : public MCP23017Minimal {
public:
    /** @brief GPIO proxy for a single MCP23017 pin — Full interface.
     *
     *  Adds INPUT_PULLUP support and per-pin attachInterrupt()/detachInterrupt().
     */
    class IOExpanderPin : public MCP23017Minimal::IOExpanderPin {
    public:
        /** @brief Construct a Full pin proxy.
         *  @param chip Parent MCP23017Full instance.
         *  @param n Pin index 0–15.
         */
        IOExpanderPin(MCP23017Full& chip, uint8_t n);

        /** @brief Set pin direction including pull-up.
         *  @param m INPUT, OUTPUT, or INPUT_PULLUP.
         */
        void mode(uint8_t m);

        /** @brief Attach a per-pin interrupt handler.
         *
         *  The handler is called when the pin's state matches the trigger.
         *  Requires configure_interrupt() to have been called on the chip.
         *
         *  @param handler Function pointer: void(*)(IOExpanderPin*).
         *  @param mode RISING, FALLING, or CHANGE.
         */
        void attachInterrupt(void (*handler)(IOExpanderPin*), uint8_t mode);

        /** @brief Remove the per-pin interrupt handler. */
        void detachInterrupt();

    private:
        MCP23017Full& _full_chip;
        void (*_handler)(IOExpanderPin*) = nullptr;
        uint8_t _irq_mode = 0;
        friend class MCP23017Full;
    };

    explicit MCP23017Full(Transport& transport, uint8_t addr = 0x20);

    /** @brief Return a Full pin proxy for pin n (0–15). */
    IOExpanderPin pin(uint8_t n);

    /** @brief Enable/disable per-pin 100 kΩ pull-ups on a port.
     *  @param port 0 = PORTA, 1 = PORTB.
     *  @param mask 8-bit mask; bit n = 1 enables pull-up on pin n.
     */
    void configure_pullup(uint8_t port, uint8_t mask);

    /** @brief Set input polarity inversion per pin.
     *  @param port 0 = PORTA, 1 = PORTB.
     *  @param mask 8-bit mask; bit n = 1 inverts GPIO read for pin n.
     */
    void configure_polarity(uint8_t port, uint8_t mask);

    /** @brief Enable interrupt for a port.
     *
     *  @param port 0 = PORTA, 1 = PORTB.
     *  @param int_gpio_pin Hardware GPIO pin number for the INT line, or -1 for polling (Linux only).
     *  @param callback void(*)(uint8_t changed_mask) called when an interrupt fires.
     *  @param mode 'change' (default) compares against previous pin value;
     *              'default' compares against DEFVAL register.
     *  @param mirror If true, sets IOCON.MIRROR so either port's interrupt activates both INTA and INTB.
     */
    void configure_interrupt(uint8_t port, int int_gpio_pin,
                             void (*callback)(uint8_t), const char* mode = "change",
                             bool mirror = false);

    /** @brief Set DEFVAL register for default-compare interrupt mode.
     *  @param port 0 = PORTA, 1 = PORTB.
     *  @param mask 8-bit default compare value.
     */
    void set_default_value(uint8_t port, uint8_t mask);

    /** @brief Read INTCAP and return captured port state; clears INT for the port.
     *  @param port 0 = PORTA, 1 = PORTB.
     *  @return 8-bit captured port bitmask at the moment of interrupt.
     */
    uint8_t clear_interrupt(uint8_t port);

    /** @brief Read INTFA/INTFB without clearing the interrupt.
     *  @param port 0 = PORTA, 1 = PORTB.
     *  @return 8-bit interrupt flag mask.
     */
    uint8_t read_interrupt_flags(uint8_t port);

    /** @brief Disable interrupt for the port.
     *  @param port 0 = PORTA, 1 = PORTB.
     */
    void stop_interrupt(uint8_t port);

protected:
    static constexpr uint8_t REG_GPINTENA = 0x04;
    static constexpr uint8_t REG_GPINTENB = 0x05;
    static constexpr uint8_t REG_DEFVALA  = 0x06;
    static constexpr uint8_t REG_DEFVALB  = 0x07;
    static constexpr uint8_t REG_INTCONA  = 0x08;
    static constexpr uint8_t REG_INTCONB  = 0x09;
    static constexpr uint8_t REG_IOCON    = 0x0A;
    static constexpr uint8_t REG_INTFA   = 0x0E;
    static constexpr uint8_t REG_INTFB    = 0x0F;
    static constexpr uint8_t REG_INTCAPA  = 0x10;
    static constexpr uint8_t REG_INTCAPB  = 0x11;

public:
    uint8_t _pullup[2] = {0, 0};
protected:
    void (*_callback)(uint8_t) = nullptr;

    static void _dispatch(MCP23017Full* chip, uint8_t changed);
};