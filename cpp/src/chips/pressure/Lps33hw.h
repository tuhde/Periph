#pragma once
#include <stdint.h>
#include "../../connection/Connection.h"

/** @brief LPS33HW water-resistant MEMS absolute pressure sensor — minimal interface.
 *
 *  Provides calibrated pressure (Pa) and temperature (°C) with no configuration
 *  beyond the connection. I²C address is 0x5C (SA0=GND) or 0x5D (SA0=VDD).
 *
 *  Default: ODR=1 Hz, BDU=1, EN_LPFP=0, IF_ADD_INC=1.
 *
 *  @param connection Configured I²C or SPI connection pointing at the device.
 */
class LPS33HWMinimal {
public:
    explicit LPS33HWMinimal(Connection& connection);

    /** @brief Read calibrated absolute pressure.
     *
     *  Waits for STATUS.P_DA before reading.
     *
     *  @return Pressure in pascals.
     */
    float pressure();

    /** @brief Read calibrated temperature.
     *
     *  Waits for STATUS.T_DA before reading.
     *
     *  @return Temperature in degrees Celsius.
     */
    float temperature();

protected:
    static constexpr uint8_t REG_INTERRUPT_CFG = 0x0B;
    static constexpr uint8_t REG_THS_P_L       = 0x0C;
    static constexpr uint8_t REG_THS_P_H       = 0x0D;
    static constexpr uint8_t REG_WHO_AM_I      = 0x0F;
    static constexpr uint8_t REG_CTRL_REG1     = 0x10;
    static constexpr uint8_t REG_CTRL_REG2     = 0x11;
    static constexpr uint8_t REG_CTRL_REG3     = 0x12;
    static constexpr uint8_t REG_FIFO_CTRL     = 0x14;
    static constexpr uint8_t REG_REF_P_XL      = 0x15;
    static constexpr uint8_t REG_REF_P_L       = 0x16;
    static constexpr uint8_t REG_REF_P_H       = 0x17;
    static constexpr uint8_t REG_RPDS_L        = 0x18;
    static constexpr uint8_t REG_RPDS_H        = 0x19;
    static constexpr uint8_t REG_RES_CONF      = 0x1A;
    static constexpr uint8_t REG_INT_SOURCE    = 0x25;
    static constexpr uint8_t REG_FIFO_STATUS   = 0x26;
    static constexpr uint8_t REG_STATUS        = 0x27;
    static constexpr uint8_t REG_PRESS_XL      = 0x28;
    static constexpr uint8_t REG_PRESS_L       = 0x29;
    static constexpr uint8_t REG_PRESS_H       = 0x2A;
    static constexpr uint8_t REG_TEMP_L        = 0x2B;
    static constexpr uint8_t REG_TEMP_H        = 0x2C;
    static constexpr uint8_t REG_LPFP_RES      = 0x33;

    static constexpr uint8_t CHIP_ID           = 0xB1;
    static constexpr uint8_t STATUS_P_DA       = 0x01;
    static constexpr uint8_t STATUS_T_DA       = 0x02;

    static constexpr uint8_t CTRL_REG1_DEFAULT = 0x12;
    static constexpr uint8_t CTRL_REG2_RESET   = 0x04;
    static constexpr uint8_t CTRL_REG2_DEFAULT = 0x10;

    Connection& _connection;

    void     _write_reg(uint8_t reg, uint8_t value);
    void     _read_reg(uint8_t reg, uint8_t* buf, size_t len);
    void     _wait_status(uint8_t mask);
    int32_t  _read_pressure_raw();
    int16_t  _read_temperature_raw();
};

/** @brief LPS33HW full interface — extends LPS33HWMinimal with configuration,
 *  one-shot, FIFO, interrupt, AUTOZERO/AUTORIFP, reset, and reboot.
 *
 *  @param connection Configured I²C or SPI connection pointing at the device.
 */
class LPS33HWFull : public LPS33HWMinimal {
public:
    static constexpr uint8_t ODR_POWER_DOWN = 0;
    static constexpr uint8_t ODR_1_HZ       = 1;
    static constexpr uint8_t ODR_10_HZ      = 2;
    static constexpr uint8_t ODR_25_HZ      = 3;
    static constexpr uint8_t ODR_50_HZ      = 4;
    static constexpr uint8_t ODR_75_HZ      = 5;

    static constexpr uint8_t LPFP_BW_ODR_9  = 0;
    static constexpr uint8_t LPFP_BW_ODR_20 = 1;

    static constexpr uint8_t FIFO_MODE_BYPASS            = 0;
    static constexpr uint8_t FIFO_MODE_FIFO              = 1;
    static constexpr uint8_t FIFO_MODE_STREAM            = 2;
    static constexpr uint8_t FIFO_MODE_STREAM_TO_FIFO    = 3;
    static constexpr uint8_t FIFO_MODE_BYPASS_TO_STREAM  = 4;
    static constexpr uint8_t FIFO_MODE_DYNAMIC_STREAM    = 6;
    static constexpr uint8_t FIFO_MODE_BYPASS_TO_FIFO    = 7;

    static constexpr uint8_t INT_S_DATA_SIGNALS   = 0;
    static constexpr uint8_t INT_S_PRESSURE_HIGH  = 1;
    static constexpr uint8_t INT_S_PRESSURE_LOW   = 2;
    static constexpr uint8_t INT_S_PRESSURE_BOTH  = 3;

    explicit LPS33HWFull(Connection& connection);

    /** @brief Write CTRL_REG1, RES_CONF, and (when needed) CTRL_REG2 SIM bit.
     *  @param odr      Output data rate (0–5).
     *  @param bdu      Block data update.
     *  @param en_lpfp  Enable additional low-pass filter.
     *  @param lpfp_cfg LPF bandwidth (0=ODR/9, 1=ODR/20).
     *  @param lc_en    Low-current mode (only writable when ODR=0).
     *  @param sim      SPI mode (false=4-wire, true=3-wire).
     */
    void configure(uint8_t odr, bool bdu, bool en_lpfp, uint8_t lpfp_cfg,
                   bool lc_en, bool sim);

    /** @brief Trigger a single measurement. Requires ODR=0.
     *  @param[out] pressure_Pa    Pressure in Pa.
     *  @param[out] temperature_C  Temperature in °C.
     *  @return true on success, false on timeout.
     */
    bool one_shot(float& pressure_Pa, float& temperature_C);

    /** @brief Read the STATUS register.
     *  @return Status byte; bit 0=P_DA, bit 1=T_DA, bit 4=P_OR, bit 5=T_OR.
     */
    uint8_t status();

    /** @brief Software-reset via SWRESET; waits for self-clear, restores defaults. */
    void reset();

    /** @brief Reload factory trimming via BOOT bit. */
    void reboot();

    /** @brief Apply a one-point calibration offset in hPa.
     *  @param offset_hPa Offset in hectopascals (1 RPDS LSB = 1/16 hPa).
     */
    void set_pressure_offset(float offset_hPa);

    /** @brief Set AUTOZERO; current pressure is stored in REF_P. */
    void set_autozero();

    /** @brief Clear AUTOZERO mode and reset REF_P to 0. */
    void clear_autozero();

    /** @brief Set AUTORIFP; next measurement value is stored in RPDS. */
    void set_autorifp();

    /** @brief Clear AUTORIFP mode and reset RPDS to 0. */
    void clear_autorifp();

    /** @brief Route events to the INT_DRDY pin.
     *  @param drdy        Route data-ready.
     *  @param f_fth       Route FIFO threshold.
     *  @param f_ovr       Route FIFO overrun.
     *  @param f_fss5      Route FIFO full (32 samples).
     *  @param int_s       Signal selection (0–3).
     *  @param active_low  INT_DRDY active-low.
     *  @param open_drain  INT_DRDY open-drain.
     */
    void configure_interrupt(bool drdy, bool f_fth, bool f_ovr, bool f_fss5,
                             uint8_t int_s, bool active_low, bool open_drain);

    /** @brief Configure differential pressure threshold interrupt.
     *  @param high_en        Enable interrupt on pressure above threshold.
     *  @param low_en         Enable interrupt on pressure below threshold.
     *  @param threshold_hPa  Pressure threshold in hPa.
     *  @param latch          Latch the interrupt request.
     */
    void configure_pressure_interrupt(bool high_en, bool low_en,
                                      float threshold_hPa, bool latch);

    /** @brief Read the INT_SOURCE register.
     *  @return Raw INT_SOURCE byte.
     */
    uint8_t interrupt_status();

    /** @brief Enable the FIFO.
     *  @param mode      FIFO mode (0–7, excluding 5).
     *  @param watermark FIFO watermark level (0–31).
     */
    void enable_fifo(uint8_t mode, uint8_t watermark);

    /** @brief Disable the FIFO and reset to Bypass mode. */
    void disable_fifo();

    /** @brief Read FIFO_STATUS register.
     *  @return FIFO_STATUS byte; bit 7=FTH, bit 6=OVR, bits 5:0=FSS.
     */
    uint8_t fifo_status();

    /** @brief Read LPFP_RES to flush transitory LPF state. */
    void reset_lpf();
};