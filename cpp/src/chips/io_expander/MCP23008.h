#pragma once
#include <stdint.h>
#include "../../connection/RegisterConnection.h"

#ifndef OUTPUT
#define INPUT        0
#define OUTPUT       1
#define INPUT_PULLUP 2
#define HIGH         1
#define LOW          0
#endif

/** @brief Shared implementation of the MCP23008 (I²C) and MCP23S08 (SPI) drivers — minimal interface.
 *
 *  The two chips have an identical register map and GPIO behaviour; they differ only in the
 *  serial interface. Use MCP23008Minimal (I²C) or MCP23S08Minimal (SPI) rather than this class.
 *
 *  Provides 8 bidirectional GPIO pins (GP0–GP7) as IOExpanderPin objects via pin(). Direction is
 *  explicit: IODIR bit = 1 means input, 0 means output. A shadow of OLAT and IODIR is kept so a
 *  single-pin change is one register write.
 */
class MCP23x08Minimal {
public:
    /** @brief GPIO proxy for a single pin; also satisfies the project's OutputPin contract.
     *
     *  Obtain via pin(). Reuses Arduino GPIO constants: INPUT, OUTPUT, INPUT_PULLUP, HIGH, LOW.
     *  An output-configured pin can be passed as @c en_pin of another chip's Connection.
     */
    class IOExpanderPin : public OutputPin {
    public:
        /** @brief Construct a pin proxy.
         *  @param chip Parent driver instance.
         *  @param n Pin index 0–7 (GP0–GP7).
         */
        IOExpanderPin(MCP23x08Minimal& chip, uint8_t n) : _chip(chip), _n(n) {}

        /** @brief Set pin direction.
         *  @param m INPUT, OUTPUT, or INPUT_PULLUP (pull-up is ignored in Minimal;
         *           use the Full driver for per-pin pull-up support).
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
        void toggle();

        /** @brief OutputPin interface.
         *  @param high true = high, false = low.
         */
        void set(bool high) override { write(high ? HIGH : LOW); }

    protected:
        MCP23x08Minimal& _chip;
        uint8_t _n;
    };

    /** @brief Return a pin proxy for pin n.
     *  @param n Pin index 0–7 (GP0–GP7).
     */
    IOExpanderPin pin(uint8_t n);

    /** @brief Read all 8 pins as a bitmask (GPIO register, actual pin levels).
     *  @param port Ignored; the chip has a single port.
     *  @return 8-bit bitmask; bit 0 = GP0.
     */
    uint8_t read_port(uint8_t port = 0);

    /** @brief Write all 8 output bits via OLAT and update the shadow register.
     *
     *  Bits of input pins are stored but have no effect until the pin becomes an output.
     *  @param port Ignored; the chip has a single port.
     *  @param mask 8-bit output mask; bit 0 = GP0.
     */
    void write_port(uint8_t port, uint8_t mask);

    static constexpr uint8_t REG_IODIR   = 0x00;
    static constexpr uint8_t REG_IPOL    = 0x01;
    static constexpr uint8_t REG_GPINTEN = 0x02;
    static constexpr uint8_t REG_DEFVAL  = 0x03;
    static constexpr uint8_t REG_INTCON  = 0x04;
    static constexpr uint8_t REG_IOCON   = 0x05;
    static constexpr uint8_t REG_GPPU    = 0x06;
    static constexpr uint8_t REG_INTF    = 0x07;
    static constexpr uint8_t REG_INTCAP  = 0x08;
    static constexpr uint8_t REG_GPIO    = 0x09;
    static constexpr uint8_t REG_OLAT    = 0x0A;

    static constexpr uint8_t IOCON_SEQOP  = 0x20;
    static constexpr uint8_t IOCON_HAEN   = 0x08;
    static constexpr uint8_t IOCON_ODR    = 0x04;
    static constexpr uint8_t IOCON_INTPOL = 0x02;

    uint8_t _shadow    = 0x00;  ///< Shadow of OLAT
    uint8_t _direction = 0xFF;  ///< Shadow of IODIR (1 = input)

protected:
    /** @param connection Raw connection (used directly for SPI).
     *  @param regConnection RegisterConnection for I²C, or nullptr for SPI.
     *  @param addr I²C address (I²C) or hardware address 0–3 (SPI).
     */
    MCP23x08Minimal(Connection& connection, RegisterConnection* regConnection, uint8_t addr);

    uint8_t _read_reg(uint8_t reg);
    void _write_reg(uint8_t reg, uint8_t value);
    void _write_olat(uint8_t mask);
    void _set_pin(uint8_t n, uint8_t value);
    void _set_direction(uint8_t n, bool input);

    Connection&         _connection;
    RegisterConnection* _reg;     ///< non-null for I²C, null for SPI
    uint8_t             _addr;
    uint8_t             _iocon;   ///< Shadow of IOCON

};

/** @brief MCP23008 8-bit I/O port expander (I²C) — minimal interface.
 *
 *  @param connection RegisterConnection (I²C or SMBus) pointing at the device.
 *  @param addr 7-bit I²C device address (default 0x20, range 0x20–0x27).
 */
class MCP23008Minimal : public MCP23x08Minimal {
public:
    explicit MCP23008Minimal(RegisterConnection& connection, uint8_t addr = 0x20);
};

/** @brief MCP23S08 8-bit I/O port expander (SPI) — minimal interface.
 *
 *  Every transaction is [0x40 | (addr << 1) | R/W] [reg] [data…]; the driver builds the header
 *  itself and talks to a plain Connection. IOCON.HAEN is set during construction so up to four
 *  devices can share one chip select.
 *
 *  @param connection SPI Connection (mode 0, MSB first, CS active low).
 *  @param addr Hardware device address 0–3 (A1:A0 pins).
 */
class MCP23S08Minimal : public MCP23x08Minimal {
public:
    explicit MCP23S08Minimal(Connection& connection, uint8_t addr = 0);
};

/** @brief Shared Full-stage implementation — pull-ups, polarity, interrupts, raw access.
 *
 *  Use MCP23008Full (I²C) or MCP23S08Full (SPI). INT-pin delivery is handled by the InputPin
 *  passed to onInterrupt() (or connection.intPin()); this class contains no platform-specific
 *  interrupt code. Without an INT pin, call service() from the main loop.
 */
class MCP23x08Full : public MCP23x08Minimal {
public:
    /** @brief Interrupt comparison mode for onInterrupt(). */
    enum IntMode : uint8_t {
        INT_ON_CHANGE = 0,  ///< compare against the previous pin value (INTCON = 0)
        INT_VS_DEFAULT = 1  ///< compare against DEFVAL (INTCON = 1)
    };

    /** @brief GPIO proxy for a single pin — Full interface.
     *
     *  Adds INPUT_PULLUP support and per-pin watch()/unwatch().
     */
    class IOExpanderPin : public MCP23x08Minimal::IOExpanderPin {
    public:
        /** @brief Construct a Full pin proxy.
         *  @param chip Parent Full driver instance.
         *  @param n Pin index 0–7.
         */
        IOExpanderPin(MCP23x08Full& chip, uint8_t n)
            : MCP23x08Minimal::IOExpanderPin(chip, n), _full(chip) {}

        /** @brief Set pin direction including pull-up.
         *  @param m INPUT, OUTPUT, or INPUT_PULLUP.
         */
        void mode(uint8_t m);

        /** @brief Subscribe to this pin's edge events (enables its GPINTEN bit).
         *
         *  At most one handler per pin; a second call replaces the first. The chip reports changes
         *  only; the rising/falling filter is applied in software. Not allowed on output pins.
         *
         *  @param handler Function pointer: void(*)(IOExpanderPin*).
         *  @param trigger InputPin::kFalling, ::kRising, or ::kChange (default).
         *  @return false if the pin is an output (nothing is registered).
         */
        bool watch(void (*handler)(IOExpanderPin*), uint8_t trigger = InputPin::kChange);

        /** @brief Unsubscribe this pin's handler and clear its GPINTEN bit. */
        void unwatch();

    private:
        MCP23x08Full& _full;
    };

    /** @brief Return a Full pin proxy for pin n (0–7). */
    IOExpanderPin pin(uint8_t n);

    /** @brief Enable/disable per-pin 100 kΩ pull-ups (GPPU ← mask); only effective on inputs.
     *  @param mask 8-bit mask; bit n = 1 enables the pull-up on GPn.
     */
    void configure_pullup(uint8_t mask);

    /** @brief Set input polarity inversion (IPOL ← mask).
     *  @param mask 8-bit mask; bit n = 1 inverts the GPIO read of GPn.
     */
    void configure_polarity(uint8_t mask);

    /** @brief Set DEFVAL for default-compare interrupts.
     *  @param mask 8-bit default compare value.
     */
    void set_default_value(uint8_t mask);

    /** @brief Arm interrupt-on-change pins (GPINTEN ← mask).
     *  @param mask 8-bit mask; bit n = 1 arms GPn (only inputs can interrupt).
     */
    void enable_pin_interrupt(uint8_t mask);

    /** @brief Configure the INT output (IOCON ODR / INTPOL).
     *  @param openDrain true for open-drain (overrides activeHigh).
     *  @param activeHigh true for active-high when push-pull.
     */
    void configure_int_pin(bool openDrain = false, bool activeHigh = false);

    /** @brief Enable or disable sequential register access (IOCON SEQOP).
     *  @param enabled true (default, auto-increment) or false.
     */
    void set_sequential(bool enabled);

    /** @brief Raw register read (debug / escape hatch).
     *  @param reg Register address 0x00–0x0A.
     */
    uint8_t read_register(uint8_t reg);

    /** @brief Raw register write; the IODIR / OLAT / IOCON / GPPU / GPINTEN shadows are kept in sync.
     *  @param reg Register address 0x00–0x0A.
     *  @param value 8-bit value.
     */
    void write_register(uint8_t reg, uint8_t value);

    /** @brief Rewrite every register to its reset value and clear the shadows.
     *
     *  The chip has no reset command (RESET is an external pin). On the MCP23S08 IOCON is
     *  rewritten to HAEN (not 0) so addressed access keeps working.
     */
    void reset();

    /** @brief Subscribe to INT assertions.
     *
     *  Every pin armed via enable_pin_interrupt() or watch() is used. Wires @p intPin->onEdge()
     *  (or connection.intPin() if @p intPin is nullptr). Without any InputPin, call service()
     *  periodically instead.
     *
     *  @param callback void(*)(uint8_t status); receives the INTF mask of the pins that caused it.
     *  @param intPin Optional InputPin overriding connection.intPin().
     *  @param mode INT_ON_CHANGE or INT_VS_DEFAULT.
     *  @return false if no pin is armed (nothing is registered).
     */
    bool onInterrupt(void (*callback)(uint8_t status), InputPin* intPin = nullptr,
                     uint8_t mode = INT_ON_CHANGE);

    /** @brief Unsubscribe and stop delivery (GPINTEN is left untouched). */
    void offInterrupt();

    /** @brief Read INTF, then INTCAP (clearing the interrupt); returns INTF.
     *  The captured port value is discarded — use read_capture().
     *  @return INTF bitmask; bit n = 1 if GPn caused the interrupt.
     */
    uint8_t pollInterrupt();

    /** @brief Read INTCAP: the port state captured at the interrupt; also clears the interrupt.
     *  @return 8-bit captured port value.
     */
    uint8_t read_capture();

    /** @brief Poll once and dispatch callbacks and pin watches (for use without an INT pin).
     *
     *  Call from the main loop; also invoked by the INT edge handler.
     */
    void service();

    uint8_t _pullup  = 0x00;  ///< Shadow of GPPU
    uint8_t _gpinten = 0x00;  ///< Shadow of GPINTEN

protected:
    MCP23x08Full(Connection& connection, RegisterConnection* regConnection, uint8_t addr);

    struct PinWatch {
        void (*handler)(IOExpanderPin*) = nullptr;
        uint8_t trigger = InputPin::kChange;
    };

    void (*_callback)(uint8_t status) = nullptr;
    InputPin* _intPinUsed = nullptr;
    PinWatch _pinWatches[8];
    bool _activeHigh = false;

    void _arm(InputPin* intPin);

    static MCP23x08Full* _activeInstance;
    static void _edgeTrampoline();
};

/** @brief MCP23008 full interface (I²C) — pull-ups, polarity, interrupts, raw access.
 *  @param connection RegisterConnection (I²C or SMBus) pointing at the device.
 *  @param addr 7-bit I²C device address (default 0x20).
 */
class MCP23008Full : public MCP23x08Full {
public:
    explicit MCP23008Full(RegisterConnection& connection, uint8_t addr = 0x20);
};

/** @brief MCP23S08 full interface (SPI) — pull-ups, polarity, interrupts, raw access.
 *  @param connection SPI Connection (mode 0, MSB first, CS active low).
 *  @param addr Hardware device address 0–3 (A1:A0 pins).
 */
class MCP23S08Full : public MCP23x08Full {
public:
    explicit MCP23S08Full(Connection& connection, uint8_t addr = 0);
};
