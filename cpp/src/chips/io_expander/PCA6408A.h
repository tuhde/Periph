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

/** @brief PCA6408A 8-bit I/O expander (I²C / SMBus) — minimal interface.
 *
 *  Provides 8 bidirectional GPIO pins (P0–P7) as IOExpanderPin objects via pin(). Direction is
 *  explicit: CONFIG bit = 1 means input, 0 means output. The chip has no internal pull-ups. A
 *  shadow of OUTPUT and CONFIG is kept so a single-pin change is one register write. All
 *  registers are accessed one byte at a time (the chip has no auto-increment).
 *
 *  @param connection RegisterConnection (I²C or SMBus, no PEC) pointing at the device.
 *  @param addr 7-bit I²C device address (0x20 with ADDR = VSS, 0x21 with ADDR = VDD(P));
 *              informational, the connection owns the address.
 */
class PCA6408AMinimal {
public:
    explicit PCA6408AMinimal(RegisterConnection& connection, uint8_t addr = 0x20);

    /** @brief GPIO proxy for a single pin; also satisfies the project's OutputPin contract.
     *
     *  Obtain via pin(). Reuses Arduino GPIO constants: INPUT, OUTPUT, HIGH, LOW.
     *  An output-configured pin can be passed as @c en_pin of another chip's Connection.
     */
    class IOExpanderPin : public OutputPin {
    public:
        /** @brief Construct a pin proxy.
         *  @param chip Parent driver instance.
         *  @param n Pin index 0–7 (P0–P7).
         */
        IOExpanderPin(PCA6408AMinimal& chip, uint8_t n) : _chip(chip), _n(n) {}

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
        PCA6408AMinimal& _chip;
        uint8_t _n;
    };

    /** @brief Return a pin proxy for pin n.
     *  @param n Pin index 0–7 (P0–P7).
     */
    IOExpanderPin pin(uint8_t n);

    /** @brief Read all 8 pins as a bitmask (INPUT register, actual pin levels).
     *  @param port Ignored; the chip has a single port.
     *  @return 8-bit bitmask; bit 0 = P0.
     */
    uint8_t read_port(uint8_t port = 0);

    /** @brief Write all 8 output bits via OUTPUT and update the shadow register.
     *
     *  Bits of input pins are stored but have no effect until the pin becomes an output.
     *  @param port Ignored; the chip has a single port.
     *  @param mask 8-bit output mask; bit 0 = P0.
     */
    void write_port(uint8_t port, uint8_t mask);

    static constexpr uint8_t REG_INPUT    = 0x00;
    static constexpr uint8_t REG_OUTPUT   = 0x01;
    static constexpr uint8_t REG_POLARITY = 0x02;
    static constexpr uint8_t REG_CONFIG   = 0x03;

    uint8_t _shadow    = 0xFF;  ///< Shadow of OUTPUT
    uint8_t _direction = 0xFF;  ///< Shadow of CONFIG (1 = input)

protected:
    uint8_t _read_reg(uint8_t reg);
    void _write_reg(uint8_t reg, uint8_t value);
    void _write_output(uint8_t mask);
    void _set_pin(uint8_t n, uint8_t value);
    void _set_direction(uint8_t n, bool input);

    RegisterConnection& _connection;
    uint8_t             _addr;
    uint8_t             _polarity  = 0x00;  ///< Shadow of POLARITY
    uint8_t             _lastInput = 0x00;  ///< Last INPUT value read (interrupt reference)
};

/** @brief PCA6408A full interface — polarity, whole-port direction, interrupts, raw access.
 *
 *  INT-pin delivery is handled by the InputPin passed to onInterrupt() (or connection.intPin());
 *  this class contains no platform-specific interrupt code. Without an INT pin, call service()
 *  from the main loop.
 *
 *  @param connection RegisterConnection (I²C or SMBus, no PEC) pointing at the device.
 *  @param addr 7-bit I²C device address (default 0x20).
 */
class PCA6408AFull : public PCA6408AMinimal {
public:
    explicit PCA6408AFull(RegisterConnection& connection, uint8_t addr = 0x20);

    /** @brief GPIO proxy for a single pin — Full interface; adds per-pin watch()/unwatch(). */
    class IOExpanderPin : public PCA6408AMinimal::IOExpanderPin {
    public:
        /** @brief Construct a Full pin proxy.
         *  @param chip Parent Full driver instance.
         *  @param n Pin index 0–7.
         */
        IOExpanderPin(PCA6408AFull& chip, uint8_t n)
            : PCA6408AMinimal::IOExpanderPin(chip, n), _full(chip) {}

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
        PCA6408AFull& _full;
    };

    /** @brief Return a Full pin proxy for pin n (0–7). */
    IOExpanderPin pin(uint8_t n);

    /** @brief Set input polarity inversion (POLARITY ← mask).
     *  @param mask 8-bit mask; bit n = 1 inverts the INPUT bit of Pn.
     */
    void configure_polarity(uint8_t mask);

    /** @brief Return the shadow CONFIG register (1 = input). */
    uint8_t get_direction();

    /** @brief Write CONFIG in one transaction (1 = input, 0 = output).
     *
     *  OUTPUT is written first so pins turning into outputs never glitch; the cached input value is
     *  refreshed afterwards to drop the false interrupt an output→input change can raise.
     *  @param mask 8-bit direction mask; bit n = 1 makes Pn an input.
     */
    void set_direction(uint8_t mask);

    /** @brief Raw register read (debug / escape hatch).
     *  @param reg Register address 0–3.
     */
    uint8_t read_register(uint8_t reg);

    /** @brief Raw register write (1–3); the OUTPUT / POLARITY / CONFIG shadows are kept in sync.
     *  @param reg Register address 1–3 (INPUT is read-only; reg 0 is ignored).
     *  @param value 8-bit value.
     *  @return false if reg is outside 1–3 (nothing is written).
     */
    bool write_register(uint8_t reg, uint8_t value);

    /** @brief Write the reset values (OUTPUT 0xFF, POLARITY 0x00, CONFIG 0xFF) and refresh the input cache.
     *
     *  The RESET pin is external and not driven by the driver.
     */
    void reset();

    /** @brief Subscribe to INT assertions.
     *
     *  Wires @p intPin->onEdge() (or connection.intPin() if @p intPin is nullptr) on the falling
     *  edge. Without any InputPin, call service() periodically instead.
     *
     *  @param callback void(*)(uint8_t changed); receives the mask of input pins that changed.
     *  @param intPin Optional InputPin overriding connection.intPin().
     */
    void onInterrupt(void (*callback)(uint8_t changed), InputPin* intPin = nullptr);

    /** @brief Unsubscribe and stop delivery. */
    void offInterrupt();

    /** @brief Read INPUT (clearing INT) and return the changed-pin mask.
     *
     *  XOR of the new and the cached INPUT value, limited to pins configured as inputs.
     *  @return 8-bit mask; bit n = 1 if input Pn changed.
     */
    uint8_t pollInterrupt();

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

    void (*_callback)(uint8_t changed) = nullptr;
    InputPin* _intPinUsed = nullptr;
    PinWatch _pinWatches[8];
    uint8_t _level = 0x00;  ///< INPUT value of the last pollInterrupt()

    void _arm(InputPin* intPin);

    static PCA6408AFull* _activeInstance;
    static void _edgeTrampoline();
};
