#pragma once
#include <stdint.h>
#include "../../connection/RegisterConnection.h"

/** @brief INA260 36V, 16-bit current/voltage/power monitor with integrated shunt — minimal interface.
 *
 * Provides bus voltage, current, and power readings with no configuration
 * beyond the connection. The shunt is integrated and the current/power
 * registers have fixed LSBs, so no calibration is needed and the constructor
 * makes no bus access.
 *
 * Default configuration (chip power-on state, not rewritten):
 * - MODE = 7: shunt current + bus voltage, continuous
 * - VBUSCT = 4: 1.1 ms bus voltage conversion time
 * - ISHCT = 4: 1.1 ms shunt current conversion time
 * - AVG = 0: 1 sample (no averaging)
 *
 * @param connection RegisterConnection (I²C or SMBus) pointing at the device.
 */
class INA260Minimal {
public:
    explicit INA260Minimal(RegisterConnection& connection);

    /** @brief Read bus voltage.
     *  @return Bus voltage in volts (raw × 1.25 mV LSB).
     */
    float voltage();

    /** @brief Read current through the integrated shunt.
     *  @return Current in amperes, signed (raw × 1.25 mA LSB); positive flows IN+ → IN−.
     */
    float current();

    /** @brief Read calculated power.
     *  @return Power in watts (raw × 10 mW LSB), always positive.
     */
    float power();

protected:
    static constexpr uint8_t  REG_CONFIG  = 0x00;
    static constexpr uint8_t  REG_CURRENT = 0x01;
    static constexpr uint8_t  REG_BUS     = 0x02;
    static constexpr uint8_t  REG_POWER   = 0x03;

    RegisterConnection& _connection;

    void     _write_reg(uint8_t reg, uint16_t value);
    uint16_t _read_reg(uint8_t reg);
    int16_t  _read_reg_signed(uint8_t reg);
};

/** @brief INA260 full interface — extends INA260Minimal with configuration and alert support.
 *
 * Adds Configuration Register programming, triggered conversions,
 * conversion-ready and overflow status, alert configuration, reset, and
 * shutdown/wake.
 *
 * Alert function constants (pass to set_alert):
 * - OCL  — over-current limit
 * - UCL  — under-current limit
 * - BOL  — bus voltage over-limit
 * - BUL  — bus voltage under-limit
 * - POL  — power over-limit
 * - CNVR — conversion ready
 *
 * @param connection RegisterConnection (I²C or SMBus) pointing at the device.
 */
class INA260Full : public INA260Minimal {
public:
    static constexpr uint16_t OCL  = 0x8000;
    static constexpr uint16_t UCL  = 0x4000;
    static constexpr uint16_t BOL  = 0x2000;
    static constexpr uint16_t BUL  = 0x1000;
    static constexpr uint16_t POL  = 0x0800;
    static constexpr uint16_t CNVR = 0x0400;
    static constexpr uint16_t AFF  = 0x0010;

    static constexpr uint8_t AVG_1 = 0, AVG_4 = 1, AVG_16 = 2, AVG_64 = 3,
                             AVG_128 = 4, AVG_256 = 5, AVG_512 = 6, AVG_1024 = 7;
    static constexpr uint8_t CT_140US = 0, CT_204US = 1, CT_332US = 2, CT_588US = 3,
                             CT_1100US = 4, CT_2116US = 5, CT_4156US = 6, CT_8244US = 7;
    static constexpr uint8_t MODE_POWER_DOWN = 0, MODE_SHUNT_TRIGGERED = 1,
                             MODE_BUS_TRIGGERED = 2, MODE_SHUNT_BUS_TRIGGERED = 3,
                             MODE_SHUNT_CONT = 5, MODE_BUS_CONT = 6, MODE_SHUNT_BUS_CONT = 7;

    explicit INA260Full(RegisterConnection& connection);

    /** @brief Write the Configuration Register (bits 14:12 kept at 110).
     *  @param avg     Averaging count selector 0–7 (AVG_1 … AVG_1024).
     *  @param vbus_ct Bus voltage conversion time selector 0–7 (default 4 = 1.1 ms).
     *  @param ish_ct  Shunt current conversion time selector 0–7 (default 4 = 1.1 ms).
     *  @param mode    Operating mode 0–7 (7 = shunt + bus continuous).
     */
    void configure(uint8_t avg = 0, uint8_t vbus_ct = 4, uint8_t ish_ct = 4, uint8_t mode = 7);

    /** @brief Start one triggered conversion with the current settings.
     *
     *  Rewrites the Configuration Register; poll conversion_ready() for completion.
     *  @param mode MODE_SHUNT_TRIGGERED, MODE_BUS_TRIGGERED or MODE_SHUNT_BUS_TRIGGERED (default).
     */
    void trigger(uint8_t mode = MODE_SHUNT_BUS_TRIGGERED);

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
     *  Writes the Alert Limit register first, then Mask/Enable. Only one alert
     *  function can be active at a time. @p limit is in natural units —
     *  amperes for OCL/UCL, volts for BOL/BUL, watts for POL; ignored for CNVR
     *  (the limit register is not written).
     *
     *  @param function Alert function constant (OCL, UCL, BOL, BUL, POL, or CNVR).
     *  @param limit    Threshold in natural units (default 0).
     *  @param polarity false = active-low (default), true = active-high.
     *  @param latch    false = transparent (default), true = latch until Mask/Enable is read.
     */
    void set_alert(uint16_t function, float limit = 0.0f, bool polarity = false, bool latch = false);

    /** @brief Disable all alert functions (write Mask/Enable = 0). */
    void clear_alert();

    /** @brief Read the Mask/Enable Register (clears CVRF and, in latch mode, AFF).
     *  @return Raw 16-bit value containing alert and status flags.
     */
    uint16_t alert_flags();

    /** @brief Reset all registers to power-on defaults (RST bit). */
    void reset();

    /** @brief Enter power-down mode (MODE = 000); the previous mode is kept for wake(). */
    void shutdown();

    /** @brief Restore the operating mode that was active before shutdown(). */
    void wake();

    /** @brief Read the Manufacturer ID register.
     *  @return Manufacturer ID; expect 0x5449 (Texas Instruments).
     */
    uint16_t manufacturer_id();

    /** @brief Read the Die ID register.
     *  @return Die ID; (value >> 4) is 0x227, the low nibble is the revision.
     */
    uint16_t die_id();

private:
    static constexpr uint8_t REG_MASK   = 0x06;
    static constexpr uint8_t REG_ALERT  = 0x07;
    static constexpr uint8_t REG_MFR_ID = 0xFE;
    static constexpr uint8_t REG_DIE_ID = 0xFF;

    uint8_t _avg    = 0;
    uint8_t _vbus_ct = 4;
    uint8_t _ish_ct = 4;
    uint8_t _mode   = 7;

    void _write_config(uint8_t mode);
};
