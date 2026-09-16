#pragma once
#include <stdint.h>
#include <string.h>
#include "../../connection/Connection.h"

/** @brief ADE7953 single-phase multifunction metering IC — minimal interface.
 *
 * The ADE7953 digitises one voltage channel and two current channels (phase
 * A and neutral B), producing RMS voltage/current, instantaneous and
 * accumulated active/reactive/apparent power/energy, power factor, phase
 * angle, line period/frequency. Supports I²C, SPI and UART transports; the
 * active transport must match how the chip's bus pins are wired (chip
 * autodetects at power-up).
 *
 * Default configuration (baked in at construction):
 *   - PGA gains all 1 (unity)
 *   - HPF on, digital integrators off
 *   - RSTREAD = 1 (energy registers reset on read)
 *   - Calibration/offset registers at power-on defaults
 *   - DISNOLOAD all 0 (no-load detection enabled)
 *
 * @param connection    Configured I²C, SPI or UART connection bound to the device.
 * @param voltage_gain  Real volts at the mains per volt at VP–VN. Required
 *                      (depends on the external voltage divider).
 * @param current_gain  Real amperes per volt at IAP–IAN for Current Channel A.
 *                      Required (depends on the external CT + burden, shunt or
 *                      Rogowski front end).
 * @param bus_type      0 = I²C (default), 1 = SPI, 2 = UART.
 */
class ADE7953Minimal {
public:
    ADE7953Minimal(Connection& connection,
                   float voltage_gain,
                   float current_gain,
                   uint8_t bus_type = 0);

    /** @brief Read the RMS voltage on the voltage channel. */
    float voltage();

    /** @brief Read the RMS current on Current Channel A. */
    float current();

    /** @brief Read instantaneous active power on Current Channel A.
     *  @return Power in watts (signed; negative = power flowing back to source).
     */
    float activePower();

    /** @brief Read the active-energy accumulator for Current Channel A.
     *
     *  Reads AENERGYA, which by default (RSTREAD = 1) resets after the read;
     *  therefore returns energy accumulated since the previous call.
     *  @return Active energy in watt-hours.
     */
    float activeEnergy();

protected:
    static constexpr uint8_t BUS_I2C  = 0;
    static constexpr uint8_t BUS_SPI  = 1;
    static constexpr uint8_t BUS_UART = 2;

    // 8-bit registers
    static constexpr uint16_t REG_DISNOLOAD       = 0x001;
    static constexpr uint16_t REG_PGA_V           = 0x007;
    static constexpr uint16_t REG_PGA_IA          = 0x008;
    static constexpr uint16_t REG_PGA_IB          = 0x009;
    static constexpr uint16_t REG_WRITE_PROTECT   = 0x040;
    static constexpr uint16_t REG_VERSION         = 0x702;
    static constexpr uint16_t REG_EX_REF          = 0x800;

    // 16-bit registers
    static constexpr uint16_t REG_CONFIG          = 0x102;
    static constexpr uint16_t REG_CF1DEN          = 0x103;
    static constexpr uint16_t REG_CF2DEN          = 0x104;
    static constexpr uint16_t REG_CFMODE          = 0x107;
    static constexpr uint16_t REG_PHCALA          = 0x108;
    static constexpr uint16_t REG_PHCALB          = 0x109;
    static constexpr uint16_t REG_PFA             = 0x10A;
    static constexpr uint16_t REG_ANGLE_A         = 0x10C;
    static constexpr uint16_t REG_PERIOD          = 0x10E;
    static constexpr uint16_t REG_ALT_OUTPUT      = 0x110;
    static constexpr uint16_t REG_INTERNAL_RES    = 0x120;

    // 24-bit / 32-bit registers (24-bit addresses listed, 32-bit aliases +0x100)
    static constexpr uint16_t REG_SAGLVL          = 0x200;
    static constexpr uint16_t REG_ACCMODE         = 0x201;
    static constexpr uint16_t REG_AP_NOLOAD       = 0x203;
    static constexpr uint16_t REG_VAR_NOLOAD      = 0x204;
    static constexpr uint16_t REG_VA_NOLOAD       = 0x205;
    static constexpr uint16_t REG_AWATT           = 0x212;
    static constexpr uint16_t REG_VRMS            = 0x21C;
    static constexpr uint16_t REG_AENERGYA        = 0x21E;
    static constexpr uint16_t REG_OVLVL           = 0x224;
    static constexpr uint16_t REG_OILVL           = 0x225;
    static constexpr uint16_t REG_IRQENA          = 0x22C;
    static constexpr uint16_t REG_RSTIRQSTATA     = 0x22E;
    static constexpr uint16_t REG_IRQENB          = 0x22F;
    static constexpr uint16_t REG_RSTIRQSTATB     = 0x231;
    static constexpr uint16_t REG_CRC             = 0x37F;
    static constexpr uint16_t REG_AWGAIN          = 0x282;
    static constexpr uint16_t REG_AWATTOS         = 0x289;

    static constexpr uint8_t  REG_120_UNLOCK      = 0xFE;
    static constexpr uint16_t REG_120_VALUE        = 0x30;

    static constexpr float    ADC_FS_VOLTS = 0.5f / 1.41421356237f;     // 0.353553
    static constexpr int32_t  ADC_FS_CODE  = 9032007;
    static constexpr int32_t  POWER_FS_CODE = 4862401;
    static constexpr float    T_SAMPLE     = 1.0f / 206900.0f;        // 4.83e-6 s
    static constexpr float    PF_LSB       = 1.0f / 32768.0f;
    static constexpr float    ANGLE_LSB    = 1.0f / 223750.0f;
    static constexpr float    PHASE_LSB    = 1.0f / 895000.0f;

    Connection& _connection;
    uint8_t     _bus_type;
    float       _voltage_gain;
    float       _current_gain_a;
    float       _current_gain_b;
    uint8_t     _pga_a;
    uint8_t     _pga_b;
    uint8_t     _pga_v;

    void _initChip();
    void _read(uint16_t reg, uint8_t n, uint8_t* buf);
    void _write(uint16_t reg, const uint8_t* payload, uint8_t n);
    void _writeU8(uint16_t reg, uint8_t value);
    void _writeU16(uint16_t reg, uint16_t value);
    void _writeU24(uint16_t reg, uint32_t value);
    void _writeU32(uint16_t reg, uint32_t value);
    uint32_t _readU24(uint16_t reg);
    int32_t  _readS24(uint16_t reg);
    uint32_t _readU32(uint16_t reg);
    uint16_t _readU16(uint16_t reg);
    int16_t  _readS16(uint16_t reg);
    float    _voltageScale() const;
    float    _currentScale(float gain) const;
    float    _powerScale(float gain) const;
    float    _energyScale(float gain) const;
    void     _delayMs(uint32_t ms);
};


/** @brief ADE7953 full interface — extends ADE7953Minimal.
 *
 * Adds Channel B, reactive/apparent measurements, calibration, accumulation
 * modes, power-quality features (no-load, sag, peak, overcurrent/overvoltage),
 * zero-crossing, REVP, alternate outputs, CF pulses, interrupts, checksum,
 * write protection, reset and last-operation diagnostics.
 */
class ADE7953Full : public ADE7953Minimal {
public:
    ADE7953Full(Connection& connection,
                float voltage_gain,
                float current_gain,
                uint8_t bus_type = 0);

    /** @brief Override the calibration constant used by every Channel B
     *  current/power/energy method. Defaults to Channel A's current_gain
     *  if never called.
     */
    void configureChannelB(float current_gain_b);

    /** @brief Read RMS current on Current Channel B (neutral). */
    float currentB();

    /** @brief Read instantaneous active power on Current Channel B (signed). */
    float activePowerB();

    /** @brief Read the active-energy accumulator for Current Channel B. */
    float activeEnergyB();

    /** @brief Read instantaneous reactive power on Current Channel A. */
    float reactivePower();

    /** @brief Read instantaneous reactive power on Current Channel B. */
    float reactivePowerB();

    /** @brief Read reactive-energy accumulator for Current Channel A. */
    float reactiveEnergy();

    /** @brief Read reactive-energy accumulator for Current Channel B. */
    float reactiveEnergyB();

    /** @brief Read instantaneous apparent power on Current Channel A. */
    float apparentPower();

    /** @brief Read instantaneous apparent power on Current Channel B. */
    float apparentPowerB();

    /** @brief Read apparent-energy accumulator for Current Channel A. */
    float apparentEnergy();

    /** @brief Read apparent-energy accumulator for Current Channel B. */
    float apparentEnergyB();

    /** @brief Read power factor for Current Channel A.
     *  @return Power factor in range [-1.0, +1.0].
     */
    float powerFactor();

    /** @brief Read line period.
     *  @return Line period in seconds.
     */
    float linePeriod();

    /** @brief Read line frequency.
     *  @return Line frequency in Hertz.
     */
    float lineFrequency();

    /** @brief Write PGA gain for a channel and update cached scale factors.
     *  @param channel 'a', 'b' or 'v'.
     *  @param gain    1, 2, 4, 8, 16 (and 22 valid only for 'a').
     */
    void setPga(char channel, uint8_t gain);

    /** @brief Write phase calibration register.
     *  @param channel  'a' or 'b'.
     *  @param delay_s  Phase shift in seconds; negative = delay, positive = advance.
     */
    void setPhaseCalibration(char channel, float delay_s);

    /** @brief Write one of the gain-calibration registers.
     *  @param reg    Register address (one of ADE7953Minimal::REG_AWGAIN, etc.).
     *  @param value  Raw 24-bit value; valid range 0x200000..0x600000.
     */
    void setGainCalibration(uint16_t reg, uint32_t value);

    /** @brief Read a gain-calibration register. */
    uint32_t gainCalibration(uint16_t reg);

    /** @brief Write one of the offset-calibration registers. */
    void setOffsetCalibration(uint16_t reg, int32_t value);

    /** @brief Read an offset-calibration register. */
    int32_t offsetCalibration(uint16_t reg);

    /** @brief Read the 32-bit CRC/checksum over the configuration registers. */
    uint32_t checksum();

    /** @brief Enable or disable the CRC/checksum (CONFIG.CRC_ENABLE). */
    void enableChecksum(bool enabled);

    /** @brief Configure overvoltage threshold (volts). */
    void configureOvervoltage(float threshold);

    /** @brief Configure overcurrent threshold (amperes; shared by both channels). */
    void configureOvercurrent(float threshold);

    /** @brief Software-reset the chip. Re-applies the mandatory power-up
     *  register setting after a 110 ms settle.
     */
    void reset();

    /** @brief Read the silicon version register. */
    uint8_t version();

    /** @brief Set the active-energy accumulation mode.
     *  @param channel 'a' or 'b'.
     *  @param mode    0 = normal, 1 = positive-only, 2 = absolute.
     */
    void setActiveEnergyMode(char channel, uint8_t mode);

    /** @brief Set the reactive-energy accumulation mode.
     *  @param channel 'a' or 'b'.
     *  @param mode    0 = normal, 1 = antitamper, 2 = absolute.
     */
    void setReactiveEnergyMode(char channel, uint8_t mode);
};