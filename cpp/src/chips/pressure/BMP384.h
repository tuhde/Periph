#pragma once
#include <stdint.h>
#include "../../connection/Connection.h"

/** @brief BMP384 high-precision barometric pressure and temperature sensor — minimal interface.
 *
 *  Provides calibrated temperature (°C) and pressure (hPa) with no configuration
 *  beyond the connection. I²C address is 0x76 (SDO=GND) or 0x77 (SDO=VDD).
 *
 *  Default: normal mode, osr_p=×16, osr_t=×2, iir=coef 3, ODR=25 Hz.
 *
 *  @param connection Configured I²C or SPI connection pointing at the device.
 *  @param spi       Set true for SPI bus (clears bit 7 on writes).
 */
class BMP384Minimal {
public:
    explicit BMP384Minimal(Connection& connection, bool spi = false);

    /** @brief Read calibrated temperature.
     *  @return Temperature in degrees Celsius.
     */
    float temperature();

    /** @brief Read calibrated pressure.
     *
     *  Reads both ADCs, refreshes t_lin, and runs the pressure compensation.
     *  Self-contained — may be called without a prior temperature() call.
     *
     *  @return Pressure in hectopascals (hPa).
     */
    float pressure();

    // Calibration / compensation state — public so unit tests can inject
    // known datasheet values and verify the algorithm.
    double _par_t1 = 0.0;
    double _par_t2 = 0.0;
    double _par_t3 = 0.0;
    double _par_p1 = 0.0;
    double _par_p2 = 0.0;
    double _par_p3 = 0.0;
    double _par_p4 = 0.0;
    double _par_p5 = 0.0;
    double _par_p6 = 0.0;
    double _par_p7 = 0.0;
    double _par_p8 = 0.0;
    double _par_p9 = 0.0;
    double _par_p10 = 0.0;
    double _par_p11 = 0.0;

    double _compensate_temperature(uint32_t uncomp_temp);
    double _compensate_pressure(uint32_t uncomp_press);

protected:
    static constexpr uint8_t REG_CHIP_ID    = 0x00;
    static constexpr uint8_t REG_STATUS     = 0x03;
    static constexpr uint8_t REG_DATA_0     = 0x04;
    static constexpr uint8_t REG_PWR_CTRL   = 0x1B;
    static constexpr uint8_t REG_OSR        = 0x1C;
    static constexpr uint8_t REG_ODR        = 0x1D;
    static constexpr uint8_t REG_CONFIG     = 0x1F;
    static constexpr uint8_t REG_CMD        = 0x7E;
    static constexpr uint8_t REG_CAL_START  = 0x31;
    static constexpr uint8_t REG_CAL_END    = 0x45;
    static constexpr uint8_t REG_CAL_LEN    = 0x15;  // 21 bytes (0x45 - 0x31 + 1)

    static constexpr uint8_t CHIP_ID        = 0x50;
    static constexpr uint8_t SOFT_RESET_CMD = 0xB6;
    static constexpr uint8_t FIFO_FLUSH_CMD = 0xB0;

    static constexpr uint32_t MEAS_TIME_MS  = 40;

    static constexpr uint8_t MODE_SLEEP     = 0x00;
    static constexpr uint8_t MODE_FORCED    = 0x01;
    static constexpr uint8_t MODE_NORMAL    = 0x03;

    static constexpr uint8_t PWR_PRESS_EN   = 0x01;
    static constexpr uint8_t PWR_TEMP_EN    = 0x02;

    Connection& _connection;
    bool      _spi;
    uint8_t   _osr_p = 4;
    uint8_t   _osr_t = 1;
    uint8_t   _iir   = 2;
    uint8_t   _odr   = 0x03;   // 25 Hz
    uint8_t   _mode  = MODE_NORMAL;
    double    _t_lin = 0.0;

    void     _read_calibration();
    void     _verify_chip_id();
    void     _apply_config();
    void     _write_reg(uint8_t reg, uint8_t value);
    void     _read_reg(uint8_t reg, uint8_t* buf, size_t len);
    void     _read_burst(uint32_t& uncomp_press, uint32_t& uncomp_temp);

    static int8_t _s8(uint8_t b) { return b >= 128 ? (int8_t)(b - 256) : (int8_t)b; }
};

/** @brief BMP384 full interface — extends BMP384Minimal with configuration, mode control, and FIFO access.
 *
 *  Adds oversampling/IIR/ODR configuration, power-mode switching, data-ready
 *  polling, soft reset, and the 512-byte FIFO (with watermark, stop-on-full,
 *  and per-frame decoding).
 *
 *  @param connection Configured I²C or SPI connection pointing at the device.
 *  @param spi       Set true for SPI bus (clears bit 7 on writes).
 */
class BMP384Full : public BMP384Minimal {
public:
    static constexpr uint8_t MODE_SLEEP     = 0x00;
    static constexpr uint8_t MODE_FORCED    = 0x01;
    static constexpr uint8_t MODE_NORMAL    = 0x03;

    // FIFO frame header bytes.
    static constexpr uint8_t FIFO_HEADER_PRESS    = 0x84;
    static constexpr uint8_t FIFO_HEADER_TEMP     = 0x90;
    static constexpr uint8_t FIFO_HEADER_SENSORT  = 0xA0;
    static constexpr uint8_t FIFO_HEADER_ERROR    = 0x44;
    static constexpr uint8_t FIFO_HEADER_EMPTY    = 0x80;

    explicit BMP384Full(Connection& connection, bool spi = false);

    /** @brief Write OSR, CONFIG, and ODR; validate ODR ≥ T_conv at chosen oversampling. */
    void configure(uint8_t osr_p, uint8_t osr_t, uint8_t iir_filter, uint8_t odr_sel);

    /** @brief Read both pressure and temperature in a single burst.
     *  @param[out] pressure_hpa    Pressure in hPa.
     *  @param[out] temperature_c   Temperature in °C.
     */
    void read(float& pressure_hpa, float& temperature_c);

    /** @brief Trigger a forced measurement, wait T_conv, return both values. */
    void read_forced(float& pressure_hpa, float& temperature_c);

    /** @brief Set the power mode. */
    void set_mode(uint8_t mode);

    /** @brief True if STATUS.drdy_press is set. */
    bool is_data_ready();

    /** @brief Soft-reset, re-read calibration, re-apply configuration. */
    void softreset();

    /** @brief Configure FIFO source, watermark, and stop-on-full behaviour. */
    void fifo_configure(bool press_en, bool temp_en, uint16_t wtm, bool stop_on_full = false);

    /** @brief Read and parse every available FIFO frame.
     *
     *  Caller-owned output buffers are filled with the first @p max_frames parsed
     *  frames. Each frame populates @p type_out (one of "pressure", "temperature",
     *  "sensortime", "error", "empty") and @p value_out (hPa for pressure, °C for
     *  temperature, raw sensor-time ticks for sensortime, 0 for error/empty).
     *
     *  @return Number of frames returned.
     */
    size_t fifo_read(const char** type_out, double* value_out, size_t max_frames);

    /** @brief Flush the FIFO. */
    void fifo_flush();

    /** @brief Compute altitude above sea level from the current pressure. */
    float altitude(float sea_level_hpa = 1013.25f);

private:
    void _trigger_forced();
    void _apply_pwr();
    double _compensate_pressure_with_t_lin(uint32_t uncomp_press, double t_lin);
};
