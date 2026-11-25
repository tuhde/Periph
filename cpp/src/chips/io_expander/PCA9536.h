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

/** @brief PCA9536 4-bit I/O expander (I²C / SMBus) — minimal interface.
 *
 *  Provides 4 bidirectional GPIO pins (P0–P3) as IOExpanderPin objects via pin(). Direction is
 *  explicit: CONFIG bit = 1 means input, 0 means output. Every I/O pin has a weak (about
 *  100 kΩ) pull-up that is always on and cannot be switched (an undriven input reads 1). A
 *  shadow of OUTPUT and CONFIG is kept so a single-pin change is one register write. All
 *  registers are accessed one byte at a time (the chip has no auto-increment).
 *
 *  @param connection RegisterConnection (I²C or SMBus, no PEC) pointing at the device.
 *  @param addr 7-bit I²C device address (fixed 0x41); informational, the
 *              connection owns the address.
 */
class PCA9536Minimal {
public:
    explicit PCA9536Minimal(RegisterConnection& connection, uint8_t addr = 0x41);

    /** @brief GPIO proxy for a single pin; also satisfies the project's OutputPin contract.
     *
     *  Obtain via pin(). Reuses Arduino GPIO constants: INPUT, OUTPUT, HIGH, LOW.
     *  An output-configured pin can be passed as @c en_pin of another chip's Connection.
     */
    class IOExpanderPin : public OutputPin {
    public:
        /** @brief Construct a pin proxy.
         *  @param chip Parent driver instance.
         *  @param n Pin index 0–3 (P0–P3).
         */
        IOExpanderPin(PCA9536Minimal& chip, uint8_t n) : _chip(chip), _n(n) {}

        /** @brief Set pin direction.
         *  @param m INPUT or OUTPUT. INPUT_PULLUP is rejected (the weak pull-up is fixed in hardware).
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
        PCA9536Minimal& _chip;
        uint8_t _n;
    };

    /** @brief Return a pin proxy for pin n.
     *  @param n Pin index 0–3 (P0–P3).
     */
    IOExpanderPin pin(uint8_t n);

    /** @brief Read all 4 pins as a bitmask (INPUT register, actual pin levels).
     *  @param port Ignored; the chip has a single port.
     *  @return 4-bit bitmask; bit 0 = P0.
     */
    uint8_t read_port(uint8_t port = 0);

    /** @brief Write all 4 output bits via OUTPUT and update the shadow register.
     *
     *  Bits of input pins are stored but have no effect until the pin becomes an output.
     *  @param port Ignored; the chip has a single port.
     *  @param mask 4-bit output mask; bit 0 = P0.
     */
    void write_port(uint8_t port, uint8_t mask);

    static constexpr uint8_t REG_INPUT    = 0x00;
    static constexpr uint8_t REG_OUTPUT   = 0x01;
    static constexpr uint8_t REG_POLARITY = 0x02;
    static constexpr uint8_t REG_CONFIG   = 0x03;
    static constexpr uint8_t MASK         = 0x0F;  ///< bits 7:4 are unused

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
    uint8_t             _lastInput = 0x00;  ///< Last INPUT value read (change-detection reference)
};

/** @brief PCA9536 full interface — polarity, whole-port direction, change polling, raw access.
 *
 *  The chip has no interrupt output, so change detection is by polling: call service() from the
 *  main loop (or a timer/task). pollChanges() returns the changed-pin mask; watchChanges() and
 *  the per-pin watch() register callbacks that service() dispatches.
 *
 *  @param connection RegisterConnection (I²C or SMBus, no PEC) pointing at the device.
 *  @param addr 7-bit I²C device address (fixed 0x41).
 */
class PCA9536Full : public PCA9536Minimal {
public:
    explicit PCA9536Full(RegisterConnection& connection, uint8_t addr = 0x41);

    /** @brief GPIO proxy for a single pin — Full interface; adds per-pin watch()/unwatch(). */
    class IOExpanderPin : public PCA9536Minimal::IOExpanderPin {
    public:
        /** @brief Construct a Full pin proxy.
         *  @param chip Parent Full driver instance.
         *  @param n Pin index 0–3.
         */
        IOExpanderPin(PCA9536Full& chip, uint8_t n)
            : PCA9536Minimal::IOExpanderPin(chip, n), _full(chip) {}

        /** @brief Subscribe to this pin's edge events.
         *
         *  At most one handler per pin; a second call replaces the first. The chip reports changes
         *  only; the rising/falling filter is applied in software, driven by service(). Not
         *  allowed on output pins.
         *
         *  @param handler Function pointer: void(*)(IOExpanderPin*).
         *  @param trigger InputPin::kFalling, ::kRising, or ::kChange (default).
         *  @return false if the pin is an output (nothing is registered).
         */
        bool watch(void (*handler)(IOExpanderPin*), uint8_t trigger = InputPin::kChange);

        /** @brief Unsubscribe this pin's handler. */
        void unwatch();

    private:
        PCA9536Full& _full;
    };

    /** @brief Return a Full pin proxy for pin n (0–3). */
    IOExpanderPin pin(uint8_t n);

    /** @brief Set input polarity inversion (POLARITY ← mask).
     *
     *  The cached input value is refreshed, so the polarity change itself is not reported by
     *  pollChanges().
     *  @param mask 4-bit mask; bit n = 1 inverts the INPUT bit of Pn.
     */
    void configure_polarity(uint8_t mask);

    /** @brief Return the shadow CONFIG register (1 = input), 4 bits. */
    uint8_t get_direction();

    /** @brief Write CONFIG in one transaction (1 = input, 0 = output).
     *
     *  OUTPUT is written first so pins turning into outputs never glitch; the cached input value is
     *  refreshed afterwards.
     *  @param mask 4-bit direction mask; bit n = 1 makes Pn an input.
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
     *  The chip has no RESET pin; only a power cycle clears a lock-up.
     */
    void reset();

    /** @brief Register a callback for input changes.
     *
     *  The chip has no interrupt output; the callback is invoked from service(), which the
     *  application calls periodically (about every 5 ms).
     *
     *  @param callback void(*)(uint8_t changed); receives the mask of input pins that changed.
     */
    void watchChanges(void (*callback)(uint8_t changed));

    /** @brief Remove the callback registered by watchChanges(). */
    void unwatchChanges();

    /** @brief Read INPUT and return the changed-pin mask (software edge detection).
     *
     *  XOR of the new and the cached INPUT value, limited to pins configured as inputs. The new
     *  value is cached.
     *  @return 4-bit mask; bit n = 1 if input Pn changed.
     */
    uint8_t pollChanges();

    /** @brief Poll once and dispatch the watchChanges() callback and pin watches.
     *
     *  Call from the main loop.
     */
    void service();

protected:
    struct PinWatch {
        void (*handler)(IOExpanderPin*) = nullptr;
        uint8_t trigger = InputPin::kChange;
    };

    void (*_callback)(uint8_t changed) = nullptr;
    PinWatch _pinWatches[4];
    uint8_t _level = 0x00;  ///< INPUT value of the last pollChanges()
};
