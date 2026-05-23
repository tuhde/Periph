#pragma once
#include <stdint.h>
#include "../../transport/Transport.h"

/** @brief AS5600 12-bit programmable contactless rotary position sensor — minimal interface.
 *
 * Reads the absolute angle in degrees with no configuration required beyond the
 * transport. Verifies magnet presence at construction; raises if no magnet is detected.
 *
 * Default behaviour:
 * - Reads STATUS to verify MD=1 (magnet detected) at construction
 * - Reads ANGLE register (0x0E-0x0F), respecting any OTP-programmed ZPOS/MPOS range
 * - No CONF writes — uses power-on default CONF=0x0000
 *
 * @param transport  Configured I²C transport pointing at the device (fixed address 0x36).
 */
class AS5600Minimal {
public:
    /**
     * @brief Construct and initialise the AS5600.
     * @param transport  I²C transport bound to the chip's address (0x36).
     */
    AS5600Minimal(Transport& transport);

    /**
     * @brief Read the scaled absolute angle.
     * @return Angle in degrees, 0.0–360.0 (exclusive).
     */
    float angle();

    /**
     * @brief Read the scaled 12-bit angle count.
     * @return Scaled angle count, 0–4095 (respects ZPOS/MPOS if programmed).
     */
    uint16_t angle_raw();

    /**
     * @brief Check if a magnet is detected.
     * @return true if STATUS.MD=1 (magnetic field >= 8 mT).
     */
    bool is_magnet_detected();

    /**
     * @brief Check if the magnet is too strong.
     * @return true if STATUS.MH=1 (AGC minimum gain overflow, Bz > 90 mT).
     */
    bool is_magnet_too_strong();

    /**
     * @brief Check if the magnet is too weak.
     * @return true if STATUS.ML=1 (AGC maximum gain overflow, Bz < 30 mT).
     */
    bool is_magnet_too_weak();

protected:
    static constexpr uint8_t REG_ZMCO        = 0x00;
    static constexpr uint8_t REG_ZPOS_H      = 0x01;
    static constexpr uint8_t REG_ZPOS_L      = 0x02;
    static constexpr uint8_t REG_MPOS_H      = 0x03;
    static constexpr uint8_t REG_MPOS_L      = 0x04;
    static constexpr uint8_t REG_MANG_H      = 0x05;
    static constexpr uint8_t REG_MANG_L      = 0x06;
    static constexpr uint8_t REG_CONF_H      = 0x07;
    static constexpr uint8_t REG_CONF_L      = 0x08;
    static constexpr uint8_t REG_STATUS      = 0x0B;
    static constexpr uint8_t REG_RAW_ANGLE_H = 0x0C;
    static constexpr uint8_t REG_RAW_ANGLE_L = 0x0D;
    static constexpr uint8_t REG_ANGLE_H     = 0x0E;
    static constexpr uint8_t REG_ANGLE_L     = 0x0F;
    static constexpr uint8_t REG_AGC         = 0x1A;
    static constexpr uint8_t REG_MAGNITUDE_H = 0x1B;
    static constexpr uint8_t REG_MAGNITUDE_L = 0x1C;
    static constexpr uint8_t REG_BURN        = 0xFF;

    static constexpr uint8_t STATUS_MD = 0x08;
    static constexpr uint8_t STATUS_ML = 0x10;
    static constexpr uint8_t STATUS_MH = 0x20;

    Transport& _transport;

    uint8_t  _read_reg8(uint8_t reg);
    uint16_t _read_reg16(uint8_t reg);
    void     _write_reg8(uint8_t reg, uint8_t value);
    void     _write_reg16(uint8_t reg, uint16_t value);
};

/** @brief AS5600 full interface — extends AS5600Minimal with complete chip functionality.
 *
 * Adds raw angle readings, AGC/magnitude/status access, configuration,
 * ZPOS/MPOS/MANG programming, and OTP burn commands.
 *
 * Power mode constants (pass to configure):
 * - PM_NOM  = 0: normal mode (6.5 mA)
 * - PM_LPM1 = 1: low power 1 (3.4 mA, 5 ms poll)
 * - PM_LPM2 = 2: low power 2 (1.8 mA, 20 ms poll)
 * - PM_LPM3 = 3: low power 3 (1.5 mA, 100 ms poll)
 *
 * Output stage constants:
 * - OUTS_ANALOG  = 0: analog 0–VDD
 * - OUTS_ANALOG2 = 1: analog 10–90% VDD
 * - OUTS_PWM     = 2: digital PWM
 *
 * @param transport  Configured I²C transport pointing at the device (fixed address 0x36).
 */
class AS5600Full : public AS5600Minimal {
public:
    static constexpr uint8_t PM_NOM  = 0;
    static constexpr uint8_t PM_LPM1 = 1;
    static constexpr uint8_t PM_LPM2 = 2;
    static constexpr uint8_t PM_LPM3 = 3;

    static constexpr uint8_t OUTS_ANALOG  = 0;
    static constexpr uint8_t OUTS_ANALOG2 = 1;
    static constexpr uint8_t OUTS_PWM     = 2;

    /**
     * @brief Construct and initialise the AS5600.
     * @param transport  I²C transport bound to the chip's address (0x36).
     */
    AS5600Full(Transport& transport);

    /**
     * @brief Read the unscaled raw 12-bit angle count.
     * @return Raw angle count, 0–4095 (unaffected by ZPOS/MPOS).
     */
    uint16_t raw_angle();

    /**
     * @brief Read the unscaled raw angle in degrees.
     * @return Raw angle in degrees, 0.0–360.0.
     */
    float raw_angle_degrees();

    /**
     * @brief Read the automatic gain control value.
     * @return AGC value (0–255 in 5 V mode; 0–127 in 3.3 V mode).
     *         Mid-range indicates optimal airgap.
     */
    uint8_t agc();

    /**
     * @brief Read the CORDIC magnitude value.
     * @return 12-bit CORDIC magnitude value.
     */
    uint16_t magnitude();

    /**
     * @brief Read the raw STATUS register byte.
     * @return Raw STATUS register (bits MH, ML, MD in positions 5, 4, 3).
     */
    uint8_t status_byte();

    /**
     * @brief Write the CONF_H and CONF_L registers.
     *
     * Reads the current CONF_H/CONF_L values first to preserve the reserved
     * bits in CONF_H[7:6].
     *
     * @param pm    Power mode 0–3 (0=NOM, 1=LPM1, 2=LPM2, 3=LPM3).
     * @param hyst  Hysteresis 0–3 (0=off, 1=1 LSB, 2=2 LSBs, 3=3 LSBs).
     * @param outs  Output stage 0–2 (0=analog 0–VDD, 1=analog 10–90%, 2=PWM).
     * @param pwmf  PWM frequency 0–3 (0=115 Hz, 1=230 Hz, 2=460 Hz, 3=920 Hz).
     * @param sf    Slow filter 0–3 (0=16x, 1=8x, 2=4x, 3=2x).
     * @param fth   Fast filter threshold 0–7.
     * @param wd    Watchdog enable (true=on, false=off).
     */
    void configure(uint8_t pm = 0, uint8_t hyst = 0, uint8_t outs = 0,
                   uint8_t pwmf = 0, uint8_t sf = 0, uint8_t fth = 0, bool wd = false);

    /**
     * @brief Write the zero position (start angle) to volatile RAM.
     * @param pos  Zero position 0–4095. Lost on power cycle unless burned.
     */
    void set_zero_position(uint16_t pos);

    /**
     * @brief Write the maximum position (stop angle) to volatile RAM.
     * @param pos  Maximum position 0–4095. Lost on power cycle unless burned.
     */
    void set_max_position(uint16_t pos);

    /**
     * @brief Write the maximum angle span to volatile RAM.
     * @param span  Angle span 0–4095 (must correspond to >= 18 degrees).
     */
    void set_max_angle(uint16_t span);

    /**
     * @brief Read the zero position (start angle).
     * @return ZPOS value 0–4095.
     */
    uint16_t zero_position();

    /**
     * @brief Read the maximum position (stop angle).
     * @return MPOS value 0–4095.
     */
    uint16_t max_position();

    /**
     * @brief Read the maximum angle span.
     * @return MANG value 0–4095.
     */
    uint16_t max_angle();

    /**
     * @brief Read the number of permanent ZPOS/MPOS burns already performed.
     * @return ZMCO value 0–3. Remaining permanent writes = 3 - ZMCO.
     */
    uint8_t burn_count();

    /**
     * @brief Permanently burn ZPOS and MPOS to OTP.
     *
     * Requires MD=1 (magnet present) and ZMCO < 3.
     *
     * @throws RuntimeError equivalent if magnet not detected or ZMCO >= 3.
     */
    void burn_angle();

    /**
     * @brief Permanently burn MANG and CONF to OTP.
     *
     * Requires ZMCO=0 (ZPOS/MPOS never burned). Can only be executed once.
     *
     * @throws RuntimeError equivalent if ZMCO != 0.
     */
    void burn_setting();
};
