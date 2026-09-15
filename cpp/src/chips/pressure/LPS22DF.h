#pragma once
#include <stdint.h>
#include "../../connection/Connection.h"

/** @brief LPS22DF absolute pressure and temperature sensor — minimal interface.
 *
 *  Provides pressure (Pa) and temperature (°C) readings with no configuration
 *  beyond the connection. I²C address is 0x5C (SDO=GND) or 0x5D (SDO=VDDIO).
 *  SPI uses 4-wire mode by default (SIM bit clear).
 *
 *  Default: ODR=10 Hz, AVG=4 samples, BDU enabled, low-pass filter off, FIFO bypass.
 *
 *  @param connection Configured I²C or SPI connection pointing at the device.
 *  @param spi       Pass true for SPI bus (masks bit 7 on writes).
 */
class LPS22DFMinimal {
public:
    explicit LPS22DFMinimal(Connection& connection, bool spi = false);

    /** @brief Read absolute pressure.
     *
     *  Polls STATUS.P_DA then burst-reads PRESS_OUT_XL..H. Sign-extends the
     *  24-bit two's complement value and converts to pascals (4096 LSB/hPa).
     *
     *  @return Pressure in pascals.
     */
    float pressure();

    /** @brief Read temperature.
     *
     *  Reads TEMP_OUT_L..H. Sign-extends the 16-bit two's complement value
     *  and converts to °C (100 LSB/°C).
     *
     *  @return Temperature in degrees Celsius.
     */
    float temperature();

protected:
    static constexpr uint8_t REG_INTERRUPT_CFG = 0x0B;
    static constexpr uint8_t REG_THS_P_L       = 0x0C;
    static constexpr uint8_t REG_THS_P_H       = 0x0D;
    static constexpr uint8_t REG_IF_CTRL       = 0x0E;
    static constexpr uint8_t REG_WHO_AM_I      = 0x0F;
    static constexpr uint8_t REG_CTRL_REG1     = 0x10;
    static constexpr uint8_t REG_CTRL_REG2     = 0x11;
    static constexpr uint8_t REG_CTRL_REG3     = 0x12;
    static constexpr uint8_t REG_CTRL_REG4     = 0x13;
    static constexpr uint8_t REG_FIFO_CTRL     = 0x14;
    static constexpr uint8_t REG_FIFO_WTM      = 0x15;
    static constexpr uint8_t REG_REF_P_L       = 0x16;
    static constexpr uint8_t REG_REF_P_H       = 0x17;
    static constexpr uint8_t REG_RPDS_L        = 0x1A;
    static constexpr uint8_t REG_RPDS_H        = 0x1B;
    static constexpr uint8_t REG_INT_SOURCE    = 0x24;
    static constexpr uint8_t REG_FIFO_STATUS1  = 0x25;
    static constexpr uint8_t REG_STATUS        = 0x27;
    static constexpr uint8_t REG_PRESS_OUT_XL  = 0x28;
    static constexpr uint8_t REG_TEMP_OUT_L    = 0x2B;
    static constexpr uint8_t REG_FIFO_PRESS_XL = 0x78;

    static constexpr uint8_t CHIP_ID = 0xB4;

    Connection& _connection;
    bool        _spi;

    void     _write_reg(uint8_t reg, uint8_t value);
    void     _read_reg(uint8_t reg, uint8_t* buf, uint8_t len);
    void     _wait_p_da();
};

/** @brief LPS22DF full interface — extends LPS22DFMinimal with configuration,
 *  threshold/offset calibration, FIFO, interrupts, and AUTOZERO/AUTOREFP.
 *
 *  @param connection Configured I²C or SPI connection pointing at the device.
 *  @param spi       Pass true for SPI bus (masks bit 7 on writes).
 */
class LPS22DFFull : public LPS22DFMinimal {
public:
    /** Output data rates. */
    static constexpr uint8_t ODR_POWER_DOWN = 0;
    static constexpr uint8_t ODR_1_HZ       = 1;
    static constexpr uint8_t ODR_4_HZ       = 2;
    static constexpr uint8_t ODR_10_HZ      = 3;
    static constexpr uint8_t ODR_25_HZ      = 4;
    static constexpr uint8_t ODR_50_HZ       = 5;
    static constexpr uint8_t ODR_75_HZ      = 6;
    static constexpr uint8_t ODR_100_HZ     = 7;
    static constexpr uint8_t ODR_200_HZ     = 8;

    /** Averaging filter. */
    static constexpr uint8_t AVG_4   = 0;
    static constexpr uint8_t AVG_8   = 1;
    static constexpr uint8_t AVG_16  = 2;
    static constexpr uint8_t AVG_32  = 3;
    static constexpr uint8_t AVG_64  = 4;
    static constexpr uint8_t AVG_128 = 5;
    static constexpr uint8_t AVG_512 = 7;

    /** FIFO modes. */
    static constexpr uint8_t FIFO_BYPASS         = 0;
    static constexpr uint8_t FIFO_FIFO           = 1;
    static constexpr uint8_t FIFO_CONTINUOUS     = 2;
    static constexpr uint8_t FIFO_BYPASS_TO_FIFO = 3;
    static constexpr uint8_t FIFO_BYPASS_TO_CONT = 4;
    static constexpr uint8_t FIFO_CONT_TO_FIFO   = 5;

    /** Interrupt source flags. */
    static constexpr uint8_t INT_BOOT_ON = 0x80;
    static constexpr uint8_t INT_IA      = 0x04;
    static constexpr uint8_t INT_PL      = 0x02;
    static constexpr uint8_t INT_PH      = 0x01;

    explicit LPS22DFFull(Connection& connection, bool spi = false);

    /** @brief Write CTRL_REG1 and CTRL_REG2.
     *
     *  @param odr       Output data rate (0=power-down, 1..7=1..100 Hz, 8=200 Hz).
     *  @param avg       Averaging filter (0=4, 1=8, 2=16, 3=32, 4=64, 5=128, 7=512).
     *  @param en_lpfp   Enable low-pass filter on pressure.
     *  @param lfpf_cfg  0=ODR/4 cutoff, 1=ODR/9 cutoff.
     *  @param bdu       Block data update.
     */
    void configure(uint8_t odr, uint8_t avg, bool en_lpfp, uint8_t lfpf_cfg, bool bdu);

    /** @brief Trigger a single measurement in power-down mode.
     *
     *  Sets ODR to power-down then sets ONESHOT=1; blocks until STATUS.P_DA=1.
     */
    void oneshot();

    /** @brief Compute altitude above sea level from the current pressure.
     *
     *  @param sea_level_pa Reference sea-level pressure in pascals (default 101325).
     *  @return Altitude in metres.
     */
    float altitude(float sea_level_pa = 101325.0f);

    /** @brief Assert SWRESET and wait for self-clear. */
    void software_reset();

    /** @brief Write a one-point calibration offset.
     *
     *  @param offset_pa Offset in pascals (signed; persisted in NVM).
     */
    void set_pressure_offset(float offset_pa);

    /** @brief Write a 15-bit unsigned pressure threshold.
     *
     *  @param threshold_pa Threshold in pascals.
     */
    void set_pressure_threshold(float threshold_pa);

    /** @brief Configure the INT pin and routing.
     *
     *  @param int_h_l    Interrupt polarity (False=active-high, True=active-low).
     *  @param pp_od      Pin type (False=push-pull, True=open-drain).
     *  @param drdy       Route data-ready signal to INT pin.
     *  @param drdy_pls   Data-ready pulsed (~5 µs).
     *  @param int_en     Route pressure threshold interrupt to INT pin.
     *  @param int_f_wtm  Route FIFO-watermark flag to INT pin.
     *  @param int_f_full Route FIFO-full flag to INT pin.
     *  @param int_f_ovr  Route FIFO-overrun flag to INT pin.
     */
    void configure_interrupt(bool int_h_l, bool pp_od, bool drdy, bool drdy_pls,
                             bool int_en, bool int_f_wtm, bool int_f_full, bool int_f_ovr);

    /** @brief Configure pressure-event interrupts.
     *
     *  @param phe Enable interrupt on pressure-high event.
     *  @param ple Enable interrupt on pressure-low event.
     *  @param lir Latch interrupt request in INT_SOURCE.
     */
    void configure_pressure_event(bool phe, bool ple, bool lir);

    /** @brief Capture the current pressure as the AUTOZERO reference. */
    void autozero();

    /** @brief Capture the current pressure in REF_P for use as a comparator
     *  against THS_P. PRESS_OUT remains absolute. */
    void autorefp();

    /** @brief Reset both AUTOZERO and AUTOREFP, returning PRESS_OUT to absolute. */
    void reset_reference();

    /** @brief Read the stored AUTOZERO/AUTOREFP reference pressure.
     *  @return Reference pressure in pascals. */
    float reference_pressure();

    /** @brief Set the FIFO mode.
     *
     *  @param mode 0=bypass, 1=FIFO, 2=continuous, 3=bypass-to-FIFO,
     *              4=bypass-to-continuous, 5=continuous-to-FIFO.
     */
    void set_fifo_mode(uint8_t mode);

    /** @brief Set the FIFO watermark level.
     *  @param level 0..127. */
    void set_fifo_watermark(uint8_t level);

    /** @brief Read the FIFO sample count.
     *  @return Number of stored FIFO samples (0..127). */
    uint8_t fifo_sample_count();

    /** @brief Read every available FIFO sample.
     *  @param out_buf Destination buffer (one float per sample).
     *  @param max_samples Buffer capacity in samples.
     *  @return Number of samples written into out_buf. */
    uint8_t read_fifo(float* out_buf, uint8_t max_samples);

    /** @brief Read and clear the INT_SOURCE register.
     *  @return Raw INT_SOURCE byte. */
    uint8_t interrupt_source();

    /** @brief Re-read the chip ID register (expected 0xB4). */
    uint8_t who_am_i();
};