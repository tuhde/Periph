#pragma once
#include <stdint.h>
#include "../../transport/Transport.h"

/** @brief INA219 26V, 12-bit current/voltage/power monitor — minimal interface.
 *
 * Provides bus voltage, shunt voltage, current, and power readings with no
 * configuration beyond the transport and shunt resistor. The Calibration
 * Register is written automatically at construction.
 *
 * Default chip configuration (power-on defaults, not rewritten):
 * - BRNG = 1: 32 V bus full-scale range
 * - PG = 11: PGA ÷8, ±320 mV shunt full-scale
 * - BADC = 0011: 12-bit, 532 µs
 * - SADC = 0011: 12-bit, 532 µs
 * - MODE = 111: shunt + bus, continuous
 *
 * @param transport   Configured I²C or SMBus transport pointing at the device.
 * @param r_shunt    Shunt resistor value in ohms (default 0.1).
 * @param max_current Maximum expected current in amperes (default 2.0).
 */
class INA219Minimal {
public:
    INA219Minimal(Transport& transport, float r_shunt = 0.1f, float max_current = 2.0f);

    /** @brief Read bus voltage.
     *  @return Bus voltage in volts ((raw >> 3) × 4 mV LSB).
     */
    float voltage();

    /** @brief Read differential shunt voltage.
     *  @return Shunt voltage in volts, signed (raw × 10 µV LSB).
     */
    float shunt_voltage();

    /** @brief Read calculated current through the shunt.
     *  @return Current in amperes, signed.
     */
    float current();

    /** @brief Read calculated power.
     *  @return Power in watts (raw × 20 × current LSB).
     */
    float power();

protected:
    static constexpr uint8_t  REG_CONFIG  = 0x00;
    static constexpr uint8_t  REG_SHUNT   = 0x01;
    static constexpr uint8_t  REG_BUS     = 0x02;
    static constexpr uint8_t  REG_POWER   = 0x03;
    static constexpr uint8_t  REG_CURRENT = 0x04;
    static constexpr uint8_t  REG_CAL     = 0x05;

    Transport& _transport;
    float      _current_lsb;
    uint16_t   _cal;

    void     _write_reg(uint8_t reg, uint16_t value);
    uint16_t _read_reg(uint8_t reg);
    int16_t  _read_reg_signed(uint8_t reg);
};

/** @brief INA219 full interface — extends INA219Minimal with full configuration and power management.
 *
 * Adds Configuration Register programming (bus range, PGA, ADC resolution/averaging, mode),
 * conversion-ready and overflow status, reset, and shutdown/wake.
 *
 * @param transport   Configured I²C or SMBus transport pointing at the device.
 * @param r_shunt    Shunt resistor value in ohms (default 0.1).
 * @param max_current Maximum expected current in amperes (default 2.0).
 */
class INA219Full : public INA219Minimal {
public:
    static constexpr uint8_t BRNG_16V = 0;
    static constexpr uint8_t BRNG_32V = 1;

    static constexpr uint8_t PGA_1 = 0;
    static constexpr uint8_t PGA_2 = 1;
    static constexpr uint8_t PGA_4 = 2;
    static constexpr uint8_t PGA_8 = 3;

    static constexpr uint8_t ADC_9BIT    = 0x00;
    static constexpr uint8_t ADC_10BIT   = 0x01;
    static constexpr uint8_t ADC_11BIT   = 0x02;
    static constexpr uint8_t ADC_12BIT   = 0x03;
    static constexpr uint8_t ADC_AVG_2    = 0x09;
    static constexpr uint8_t ADC_AVG_4    = 0x0A;
    static constexpr uint8_t ADC_AVG_8    = 0x0B;
    static constexpr uint8_t ADC_AVG_16   = 0x0C;
    static constexpr uint8_t ADC_AVG_32   = 0x0D;
    static constexpr uint8_t ADC_AVG_64   = 0x0E;
    static constexpr uint8_t ADC_AVG_128  = 0x0F;

    static constexpr uint8_t MODE_POWERDOWN       = 0;
    static constexpr uint8_t MODE_SHUNT_TRIG     = 1;
    static constexpr uint8_t MODE_BUS_TRIG       = 2;
    static constexpr uint8_t MODE_SHUNT_BUS_TRIG = 3;
    static constexpr uint8_t MODE_ADC_OFF        = 4;
    static constexpr uint8_t MODE_SHUNT_CONT     = 5;
    static constexpr uint8_t MODE_BUS_CONT       = 6;
    static constexpr uint8_t MODE_SHUNT_BUS_CONT = 7;

    INA219Full(Transport& transport, float r_shunt = 0.1f, float max_current = 2.0f);

    /** @brief Write the Configuration Register.
     *  @param brng  Bus voltage range — 0 = 16 V FSR, 1 = 32 V FSR (default 1).
     *  @param pga   Shunt PGA gain — 0 = ÷1, 1 = ÷2, 2 = ÷4, 3 = ÷8 (default 3).
     *  @param badc  Bus ADC resolution/averaging — 0x00–0x0F (default 0x03 = 12-bit).
     *  @param sadc  Shunt ADC resolution/averaging — 0x00–0x0F (default 0x03 = 12-bit).
     *  @param mode  Operating mode 0–7 (default 7 = shunt+bus continuous).
     */
    void configure(uint8_t brng = 1, uint8_t pga = 3, uint8_t badc = 0x03, uint8_t sadc = 0x03, uint8_t mode = 7);

    /** @brief Read the Conversion Ready Flag (CNVR) from the Bus Voltage register.
     *  @return true if a conversion completed since the last read.
     */
    bool conversion_ready();

    /** @brief Read the Math Overflow Flag (OVF) from the Bus Voltage register.
     *  @return true if an arithmetic overflow occurred in current/power calculation.
     */
    bool overflow();

    /** @brief Reset all registers to power-on defaults, then re-write the Calibration Register. */
    void reset();

    /** @brief Enter power-down mode (MODE = 000) and save the current mode for wake(). */
    void shutdown();

    /** @brief Restore the operating mode saved by shutdown(). */
    void wake();

    /** @brief Re-write the current mode to trigger a single-shot conversion.
     *
     *  Only effective when the current mode is a triggered mode (1, 2, or 3).
     */
    void trigger();

private:
    uint8_t _saved_mode = MODE_SHUNT_BUS_CONT;
};
