#pragma once
#include <stdint.h>
#include "../../transport/Transport.h"

/** @brief INA226 36V, 16-bit current/voltage/power monitor — minimal interface.
 *
 * Provides bus voltage, shunt voltage, current, and power readings with no
 * configuration beyond the transport and shunt resistor. The Calibration
 * Register is written automatically at construction.
 *
 * Default configuration (written at construction):
 * - MODE = 7: shunt + bus, continuous
 * - VBUSCT = 4: 1.1 ms bus voltage conversion time
 * - VSHCT = 4: 1.1 ms shunt voltage conversion time
 * - AVG = 0: 1 sample (no averaging)
 *
 * @param transport   Configured I²C or SMBus transport pointing at the device.
 * @param r_shunt     Shunt resistor value in ohms (default 0.1).
 * @param max_current Maximum expected current in amperes (default 2.0).
 */
class INA226Minimal {
public:
    INA226Minimal(Transport& transport, float r_shunt = 0.1f, float max_current = 2.0f);

    /** @brief Read bus voltage.
     *  @return Bus voltage in volts (raw × 1.25 mV LSB).
     */
    float voltage();

    /** @brief Read differential shunt voltage.
     *  @return Shunt voltage in volts, signed (raw × 2.5 µV LSB).
     */
    float shunt_voltage();

    /** @brief Read calculated current through the shunt.
     *  @return Current in amperes, signed.
     */
    float current();

    /** @brief Read calculated power.
     *  @return Power in watts (raw × 25 × current LSB).
     */
    float power();

protected:
    static constexpr uint8_t  REG_CONFIG      = 0x00;
    static constexpr uint8_t  REG_SHUNT       = 0x01;
    static constexpr uint8_t  REG_BUS         = 0x02;
    static constexpr uint8_t  REG_POWER       = 0x03;
    static constexpr uint8_t  REG_CURRENT     = 0x04;
    static constexpr uint8_t  REG_CAL         = 0x05;
    static constexpr uint16_t CONFIG_DEFAULT  = 0x4127;

    Transport& _transport;
    float      _current_lsb;
    uint16_t   _cal;

    void     _write_reg(uint8_t reg, uint16_t value);
    uint16_t _read_reg(uint8_t reg);
    int16_t  _read_reg_signed(uint8_t reg);
};

/** @brief INA226 full interface — extends INA226Minimal with configuration and alert support.
 *
 * Adds Configuration Register programming, conversion-ready and overflow status,
 * alert configuration, reset, and shutdown/wake.
 *
 * Alert function constants (pass to set_alert):
 * - SOL  — shunt voltage over-limit
 * - SUL  — shunt voltage under-limit
 * - BOL  — bus voltage over-limit
 * - BUL  — bus voltage under-limit
 * - POL  — power over-limit
 * - CNVR — conversion ready
 *
 * @param transport   Configured I²C or SMBus transport pointing at the device.
 * @param r_shunt     Shunt resistor value in ohms (default 0.1).
 * @param max_current Maximum expected current in amperes (default 2.0).
 */
class INA226Full : public INA226Minimal {
public:
    static constexpr uint16_t SOL  = 0x8000;
    static constexpr uint16_t SUL  = 0x4000;
    static constexpr uint16_t BOL  = 0x2000;
    static constexpr uint16_t BUL  = 0x1000;
    static constexpr uint16_t POL  = 0x0800;
    static constexpr uint16_t CNVR = 0x0400;
    static constexpr uint16_t AFF  = 0x0010;

    INA226Full(Transport& transport, float r_shunt = 0.1f, float max_current = 2.0f);

    /** @brief Write the Configuration Register.
     *  @param avg     Averaging count selector 0–7 (0 = 1 sample … 7 = 1024 samples).
     *  @param vbus_ct Bus voltage conversion time selector 0–7 (default 4 = 1.1 ms).
     *  @param vsh_ct  Shunt voltage conversion time selector 0–7 (default 4 = 1.1 ms).
     *  @param mode    Operating mode 0–7 (7 = shunt+bus continuous).
     */
    void configure(uint8_t avg = 0, uint8_t vbus_ct = 4, uint8_t vsh_ct = 4, uint8_t mode = 7);

    /** @brief Read the Conversion Ready Flag (CVRF) from the Mask/Enable Register.
     *
     *  @note Reading Mask/Enable clears CVRF. Read it last if also checking other flags.
     *  @return true if a conversion completed since the last Mask/Enable read.
     */
    bool conversion_ready();

    /** @brief Read the Math Overflow Flag (OVF) from the Mask/Enable Register.
     *  @return true if an arithmetic overflow occurred in the power calculation.
     */
    bool overflow();

    /** @brief Configure the alert pin function and threshold.
     *
     *  Only one alert function can be active at a time. @p limit is in natural
     *  units — volts for SOL/SUL/BOL/BUL, watts for POL.
     *
     *  @param function Alert function constant (SOL, SUL, BOL, BUL, POL, or CNVR).
     *  @param limit    Threshold in natural units (default 0).
     *  @param polarity false = active-low (default), true = active-high.
     *  @param latch    false = transparent (default), true = latch until Mask/Enable is read.
     */
    void set_alert(uint16_t function, float limit = 0.0f, bool polarity = false, bool latch = false);

    /** @brief Read the Mask/Enable Register.
     *  @return Raw 16-bit value containing alert and status flags.
     */
    uint16_t alert_flags();

    /** @brief Reset all registers to power-on defaults, then re-write the Calibration Register. */
    void reset();

    /** @brief Enter power-down mode (MODE = 000) and save the current mode for wake(). */
    void shutdown();

    /** @brief Restore the operating mode saved by shutdown(). */
    void wake();

    /** @brief Read the Manufacturer ID register.
     *  @return Manufacturer ID; expect 0x5449 (Texas Instruments).
     */
    uint16_t manufacturer_id();

    /** @brief Read the Die ID register.
     *  @return Die revision ID; expect 0x2260.
     */
    uint16_t die_id();

private:
    static constexpr uint8_t REG_MASK   = 0x06;
    static constexpr uint8_t REG_ALERT  = 0x07;
    static constexpr uint8_t REG_MFR_ID = 0xFE;
    static constexpr uint8_t REG_DIE_ID = 0xFF;

    uint8_t _mode = 0x07;
};
