#pragma once
#include <stdint.h>
#include "../../transport/Transport.h"

/** @brief INA3221 three-channel 26V current/voltage/power monitor — minimal interface.
 *
 * Reads bus voltage, shunt voltage, current, and power for each of the three
 * channels with no configuration beyond the transport and shunt resistors.
 * The chip's power-on default (all three channels on, continuous shunt+bus)
 * is used without modification.
 *
 * @param transport Configured I2C or SMBus transport pointing at the device.
 * @param r_shunt  Shunt resistor value in ohms. Pass a single float to apply
 *                  the same value to all three channels, or a 3-element float
 *                  array for per-channel values (default 0.1 ohms for all).
 */
class INA3221Minimal {
public:
    INA3221Minimal(Transport& transport, float r_shunt = 0.1f);
    INA3221Minimal(Transport& transport, const float r_shunt[3]);

    /** @brief Read bus voltage for a channel.
     *  @param channel Channel number 1, 2, or 3.
     *  @return Bus voltage in volts.
     */
    float voltage(uint8_t channel);

    /** @brief Read differential shunt voltage for a channel.
     *  @param channel Channel number 1, 2, or 3.
     *  @return Shunt voltage in volts, signed.
     */
    float shunt_voltage(uint8_t channel);

    /** @brief Read calculated current through the shunt for a channel.
     *  @param channel Channel number 1, 2, or 3.
     *  @return Current in amperes.
     */
    float current(uint8_t channel);

    /** @brief Read calculated power for a channel.
     *  @param channel Channel number 1, 2, or 3.
     *  @return Power in watts.
     */
    float power(uint8_t channel);

protected:
    static constexpr uint8_t  REG_CONFIG   = 0x00;
    static constexpr uint8_t  REG_SHUNT1   = 0x01;
    static constexpr uint8_t  REG_BUS1     = 0x02;
    static constexpr uint8_t  REG_SHUNT2   = 0x03;
    static constexpr uint8_t  REG_BUS2     = 0x04;
    static constexpr uint8_t  REG_SHUNT3   = 0x05;
    static constexpr uint8_t  REG_BUS3     = 0x06;
    static constexpr uint8_t  REG_MFR_ID   = 0xFE;
    static constexpr uint8_t  REG_DIE_ID   = 0xFF;

    static constexpr uint8_t  SHUNT_REGS[3] = { REG_SHUNT1, REG_SHUNT2, REG_SHUNT3 };
    static constexpr uint8_t  BUS_REGS[3]   = { REG_BUS1,   REG_BUS2,   REG_BUS3   };

    Transport& _transport;
    float      _r_shunt[3];

    void     _write_reg(uint8_t reg, uint16_t value);
    uint16_t _read_reg(uint8_t reg);
    int16_t  _read_reg_signed(uint8_t reg);
    uint8_t  _channel_valid(uint8_t channel);
};

/** @brief INA3221 full interface — extends INA3221Minimal with configuration and alert support.
 *
 * Adds Configuration Register programming, channel enables, conversion-ready,
 * per-channel critical and warning alerts, shunt-voltage summation, power-valid
 * monitoring, reset, and shutdown/wake.
 *
 * Alert flag constants (from Mask/Enable register):
 * - CF1, CF2, CF3 — Channel 1/2/3 critical-alert flag
 * - WF1, WF2, WF3 — Channel 1/2/3 warning-alert flag
 * - SF            — Summation-alert flag
 * - PVF           — Power-valid flag
 * - TCF           — Timing-control flag
 * - CVRF          — Conversion-ready flag
 *
 * Mode constants:
 * - MODE_POWERDOWN      = 0
 * - MODE_SHUNT_TRIG     = 1
 * - MODE_BUS_TRIG       = 2
 * - MODE_SHUNT_BUS_TRIG = 3
 * - MODE_SHUNT_CONT     = 5
 * - MODE_BUS_CONT       = 6
 * - MODE_SHUNT_BUS_CONT = 7
 *
 * @param transport Configured I2C or SMBus transport pointing at the device.
 * @param r_shunt  Shunt resistor value in ohms. Pass a single float to apply
 *                  the same value to all three channels, or a 3-element float
 *                  array for per-channel values (default 0.1 ohms for all).
 */
class INA3221Full : public INA3221Minimal {
public:
    static constexpr uint16_t CF1   = 0x0200;
    static constexpr uint16_t CF2   = 0x0100;
    static constexpr uint16_t CF3   = 0x0080;
    static constexpr uint16_t SF    = 0x0040;
    static constexpr uint16_t WF1   = 0x0020;
    static constexpr uint16_t WF2   = 0x0010;
    static constexpr uint16_t WF3   = 0x0008;
    static constexpr uint16_t PVF   = 0x0004;
    static constexpr uint16_t TCF   = 0x0002;
    static constexpr uint16_t CVRF  = 0x0001;

    static constexpr uint8_t MODE_POWERDOWN      = 0;
    static constexpr uint8_t MODE_SHUNT_TRIG     = 1;
    static constexpr uint8_t MODE_BUS_TRIG       = 2;
    static constexpr uint8_t MODE_SHUNT_BUS_TRIG = 3;
    static constexpr uint8_t MODE_SHUNT_CONT     = 5;
    static constexpr uint8_t MODE_BUS_CONT       = 6;
    static constexpr uint8_t MODE_SHUNT_BUS_CONT = 7;

    INA3221Full(Transport& transport, float r_shunt = 0.1f);
    INA3221Full(Transport& transport, const float r_shunt[3]);

    /** @brief Write the Configuration Register.
     *  @param avg     Averaging count selector 0–7 (0=1 sample, 7=1024 samples).
     *  @param vbus_ct Bus voltage conversion time selector 0–7 (default 4=1.1 ms).
     *  @param vsh_ct  Shunt voltage conversion time selector 0–7 (default 4=1.1 ms).
     *  @param mode    Operating mode (default 7=shunt+bus continuous).
     */
    void configure(uint8_t avg = 0, uint8_t vbus_ct = 4, uint8_t vsh_ct = 4, uint8_t mode = 7);

    /** @brief Enable or disable a channel.
     *  @param channel Channel number 1, 2, or 3.
     *  @param enabled true to enable, false to disable.
     */
    void enable_channel(uint8_t channel, bool enabled);

    /** @brief Read whether a channel is enabled.
     *  @param channel Channel number 1, 2, or 3.
     *  @return true if the channel is enabled.
     */
    bool channel_enabled(uint8_t channel);

    /** @brief Read the Conversion Ready Flag (CVRF).
     *  @return true if a conversion completed.
     */
    bool conversion_ready();

    /** @brief Set the critical-alert limit for a channel.
     *  @param channel Channel number 1, 2, or 3.
     *  @param limit_v Voltage limit in volts.
     *  @param latch   If true, use latched mode (default false).
     */
    void set_critical_alert(uint8_t channel, float limit_v, bool latch = false);

    /** @brief Set the warning-alert limit for a channel.
     *  @param channel Channel number 1, 2, or 3.
     *  @param limit_v Voltage limit in volts.
     *  @param latch   If true, use latched mode (default false).
     */
    void set_warning_alert(uint8_t channel, float limit_v, bool latch = false);

    /** @brief Read the Mask/Enable Register.
     *
     *  Reading this register clears the latched alert flags (CF1/CF2/CF3,
     *  WF1/WF2/WF3, SF) when latch mode is enabled.
     *
     *  @return Raw 16-bit Mask/Enable register value.
     */
    uint16_t alert_flags();

    /** @brief Configure the shunt-voltage summation function.
     *  @param channels Array of channel numbers to sum.
     *  @param n        Number of channels in the array.
     *  @param limit_v  Shunt-voltage sum limit in volts.
     */
    void set_summation_channels(const uint8_t* channels, uint8_t n, float limit_v);

    /** @brief Read the shunt-voltage sum.
     *  @return Sum of selected channels' shunt voltages in volts.
     */
    float summation_value();

    /** @brief Set the Power-Valid upper and lower voltage limits.
     *  @param upper_v Upper bus voltage limit in volts.
     *  @param lower_v Lower bus voltage limit in volts.
     */
    void set_power_valid_limits(float upper_v, float lower_v);

    /** @brief Read the Power-Valid flag (PVF).
     *  @return true if all enabled bus voltages are within the PV limits.
     */
    bool power_valid();

    /** @brief Enter power-down mode and save the current mode for wake(). */
    void shutdown();

    /** @brief Restore the operating mode saved by shutdown(). */
    void wake();

    /** @brief Reset all registers to power-on defaults. */
    void reset();

    /** @brief Read the Manufacturer ID register.
     *  @return Manufacturer ID; expect 0x5449 (Texas Instruments).
     */
    uint16_t manufacturer_id();

    /** @brief Read the Die ID register.
     *  @return Die revision ID; expect 0x3220.
     */
    uint16_t die_id();

private:
    static constexpr uint8_t  REG_CH1_CRIT = 0x07;
    static constexpr uint8_t  REG_CH1_WARN = 0x08;
    static constexpr uint8_t  REG_CH2_CRIT = 0x09;
    static constexpr uint8_t  REG_CH2_WARN = 0x0A;
    static constexpr uint8_t  REG_CH3_CRIT = 0x0B;
    static constexpr uint8_t  REG_CH3_WARN = 0x0C;
    static constexpr uint8_t  REG_SUM      = 0x0D;
    static constexpr uint8_t  REG_SUM_LIMIT = 0x0E;
    static constexpr uint8_t  REG_MASK_EN  = 0x0F;
    static constexpr uint8_t  REG_PV_UPPER = 0x10;
    static constexpr uint8_t  REG_PV_LOWER = 0x11;

    static constexpr uint8_t  CRIT_REGS[3] = { REG_CH1_CRIT, REG_CH2_CRIT, REG_CH3_CRIT };
    static constexpr uint8_t  WARN_REGS[3] = { REG_CH1_WARN, REG_CH2_WARN, REG_CH3_WARN };

    uint8_t _mode = 0x07;
};