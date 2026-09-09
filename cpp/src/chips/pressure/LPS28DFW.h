#pragma once
#include <stdint.h>
#include "../../connection/Connection.h"

/** @brief LPS28DFW dual full-scale digital barometer — minimal interface.
 *
 *  Reads absolute pressure and temperature from the CCLGA-7L water-resistant
 *  sensor. I²C address is 0x5C (SA0=GND) or 0x5D (SA0=VDD).
 *
 *  Default configuration (baked in at construction):
 *      - FS_MODE = 0 (Mode 1, 0–1260 hPa, 4096 LSB/hPa)
 *      - AVG = 010b (16 samples)
 *      - ODR = 0100b (25 Hz)
 *      - BDU = 1, EN_LPFP = 1, LFPF_CFG = 0 (ODR/4 bandwidth)
 *
 *  @param connection Configured I²C connection pointing at the device.
 */
class LPS28DFWMinimal {
public:
    explicit LPS28DFWMinimal(Connection& connection);

    /** @brief Read absolute pressure.
     *
     *  Sensitivity is 4096 LSB/hPa in Mode 1 (default) and 2048 LSB/hPa
     *  in Mode 2 (260–4060 hPa range).
     *
     *  @return Pressure in hPa.
     */
    float read_pressure();

    /** @brief Read sensor temperature.
     *  @return Temperature in degrees Celsius (0.01 °C LSB).
     */
    float read_temperature();

protected:
    static constexpr uint8_t REG_INTERRUPT_CFG = 0x0B;
    static constexpr uint8_t REG_WHO_AM_I      = 0x0F;
    static constexpr uint8_t REG_CTRL_REG1     = 0x10;
    static constexpr uint8_t REG_CTRL_REG2     = 0x11;
    static constexpr uint8_t REG_CTRL_REG3     = 0x12;
    static constexpr uint8_t REG_STATUS        = 0x27;
    static constexpr uint8_t REG_PRESS_OUT_XL  = 0x28;
    static constexpr uint8_t REG_PRESS_OUT_L   = 0x29;
    static constexpr uint8_t REG_PRESS_OUT_H   = 0x2A;
    static constexpr uint8_t REG_TEMP_OUT_L    = 0x2B;
    static constexpr uint8_t REG_TEMP_OUT_H    = 0x2C;
    static constexpr uint8_t REG_FIFO_DATA_PRESS_XL = 0x78;

    static constexpr uint8_t CHIP_ID            = 0xB4;
    static constexpr uint32_t BOOT_WAIT_MS       = 2;
    static constexpr float   SENSITIVITY_LSB_PER_HPA_MODE1 = 4096.0f;
    static constexpr float   SENSITIVITY_LSB_PER_HPA_MODE2 = 2048.0f;

    Connection& _connection;
    uint8_t _fs_mode = 0;
    uint8_t _odr     = 0x04;
    uint8_t _avg     = 0x02;
    uint8_t _lpf_en  = 1;
    uint8_t _lpf_cfg = 0;
    uint8_t _bdu     = 1;

    void    _init();
    void    _write_reg(uint8_t reg, uint8_t value);
    void    _read_reg(uint8_t reg, uint8_t* buf, size_t len);
    int32_t _read_pressure_raw();
    int16_t _read_temperature_raw();
};

/** @brief LPS28DFW full interface — extends LPS28DFWMinimal with full configuration.
 *
 *  Adds full-scale selection, ODR / averaging control, IIR low-pass filter,
 *  block-data-update, one-shot trigger, one-point calibration offset, FIFO
 *  configuration / drain / level, and pressure-threshold interrupt setup.
 *
 *  @param connection Configured I²C connection pointing at the device.
 */
class LPS28DFWFull : public LPS28DFWMinimal {
public:
    static constexpr uint8_t ODR_POWER_DOWN = 0x00;
    static constexpr uint8_t ODR_1_HZ       = 0x01;
    static constexpr uint8_t ODR_4_HZ       = 0x02;
    static constexpr uint8_t ODR_10_HZ      = 0x03;
    static constexpr uint8_t ODR_25_HZ      = 0x04;
    static constexpr uint8_t ODR_50_HZ      = 0x05;
    static constexpr uint8_t ODR_75_HZ      = 0x06;
    static constexpr uint8_t ODR_100_HZ     = 0x07;
    static constexpr uint8_t ODR_200_HZ     = 0x08;

    static constexpr uint8_t AVG_4   = 0x00;
    static constexpr uint8_t AVG_8   = 0x01;
    static constexpr uint8_t AVG_16  = 0x02;
    static constexpr uint8_t AVG_32  = 0x03;
    static constexpr uint8_t AVG_64  = 0x04;
    static constexpr uint8_t AVG_128 = 0x05;
    static constexpr uint8_t AVG_512 = 0x07;

    static constexpr uint8_t FS_MODE_1 = 0;
    static constexpr uint8_t FS_MODE_2 = 1;

    static constexpr uint8_t LFPF_ODR_OVER_4 = 0;
    static constexpr uint8_t LFPF_ODR_OVER_9 = 1;

    static constexpr uint8_t FIFO_BYPASS               = 0;
    static constexpr uint8_t FIFO_FIFO                 = 1;
    static constexpr uint8_t FIFO_CONTINUOUS           = 2;
    static constexpr uint8_t FIFO_BYPASS_TO_FIFO       = 4;
    static constexpr uint8_t FIFO_BYPASS_TO_CONTINUOUS = 5;
    static constexpr uint8_t FIFO_CONTINUOUS_TO_FIFO   = 6;

    static constexpr uint8_t STATUS_P_DA = 0x01;
    static constexpr uint8_t STATUS_T_DA = 0x02;
    static constexpr uint8_t STATUS_P_OR = 0x10;
    static constexpr uint8_t STATUS_T_OR = 0x20;

    explicit LPS28DFWFull(Connection& connection);

    /** @brief Set output data rate, averaging, full-scale mode, and IIR filter.
     *  @param odr     Output data rate code (0=power-down, 1–7=1–100 Hz, 8=200 Hz).
     *  @param avg     Averaging code (0–5 for 4/8/16/32/64/128 samples, 7=512).
     *  @param fs_mode 0=Mode 1 (0–1260 hPa), 1=Mode 2 (0–4060 hPa).
     *  @param lpf_en  1 to enable the IIR low-pass filter, 0 to disable.
     *  @param lpf_cfg 0=ODR/4 bandwidth, 1=ODR/9 bandwidth.
     */
    void configure(uint8_t odr, uint8_t avg, uint8_t fs_mode, uint8_t lpf_en, uint8_t lpf_cfg);

    /** @brief Burst-read pressure and temperature.
     *
     *  @param[out] pressure    Pressure in hPa.
     *  @param[out] temperature Temperature in °C.
     */
    void read(float& pressure, float& temperature);

    /** @brief Trigger a one-shot measurement (with ODR=0000) and read the result.
     *
     *  Polls STATUS.P_DA before reading; restores the prior ODR on exit.
     *
     *  @param[out] pressure    Pressure in hPa.
     *  @param[out] temperature Temperature in °C.
     */
    void read_oneshot(float& pressure, float& temperature);

    /** @brief Return True if STATUS.P_DA is set (new pressure sample available).
     *  @return 1 if STATUS bit 0 is asserted, 0 otherwise.
     */
    uint8_t is_data_ready();

    /** @brief Program the one-point calibration offset (RPDS).
     *
     *  @param offset_hpa Offset in hPa to subtract from subsequent pressure
     *                    readings (signed). Sensitivity is 4096 LSB/hPa
     *                    (Mode 1) or 2048 LSB/hPa (Mode 2).
     */
    void set_offset(float offset_hpa);

    /** @brief Issue a software reset and wait for the chip to reboot (~2 ms). */
    void softreset();

    /** @brief Configure FIFO mode, watermark level, and stop-on-watermark.
     *  @param mode         FIFO mode (0=bypass, 1=FIFO, 2=continuous;
     *                      +4/+5/+6 for triggered variants Bypass-to-FIFO /
     *                      Bypass-to-continuous / Continuous-to-FIFO).
     *  @param wtm          Watermark level, 0–127 samples.
     *  @param stop_on_wtm  1 to limit FIFO depth to the watermark, 0 otherwise.
     */
    void fifo_configure(uint8_t mode, uint8_t wtm, uint8_t stop_on_wtm);

    /** @brief Drain up to `count` pressure samples from the FIFO.
     *
     *  @param count Number of samples to read (capped at 128).
     *  @param buf   Output buffer for hPa values, at least `count` floats long.
     */
    void fifo_read(uint8_t count, float* buf);

    /** @brief Return the number of unread samples in the FIFO.
     *  @return 0 (empty) through 128 (full).
     */
    uint8_t fifo_level();

    /** @brief Program the pressure threshold and enable interrupt sources.
     *  @param threshold_hpa Pressure threshold in hPa (15-bit unsigned).
     *                       Sensitivity is 16 LSB/hPa (Mode 1) or
     *                       8 LSB/hPa (Mode 2).
     *  @param high  1 to assert PH when pressure exceeds the threshold.
     *  @param low   1 to assert PL when pressure falls below the threshold.
     */
    void set_threshold(float threshold_hpa, uint8_t high, uint8_t low);

    /** @brief Read the WHO_AM_I register.
     *  @return Chip ID; expect 0xB4 for LPS28DFW.
     */
    uint8_t chip_id();

    /** @brief Compute altitude above sea level from the current pressure.
     *  @param sea_level_hpa Reference sea-level pressure in hPa (default 1013.25).
     *  @return Altitude in metres.
     */
    float altitude(float sea_level_hpa = 1013.25f);
};