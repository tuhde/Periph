#pragma once
#include <stdint.h>
#include "../../connection/Connection.h"

#ifndef OUTPUT
#define INPUT        0
#define OUTPUT       1
#define INPUT_PULLUP 2
#define HIGH         1
#define LOW          0
#endif

/** @brief PCF8574 8-bit quasi-bidirectional I/O port expander — minimal interface.
 *
 * Exposes all eight pins (P0–P7) as GPIO objects via the pin() factory. Direction
 * is implicit: writing HIGH puts a pin in input mode (weak ~100 µA pull-up);
 * writing LOW drives it strongly low (up to 25 mA sink). A shadow register tracks
 * the output latch for bit-level operations without a read-modify-write transaction.
 *
 * Initialises all pins to input mode (shadow = 0xFF) at construction.
 *
 * @param connection Configured I²C connection pointing at the device (100 kHz max).
 * @param addr      7-bit I²C device address. PCF8574 default 0x20; PCF8574A 0x38.
 */
class PCF8574Minimal {
public:
    /** @brief GPIO proxy for a single PCF8574 pin.
     *
     * Obtain via PCF8574Minimal::pin(). Do not construct directly.
     * Reuses Arduino GPIO constants: INPUT, OUTPUT, HIGH, LOW.
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

    PCF8574Minimal(Connection& connection, uint8_t addr = 0x20);

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
    Connection& _connection;
    uint8_t    _addr;

    void    _write_port(uint8_t mask);
    uint8_t _read_port();
    void    _set_pin(uint8_t n, uint8_t value);
};

/** @brief PCF8574 full interface — extends PCF8574Minimal with interrupt support.
 *
 * Adds onInterrupt() to subscribe a callback to the chip's active-low INT
 * output (delivered via connection.intPin()), offInterrupt() to unsubscribe,
 * and pollInterrupt() to read pin states and return a changed-pin bitmask.
 * IOExpanderPin gains watch() / unwatch().
 *
 * INT-pin delivery is entirely handled by the InputPin connection.intPin()
 * was constructed with — this class contains no platform-specific interrupt
 * code (see specs/feature_connection_design.md §4).
 *
 * @param connection Configured I²C connection pointing at the device.
 * @param addr      7-bit I²C device address (default 0x20).
 */
class PCF8574Full : public PCF8574Minimal {
public:
    /** @brief GPIO proxy for a single PCF8574 pin — Full interface.
     *
     * Extends IOExpanderPin with watch() / unwatch().
     */
    class IOExpanderPin : public PCF8574Minimal::IOExpanderPin {
    public:
        /** @brief Construct a Full pin proxy.
         *  @param chip Parent PCF8574Full instance.
         *  @param n    Pin index 0–7.
         */
        IOExpanderPin(PCF8574Full& chip, uint8_t n);

        /** @brief Subscribe to this pin's edge events.
         *
         *  The handler is called with this pin as the argument when its state
         *  matches the trigger. At most one handler per pin at a time; a
         *  second watch() call replaces the first. Call the chip's
         *  onInterrupt() first to arm delivery.
         *
         *  @param handler Function pointer: void(*)(IOExpanderPin*).
         *  @param trigger InputPin::kFalling, ::kRising, or ::kChange (default).
         */
        void watch(void (*handler)(IOExpanderPin*), uint8_t trigger = InputPin::kChange);

        /** @brief Unsubscribe this pin's handler. No-op if not registered. */
        void unwatch();

    private:
        PCF8574Full& _full_chip;
        friend class PCF8574Full;
    };

    PCF8574Full(Connection& connection, uint8_t addr = 0x20);

    /** @brief Return a Full pin proxy for pin n (0–7). */
    IOExpanderPin pin(uint8_t n);

    /** @brief Subscribe to INT assertions.
     *
     *  Wires connection.intPin()->onEdge() to deliver edges. The callback
     *  receives the 8-bit bitmask of pins that changed since the previous read.
     *  No-op if connection.intPin() is nullptr (no polling fallback in C++ —
     *  use InputPinLinux for polling-thread delivery when no hardware IRQ
     *  line is wired).
     *
     *  @param callback void(*)(uint8_t changed_mask); called on any input change.
     */
    void onInterrupt(void (*callback)(uint8_t));

    /** @brief Unsubscribe and stop delivery. */
    void offInterrupt();

    /** @brief Read port and return bitmask of pins that changed since last read.
     *
     *  Compares current byte to previous read; also clears the INT line.
     *
     *  @return 8-bit bitmask; bit n = 1 if pin n changed.
     */
    uint8_t pollInterrupt();

private:
    struct PinWatch {
        void (*handler)(IOExpanderPin*) = nullptr;
        uint8_t trigger    = InputPin::kChange;
        uint8_t lastState  = 1;
    };

    uint8_t  _prev            = 0xFF;
    void   (*_callback)(uint8_t) = nullptr;
    PinWatch _pinWatches[8];

    void _handleEdge();

    static PCF8574Full* _activeInstance;
    static void _edgeTrampoline();
};
