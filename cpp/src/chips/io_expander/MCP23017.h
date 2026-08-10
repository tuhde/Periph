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

/** @brief MCP23017 16-bit I/O port expander — minimal interface.
 *
 *  Provides 16 GPIO pins (GPA0–GPA7, GPB0–GPB7) as IOExpanderPin objects via pin().
 *  Direction is explicit: IODIR bit = 1 means input, 0 means output.
 *  GPA7 and GPB7 are output-only per the MCP23017 datasheet.
 *  A shadow register is maintained for OLATA/OLATB so individual output pins can
 *  be set/cleared/toggled without a read-modify-write transaction.
 *
 *  @param connection Configured I²C connection pointing at the device.
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

    explicit MCP23017Minimal(Connection& connection, uint8_t addr = 0x20);

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

    Connection& _connection;
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
 *  interrupt-on-change or default-compare modes, and pollInterrupt().
 *
 *  Level 3: the chip has two independent INT lines (INTA/INTB), one per port.
 *  INT-pin delivery is entirely handled by InputPin instances passed to
 *  onInterrupt() (or connection.intPin() if none is given) — this class
 *  contains no platform-specific interrupt code (see
 *  specs/feature_connection_design.md §4, §9).
 *
 *  @param connection Configured I²C connection pointing at the device.
 *  @param addr 7-bit I²C device address (default 0x20).
 */
class MCP23017Full : public MCP23017Minimal {
public:
    /** @brief GPIO proxy for a single MCP23017 pin — Full interface.
     *
     *  Adds INPUT_PULLUP support and per-pin watch()/unwatch().
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

        /** @brief Subscribe to this pin's edge events.
         *
         *  At most one handler per pin at a time; a second watch() call
         *  replaces the first. Call the chip's onInterrupt() (for this pin's
         *  port) first to arm delivery.
         *
         *  @param handler Function pointer: void(*)(IOExpanderPin*).
         *  @param trigger InputPin::kFalling, ::kRising, or ::kChange (default).
         */
        void watch(void (*handler)(IOExpanderPin*), uint8_t trigger = InputPin::kChange);

        /** @brief Unsubscribe this pin's handler. No-op if not registered. */
        void unwatch();

    private:
        MCP23017Full& _full_chip;
        friend class MCP23017Full;
    };

    explicit MCP23017Full(Connection& connection, uint8_t addr = 0x20);

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

    /** @brief Subscribe to INT assertions on both ports.
     *
     *  The common case: both INT lines share one physical GPIO, either via
     *  IOCON.MIRROR (@p mirror = true) or external wiring. Wires
     *  @p intPin ->onEdge() (or connection.intPin() if @p intPin is nullptr)
     *  to poll both ports on every edge.
     *
     *  @param callback void(*)(uint8_t port, uint8_t status); called with the
     *         port that triggered and its INTF flag mask.
     *  @param intPin Optional InputPin for this call, overriding connection.intPin().
     *  @param mirror If true, sets IOCON.MIRROR so either port's interrupt
     *         activates both INTA and INTB.
     */
    void onInterrupt(void (*callback)(uint8_t port, uint8_t status),
                     InputPin* intPin = nullptr, bool mirror = false);

    /** @brief Subscribe to INT assertions on a single port.
     *
     *  Use this (with a dedicated InputPin per call) when INTA and INTB are
     *  wired to two separate GPIOs.
     *
     *  @param port 0 = PORTA, 1 = PORTB.
     *  @param callback void(*)(uint8_t status); called with the port's INTF flag mask.
     *  @param intPin Optional InputPin for this call, overriding connection.intPin().
     */
    void onInterrupt(uint8_t port, void (*callback)(uint8_t status), InputPin* intPin = nullptr);

    /** @brief Unsubscribe and stop delivery on both ports. */
    void offInterrupt();

    /** @brief Unsubscribe and stop delivery on a single port.
     *  @param port 0 = PORTA, 1 = PORTB.
     */
    void offInterrupt(uint8_t port);

    /** @brief Set DEFVAL register for default-compare interrupt mode.
     *  @param port 0 = PORTA, 1 = PORTB.
     *  @param mask 8-bit default compare value.
     */
    void set_default_value(uint8_t port, uint8_t mask);

    /** @brief Read & clear interrupt status; returns the raw INTF flag register.
     *
     *  Reads INTFA/INTFB (which pins triggered) then INTCAPA/INTCAPB (which
     *  clears the interrupt latch and re-arms it). Note that reading either
     *  INTCAP or GPIO clears the interrupt condition on this chip — call
     *  pollInterrupt() or read_capture() per event, not both.
     *
     *  @param port 0 = PORTA, 1 = PORTB.
     *  @return 8-bit interrupt flag mask; bit n = 1 if pin n triggered.
     */
    uint8_t pollInterrupt(uint8_t port);

    /** @brief Read INTCAP: the port state latched at the moment of the interrupt.
     *
     *  Also clears the interrupt latch (see pollInterrupt() doc) — call this
     *  instead of pollInterrupt() when the captured pin state is wanted
     *  rather than the flag register.
     *
     *  @param port 0 = PORTA, 1 = PORTB.
     *  @return 8-bit captured port bitmask at the moment of interrupt.
     */
    uint8_t read_capture(uint8_t port);

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
    struct PinWatch {
        void (*handler)(IOExpanderPin*) = nullptr;
        uint8_t trigger    = InputPin::kChange;
        uint8_t lastState  = 1;
    };

    void (*_callbackBoth)(uint8_t port, uint8_t status) = nullptr;
    void (*_callbackPort[2])(uint8_t status) = { nullptr, nullptr };
    InputPin* _intPinUsed[2] = { nullptr, nullptr };
    PinWatch _pinWatches[16];

    void _armPort(uint8_t port, InputPin* intPin);
    void _handleEdge(uint8_t port);

    static MCP23017Full* _activeInstance;
    static void _edgeTrampolineA();
    static void _edgeTrampolineB();
};