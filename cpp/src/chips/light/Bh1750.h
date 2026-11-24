#pragma once
#include <stdint.h>
#include "../../connection/Connection.h"

/** @brief BH1750FVI ambient light sensor (ROHM) — minimal interface.
 *
 * Each read() starts one one-time H-resolution measurement (opcode 0x20, 1 lx steps), waits the
 * datasheet maximum of 180 ms, reads the 16-bit result and returns the illuminance in lux
 * (raw / 1.2). The chip powers down by itself after the measurement.
 *
 * The device is command driven (no register pointer), so a plain Connection is used.
 *
 * @param connection Connection (I²C) pointing at the device (address 0x23 or 0x5C). The
 *        constructor does no bus access; init() powers the chip on.
 */
class BH1750Minimal {
public:
    static constexpr uint8_t MODE_H  = 0;  ///< H-resolution, 1 lx steps
    static constexpr uint8_t MODE_H2 = 1;  ///< H-resolution mode 2, 0.5 lx steps
    static constexpr uint8_t MODE_L  = 2;  ///< L-resolution, 4 lx steps

    BH1750Minimal(Connection& connection);

    /** @brief Power the chip on (opcode 0x01). */
    void init();

    /** @brief Measure the illuminance once.
     *  @param[out] lux Illuminance in lux (one-time measurement in the current mode, H-resolution by default).
     *  @return true on success; false if a bus transfer failed.
     */
    bool read(float& lux);

protected:
    static constexpr uint8_t OP_POWER_DOWN = 0x00;
    static constexpr uint8_t OP_POWER_ON   = 0x01;
    static constexpr uint8_t OP_RESET      = 0x07;
    static constexpr uint8_t OP_MT_HIGH    = 0x40;
    static constexpr uint8_t OP_MT_LOW     = 0x60;
    static constexpr uint8_t DEFAULT_MTREG = 69;

    Connection& _connection;
    uint8_t _mtreg = DEFAULT_MTREG;
    uint8_t _mode  = MODE_H;

    void _command(uint8_t code);
    unsigned _wait_ms(uint8_t mode) const;
    float _to_lux(uint16_t raw, uint8_t mode) const;
    bool _read_word(uint16_t& raw);
    bool _measure_raw(uint8_t mode, uint16_t& raw);
};

/** @brief BH1750FVI full interface — all measurement modes, continuous mode, sensitivity, power control.
 *
 * Extends BH1750Minimal with raw codes, one-time measurements in each mode, continuous
 * measurement, power-down/power-on, reset, the measurement-time register (MTreg) and sensitivity
 * adjustment. read() uses the mode of the last measure_once() or start_continuous() call
 * (H-resolution by default).
 *
 * @param connection Connection (I²C) pointing at the device (address 0x23 or 0x5C).
 */
class BH1750Full : public BH1750Minimal {
public:
    BH1750Full(Connection& connection);

    /** @brief Run one one-time measurement in the current mode and return the raw 16-bit count.
     *  @return true on success.
     */
    bool read_raw(uint16_t& raw);

    /** @brief Run one one-time measurement.
     *  @param mode MODE_H (1 lx), MODE_H2 (0.5 lx) or MODE_L (4 lx).
     *  @param[out] lux Illuminance in lux.
     *  @return true on success; false on a bus error or an invalid mode.
     */
    bool measure_once(uint8_t mode, float& lux);

    /** @brief Start continuous measurements; the first result is ready after measurement_time(mode).
     *  @return false on a bus error or an invalid mode.
     */
    bool start_continuous(uint8_t mode = MODE_H);

    /** @brief Read the latest continuous-mode result without waiting.
     *  @return true on success.
     */
    bool read_continuous(float& lux);

    /** @brief Enter power-down (opcode 0x00). */
    void power_down();

    /** @brief Leave power-down and wait for a measurement command (opcode 0x01). */
    void power_on();

    /** @brief Clear the illuminance data register (opcode 0x07; powers on first, as reset is ignored in power-down). */
    void reset();

    /** @brief Write the measurement-time register (two opcodes).
     *  @param mtreg Register value, 31-254 (default 69).
     *  @return false if mtreg is outside 31-254 (nothing is written) or a bus transfer failed.
     */
    bool set_measurement_time(uint8_t mtreg);

    /** @brief The last written MTreg (the chip has no read-back; 69 after init). */
    uint8_t get_measurement_time_register() const;

    /** @brief Set the sensitivity relative to the default (MTreg = round(69 * factor)).
     *  @param factor 0.45-3.68.
     *  @return false if the resulting MTreg is outside 31-254.
     */
    bool set_sensitivity(float factor);

    /** @brief Maximum measurement time of a mode at the current MTreg, in seconds. */
    float measurement_time(uint8_t mode = MODE_H) const;

    /** @brief Illuminance represented by one raw count at the current MTreg, in lux. */
    float lux_per_count(uint8_t mode = MODE_H) const;
};
