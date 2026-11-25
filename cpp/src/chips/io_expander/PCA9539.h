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

/** @brief PCA9539 16-bit I/O expander (I²C / SMBus) — minimal interface.
 *
 *  Provides 16 bidirectional GPIO pins (0–7 = P00–P07, 8–15 = P10–P17) as IOExpanderPin
 *  objects via pin(). Direction is explicit: CONFIG bit = 1 means input, 0 means output. The chip
 *  has no internal pull-ups. A 16-bit shadow of OUTPUT and CONFIG is kept so a single-pin change
 *  is one register write. The registers form pairs (port 0 / port 1): a two-byte transfer
 *  starting at a port-0 register covers both ports.
 *
 *  @param connection RegisterConnection (I²C or SMBus, no PEC) pointing at the device.
 *  @param addr 7-bit I²C device address (0x74 with A1 = A0 = GND … 0x77 with A1 = A0 = VCC);
 *              informational, the connection owns the address.
 */
class PCA9539Minimal {
public:
    explicit PCA9539Minimal(RegisterConnection& connection, uint8_t addr = 0x74);

    /** @brief GPIO proxy for a single pin; also satisfies the project's OutputPin contract.
     *
     *  Obtain via pin(). Reuses Arduino GPIO constants: INPUT, OUTPUT, HIGH, LOW.
     *  An output-configured pin can be passed as @c en_pin of another chip's Connection.
     */
    class IOExpanderPin : public OutputPin {
    public:
        /** @brief Construct a pin proxy.
         *  @param chip Parent driver instance.
         *  @param n Pin index 0–15 (0–7 = port 0, 8–15 = port 1).
         */
        IOExpanderPin(PCA9539Minimal& chip, uint8_t n) : _chip(chip), _n(n) {}

        /** @brief Set pin direction.
         *  @param m INPUT or OUTPUT. INPUT_PULLUP is rejected (the chip has no pull-ups).
         *  @return false if the request was rejected (nothing is written).
         */
        bool mode(uint8_t m);

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
        PCA9539Minimal& _chip;
        uint8_t _n;
    };

    /** @brief Return a pin proxy for pin n.
     *  @param n Pin index 0–15 (0–7 = port 0, 8–15 = port 1).
     */
    IOExpanderPin pin(uint8_t n);

    /** @brief Read one 8-pin port as a bitmask (INPUTx register, actual pin levels).
     *  @param port 0 (P00–P07) or 1 (P10–P17).
     *  @return 8-bit bitmask; bit 0 = first pin of the port.
     */
    uint8_t read_port(uint8_t port = 0);

    /** @brief Write all 8 output bits of a port via OUTPUTx and update the shadow register.
     *
     *  Bits of input pins are stored but have no effect until the pin becomes an output.
     *  @param port 0 (P00–P07) or 1 (P10–P17).
     *  @param mask 8-bit output mask; bit 0 = first pin of the port.
     */
    void write_port(uint8_t port, uint8_t mask);

    // Port-0 register addresses; port 1 is +1. A two-byte transfer at a port-0 register toggles to port 1.
    static constexpr uint8_t REG_INPUT    = 0x00;
    static constexpr uint8_t REG_OUTPUT   = 0x02;
    static constexpr uint8_t REG_POLARITY = 0x04;
    static constexpr uint8_t REG_CONFIG   = 0x06;

    uint16_t _shadow    = 0xFFFF;  ///< Shadow of OUTPUT0/1
    uint16_t _direction = 0xFFFF;  ///< Shadow of CONFIG0/1 (1 = input)

protected:
    uint8_t _read_reg(uint8_t reg);
    void _write_reg(uint8_t reg, uint8_t value);
    uint16_t _read16(uint8_t base);
    void _write16(uint8_t base, uint16_t value);
    void _write_output(uint16_t mask);
    void _set_pin(uint8_t n, uint8_t value);
    void _set_direction(uint8_t n, bool input);

    RegisterConnection& _connection;
    uint8_t             _addr;
    uint16_t            _polarity  = 0x0000;  ///< Shadow of POLARITY0/1
    uint16_t            _lastInput = 0x0000;  ///< Last INPUT0/1 value read (interrupt reference)
};

/** @brief PCA9539 full interface — polarity, whole-device direction, interrupts, raw access.
 *
 *  INT-pin delivery is handled by the InputPin passed to onInterrupt() (or connection.intPin());
 *  this class contains no platform-specific interrupt code. Without an INT pin, call service()
 *  from the main loop.
 *
 *  @param connection RegisterConnection (I²C or SMBus, no PEC) pointing at the device.
 *  @param addr 7-bit I²C device address (default 0x74).
 */
class PCA9539Full : public PCA9539Minimal {
public:
    explicit PCA9539Full(RegisterConnection& connection, uint8_t addr = 0x74);

    /** @brief GPIO proxy for a single pin — Full interface; adds per-pin watch()/unwatch(). */
    class IOExpanderPin : public PCA9539Minimal::IOExpanderPin {
    public:
        /** @brief Construct a Full pin proxy.
         *  @param chip Parent Full driver instance.
         *  @param n Pin index 0–15.
         */
        IOExpanderPin(PCA9539Full& chip, uint8_t n)
            : PCA9539Minimal::IOExpanderPin(chip, n), _full(chip) {}

        /** @brief Subscribe to this pin's edge events.
         *
         *  At most one handler per pin; a second call replaces the first. The chip reports changes
         *  only; the rising/falling filter is applied in software. Not allowed on output pins.
         *
         *  @param handler Function pointer: void(*)(IOExpanderPin*).
         *  @param trigger InputPin::kFalling, ::kRising, or ::kChange (default).
         *  @return false if the pin is an output (nothing is registered).
         */
        bool watch(void (*handler)(IOExpanderPin*), uint8_t trigger = InputPin::kChange);

        /** @brief Unsubscribe this pin's handler. */
        void unwatch();

    private:
        PCA9539Full& _full;
    };

    /** @brief Return a Full pin proxy for pin n (0–15). */
    IOExpanderPin pin(uint8_t n);

    /** @brief Read both INPUT registers in one two-byte transaction.
     *  @return 16-bit value; bit n = pin n (bits 0–7 port 0, 8–15 port 1).
     */
    uint16_t read_all();

    /** @brief Write both OUTPUT registers in one two-byte transaction and update the shadow.
     *  @param value 16-bit output value; bit n = pin n.
     */
    void write_all(uint16_t value);

    /** @brief Set input polarity inversion (POLARITY0/1 ← mask).
     *  @param mask 16-bit mask; bit n = 1 inverts the INPUT bit of pin n.
     */
    void configure_polarity(uint16_t mask);

    /** @brief Return the shadow CONFIG registers (1 = input) as a 16-bit value. */
    uint16_t get_direction();

    /** @brief Write CONFIG0/1 in one two-byte transaction (1 = input, 0 = output).
     *
     *  OUTPUT is written first so pins turning into outputs never glitch; the cached input value is
     *  refreshed afterwards to drop the false interrupt an output→input change can raise.
     *  @param mask 16-bit direction mask; bit n = 1 makes pin n an input.
     */
    void set_direction(uint16_t mask);

    /** @brief Raw register read (debug / escape hatch).
     *  @param reg Register address 0–7.
     */
    uint8_t read_register(uint8_t reg);

    /** @brief Raw register write (2–7); the OUTPUT / POLARITY / CONFIG shadows are kept in sync.
     *  @param reg Register address 2–7 (INPUT0/1 are read-only; reg 0 and 1 are rejected).
     *  @param value 8-bit value.
     *  @return false if reg is outside 2–7 (nothing is written).
     */
    bool write_register(uint8_t reg, uint8_t value);

    /** @brief Write the reset values (OUTPUT 0xFF, POLARITY 0x00, CONFIG 0xFF, both ports) and refresh the input cache.
     *
     *  The RESET pin is external and not driven by the driver.
     */
    void reset();

    /** @brief Subscribe to INT assertions.
     *
     *  Wires @p intPin->onEdge() (or connection.intPin() if @p intPin is nullptr) on the falling
     *  edge. Without any InputPin, call service() periodically instead.
     *
     *  @param callback void(*)(uint16_t changed); receives the 16-bit mask of input pins that changed.
     *  @param intPin Optional InputPin overriding connection.intPin().
     */
    void onInterrupt(void (*callback)(uint16_t changed), InputPin* intPin = nullptr);

    /** @brief Unsubscribe and stop delivery. */
    void offInterrupt();

    /** @brief Read both INPUT registers (clearing INT) and return the changed-pin mask.
     *
     *  XOR of the new and the cached INPUT value, limited to pins configured as inputs.
     *  @return 16-bit mask; bit n = 1 if input pin n changed.
     */
    uint16_t pollInterrupt();

    /** @brief Poll once and dispatch callbacks and pin watches (for use without an INT pin).
     *
     *  Call from the main loop; also invoked by the INT edge handler.
     */
    void service();

protected:
    struct PinWatch {
        void (*handler)(IOExpanderPin*) = nullptr;
        uint8_t trigger = InputPin::kChange;
    };

    void (*_callback)(uint16_t changed) = nullptr;
    InputPin* _intPinUsed = nullptr;
    PinWatch _pinWatches[16];
    uint16_t _level = 0x0000;  ///< INPUT value of the last pollInterrupt()

    void _arm(InputPin* intPin);

    static PCA9539Full* _activeInstance;
    static void _edgeTrampoline();
};
