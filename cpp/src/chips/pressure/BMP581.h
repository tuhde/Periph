#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/Connection.h"

/** @brief BMP581 MEMS barometric pressure + temperature sensor — minimal interface.
 *
 *  Provides calibrated temperature (°C) and pressure (Pa) with no
 *  configuration beyond the connection. I²C address is 0x46 (SDO=GND) or
 *  0x47 (SDO=VDDIO).
 *
 *  Default: NORMAL mode, ODR 1 Hz, press_en=1, osr_p=x1, osr_t=x1,
 *  IIR bypass, FIFO disabled, INT_SOURCE=0.
 *
 *  @param connection Configured I²C or SPI connection pointing at the device.
 *  @param spi        Set true for SPI bus (masks bit 7 on writes).
 */
class BMP581Minimal {
public:
    explicit BMP581Minimal(Connection& connection, bool spi = false);

    /** @brief Read calibrated pressure.
     *  @return Pressure in Pa.
     */
    float pressure();

    /** @brief Read calibrated temperature.
     *  @return Temperature in degrees Celsius.
     */
    float temperature();

    /** @brief Read both pressure and temperature atomically in a single burst read.
     *  @param[out] pressure_pa Pressure in Pa.
     *  @param[out] temperature_c Temperature in °C.
     */
    void both(float& pressure_pa, float& temperature_c);

protected:
    static constexpr uint8_t REG_CHIP_ID       = 0x01;
    static constexpr uint8_t REG_STATUS        = 0x28;
    static constexpr uint8_t REG_INT_STATUS    = 0x27;
    static constexpr uint8_t REG_OSR_CONFIG    = 0x36;
    static constexpr uint8_t REG_ODR_CONFIG    = 0x37;
    static constexpr uint8_t REG_CMD           = 0x7E;
    static constexpr uint8_t REG_TEMP_XLSB     = 0x1D;
    static constexpr uint8_t REG_PRESS_XLSB    = 0x20;

    static constexpr uint8_t CHIP_ID_EXPECTED  = 0x50;
    static constexpr uint8_t SOFT_RESET_CMD    = 0xB6;
    static constexpr uint8_t STATUS_NVM_RDY    = 0x02;
    static constexpr uint8_t STATUS_NVM_ERR    = 0x04;
    static constexpr uint8_t INT_STATUS_DRDY   = 0x01;
    /** Power mode: forced. Shared with BMP581Full::MODE_FORCED. */
    static constexpr uint8_t MODE_FORCED       = 2;

    Connection& _connection;
    bool _spi;
    uint8_t _odr;
    uint8_t _pwr_mode;
    uint8_t _osr_p;
    uint8_t _osr_t;
    bool _press_en;

    void _init();
    void _write_reg(uint8_t reg, uint8_t value);
    void _read_reg(uint8_t reg, uint8_t* buf, size_t len);
    void _spi_dummy_read();
};

/** @brief BMP581 full interface — extends BMP581Minimal with configuration,
 *  FIFO, interrupts, OOR detection, and NVM access.
 *
 *  Adds power-mode control, oversampling, IIR filter, FIFO configuration,
 *  interrupt configuration, out-of-range threshold configuration, and
 *  NVM read/write.
 *
 *  @param connection Configured I²C or SPI connection pointing at the device.
 *  @param spi        Set true for SPI bus (masks bit 7 on writes).
 */
class BMP581Full : public BMP581Minimal {
public:
    static constexpr uint8_t OSR_1X   = 0;
    static constexpr uint8_t OSR_2X   = 1;
    static constexpr uint8_t OSR_4X   = 2;
    static constexpr uint8_t OSR_8X   = 3;
    static constexpr uint8_t OSR_16X  = 4;
    static constexpr uint8_t OSR_32X  = 5;
    static constexpr uint8_t OSR_64X  = 6;
    static constexpr uint8_t OSR_128X = 7;

    static constexpr uint8_t MODE_STANDBY    = 0;
    static constexpr uint8_t MODE_NORMAL     = 1;
    static constexpr uint8_t MODE_FORCED     = 2;
    static constexpr uint8_t MODE_CONTINUOUS = 3;

    static constexpr uint8_t IIR_BYPASS    = 0;
    static constexpr uint8_t IIR_COEFF_1   = 1;
    static constexpr uint8_t IIR_COEFF_3   = 2;
    static constexpr uint8_t IIR_COEFF_7   = 3;
    static constexpr uint8_t IIR_COEFF_15  = 4;
    static constexpr uint8_t IIR_COEFF_31  = 5;
    static constexpr uint8_t IIR_COEFF_63  = 6;
    static constexpr uint8_t IIR_COEFF_127 = 7;

    static constexpr uint8_t FIFO_DISABLED    = 0;
    static constexpr uint8_t FIFO_TEMP        = 1;
    static constexpr uint8_t FIFO_PRESS       = 2;
    static constexpr uint8_t FIFO_BOTH        = 3;

    static constexpr uint8_t FIFO_STREAM       = 0;
    static constexpr uint8_t FIFO_STOP_ON_FULL = 1;

    static constexpr uint8_t INT_SOURCE_DRDY       = 0x01;
    static constexpr uint8_t INT_SOURCE_FIFO_FULL  = 0x02;
    static constexpr uint8_t INT_SOURCE_FIFO_THS   = 0x04;
    static constexpr uint8_t INT_SOURCE_OOR_P      = 0x08;

    explicit BMP581Full(Connection& connection, bool spi = false);

    /** @brief Write OSR_CONFIG and ODR_CONFIG atomically.
     *  @param odr      ODR field 0x00-0x1F (default 0x1C = 1 Hz).
     *  @param osr_p    Pressure oversampling 0-7 (default 0 = x1).
     *  @param osr_t    Temperature oversampling 0-7 (default 0 = x1).
     *  @param press_en True to enable pressure measurements.
     */
    void configure(uint8_t odr, uint8_t osr_p, uint8_t osr_t, bool press_en);

    /** @brief Set the power mode (preserves the current ODR setting).
     *  @param mode MODE_STANDBY (0), MODE_NORMAL (1), MODE_FORCED (2), MODE_CONTINUOUS (3).
     */
    void set_mode(uint8_t mode);

    /** @brief Trigger a single FORCED measurement, wait for completion, return readings.
     *  @param[out] pressure_pa Pressure in Pa.
     *  @param[out] temperature_c Temperature in °C.
     */
    void forced(float& pressure_pa, float& temperature_c);

    /** @brief Compute altitude from the current pressure reading.
     *  @param sea_level_pa Reference pressure in Pa (default 101325).
     *  @return Altitude in metres.
     */
    float altitude(float sea_level_pa = 101325.0f);

    /** @brief Issue a soft reset and re-initialise the chip. */
    void software_reset();

    /** @brief Read CHIP_ID (0x01). @return 0x50 for a genuine BMP581. */
    uint8_t chip_id();

    /** @brief Read REV_ID (0x02). @return ASIC revision identifier. */
    uint8_t rev_id();

    /** @brief Read STATUS (0x28). @return Raw status byte. */
    uint8_t status();

    /** @brief Read INT_STATUS (0x27). @return Raw interrupt status byte (clear-on-read). */
    uint8_t interrupt_status();

    /** @brief Check whether a new data sample is available.
     *  Reads INT_STATUS (clear-on-read).
     *  @return True if drdy_data_reg is set.
     */
    bool data_ready();

    /** @brief Configure INT pin: latching, polarity, drive mode, pin enable.
     *  @param mode        0 = pulsed, 1 = latched.
     *  @param polarity    0 = active-low, 1 = active-high.
     *  @param open_drain  True for open-drain output.
     *  @param enable      True to enable the INT pin driver.
     */
    void configure_interrupt(uint8_t mode, uint8_t polarity, bool open_drain, bool enable);

    /** @brief Enable/disable the data-ready interrupt source. */
    void enable_drdy_interrupt(bool enable);

    /** @brief Enable/disable FIFO threshold and FIFO-full interrupt sources. */
    void enable_fifo_interrupt(bool threshold, bool full);

    /** @brief Enable/disable the pressure out-of-range interrupt source. */
    void enable_oor_interrupt(bool enable);

    /** @brief Set IIR filter coefficients for pressure and temperature.
     *  Also sets shdw_sel_iir_p/t in DSP_CONFIG so the data registers hold
     *  post-IIR values.
     *  @param coeff_p Pressure filter coefficient 0-7 (0 = bypass).
     *  @param coeff_t Temperature filter coefficient 0-7 (0 = bypass).
     */
    void set_iir_filter(uint8_t coeff_p, uint8_t coeff_t);

    /** @brief Configure FIFO source, mode, and threshold.
     *  Must be called in STANDBY mode.
     *  @param frame_sel FIFO_DISABLED (0), FIFO_TEMP (1), FIFO_PRESS (2), FIFO_BOTH (3).
     *  @param mode      FIFO_STREAM (0) or FIFO_STOP_ON_FULL (1).
     *  @param threshold 0-31 frames (0 = disabled).
     */
    void configure_fifo(uint8_t frame_sel, uint8_t mode, uint8_t threshold);

    /** @brief Read the number of frames currently in the FIFO.
     *  @return Frame count 0-32.
     */
    uint8_t fifo_count();

    /** @brief Read OSR_EFF. @return (osr_p_eff, osr_t_eff) as 0-7 values. */
    void effective_osr(uint8_t& osr_p_eff, uint8_t& osr_t_eff);

    /** @brief Check whether the current ODR/OSR combination is valid.
     *  @return True if odr_is_valid bit is set.
     */
    bool odr_is_valid();

    /** @brief Configure the out-of-range pressure detector.
     *  @param threshold_pa Pressure threshold in Pa.
     *  @param range_pa     Symmetric +/- window around the threshold in Pa.
     *  @param count_limit  0-3 successive over-threshold events required to fire.
     */
    void set_oor_threshold(float threshold_pa, float range_pa, uint8_t count_limit);

    /** @brief Read one user NVM row.
     *  @param row NVM row address 0x20-0x22.
     *  @return 16-bit value stored in the row.
     */
    uint16_t nvm_read(uint8_t row);

    /** @brief Write one user NVM row.
     *  Limited to 10,000 total write cycles across all rows.
     *  @param row   NVM row address 0x20-0x22.
     *  @param value 16-bit value to store.
     */
    void nvm_write(uint8_t row, uint16_t value);

protected:
    static constexpr uint8_t REG_DSP_CONFIG    = 0x30;
    static constexpr uint8_t REG_DSP_IIR       = 0x31;
    static constexpr uint8_t REG_OOR_THR_P_LSB = 0x32;
    static constexpr uint8_t REG_OOR_THR_P_MSB = 0x33;
    static constexpr uint8_t REG_OOR_RANGE     = 0x34;
    static constexpr uint8_t REG_OOR_CONFIG    = 0x35;
    static constexpr uint8_t REG_FIFO_SEL      = 0x18;
    static constexpr uint8_t REG_FIFO_CONFIG   = 0x16;
    static constexpr uint8_t REG_FIFO_COUNT    = 0x17;
    static constexpr uint8_t REG_FIFO_DATA     = 0x29;
    static constexpr uint8_t REG_INT_SOURCE    = 0x15;
    static constexpr uint8_t REG_INT_CONFIG    = 0x14;
    static constexpr uint8_t REG_REV_ID        = 0x02;
    static constexpr uint8_t REG_OSR_EFF       = 0x38;
    static constexpr uint8_t REG_NVM_ADDR      = 0x2B;
    static constexpr uint8_t REG_NVM_DATA_LSB  = 0x2C;
    static constexpr uint8_t REG_NVM_DATA_MSB  = 0x2D;

private:
    void _set_int_source(uint8_t source, bool enable);
};