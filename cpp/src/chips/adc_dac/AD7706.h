#pragma once
#include <stdint.h>
#include "../../connection/Connection.h"
#include "../../connection/OutputPin.h"

/** @brief AD7706 3-channel, 16-bit sigma-delta ADC — base class.
 *
 * Three pseudo-differential channels (AIN1, AIN2, AIN3), each measured
 * relative to a single shared `COMMON` pin. Two-phase register-access
 * protocol: write the Communication Register (selects target register +
 * read/write direction + channel) and then transfer the data bytes in a
 * single CS-held transaction. DRDY is polled over SPI by inspecting bit 7
 * of the Communication Register, matching the datasheet's 3-wire
 * microcontroller interface technique (no dedicated DRDY GPIO required).
 *
 * Minimal default configuration (gain 1, bipolar, unbuffered, 50 Hz
 * output rate on a 2.4576/4.9152 MHz clock or 20 Hz on 1/2 MHz, with
 * Channel 1 self-calibrated once at construction).
 */
class _AD7706Base {
public:
    /** @brief Master clock frequencies the driver supports. */
    static constexpr uint32_t MCLK_1MHZ      = 1000000;
    static constexpr uint32_t MCLK_2MHZ      = 2000000;
    static constexpr uint32_t MCLK_2_4576MHZ = 2457600;
    static constexpr uint32_t MCLK_4_9152MHZ = 4915200;

    /** @brief PGA gain settings exposed by configure(). */
    static constexpr uint8_t GAIN_1   = 0;
    static constexpr uint8_t GAIN_2   = 1;
    static constexpr uint8_t GAIN_4   = 2;
    static constexpr uint8_t GAIN_8   = 3;
    static constexpr uint8_t GAIN_16  = 4;
    static constexpr uint8_t GAIN_32  = 5;
    static constexpr uint8_t GAIN_64  = 6;
    static constexpr uint8_t GAIN_128 = 7;

protected:
    /** @brief Construct the driver and run the Initialization Sequence.
     *  @param connection SPI connection bound to the device (Mode 3, <=5 MHz).
     *  @param vref       Externally-supplied reference voltage in V.
     *  @param mclk_hz    Master clock frequency in Hz; one of MCLK_*.
     *  @param reset_pin  Optional OutputPin driving RESET (nullptr = unused).
     */
    _AD7706Base(Connection& connection, float vref, uint32_t mclk_hz, OutputPin* reset_pin);

    /** @brief Build a Communication Register byte. */
    static uint8_t _comm_byte(uint8_t reg, bool read, uint8_t channel);

    /** @brief Poll Communication Register 0/DRDY until ready. */
    void _wait_drdy();

    /** @brief Pulse the hardware RESET line low then high. */
    void _hardware_reset();

    /** @brief Write Clock Register with CLKDIV/CLK from mclk_hz and FS bits from rate. */
    void _configure_clock(uint16_t output_rate_hz);

    /** @brief Write n_bytes to a register on the given channel. */
    void _write_reg_channel(uint8_t reg, uint32_t value, uint8_t channel, uint8_t n_bytes);

    /** @brief Read n_bytes from a register on the given channel (big-endian unsigned). */
    uint32_t _read_reg_channel(uint8_t reg, uint8_t channel, uint8_t n_bytes);

    /** @brief Convert a 16-bit code to volts using the given gain/bipolar. */
    float _code_to_voltage(uint16_t code, uint8_t gain, bool bipolar) const;

    Connection&  _connection;
    OutputPin*   _reset_pin;
    float        _vref;
    uint32_t     _mclk_hz;
    uint8_t      _gain;
    bool         _bipolar;
    bool         _buffered;

    static constexpr uint8_t _REG_COMM    = 0x00;
    static constexpr uint8_t _REG_SETUP   = 0x10;
    static constexpr uint8_t _REG_CLOCK   = 0x20;
    static constexpr uint8_t _REG_DATA    = 0x30;
    static constexpr uint8_t _REG_OFFSET  = 0x60;
    static constexpr uint8_t _REG_GAIN    = 0x70;

    static constexpr uint8_t _RW_WRITE = 0x00;
    static constexpr uint8_t _RW_READ  = 0x08;

    static constexpr uint8_t _CH1 = 0x00;
    static constexpr uint8_t _CH2 = 0x01;
    static constexpr uint8_t _CH3 = 0x03;

    static constexpr uint8_t _MODE_NORMAL   = 0x00;
    static constexpr uint8_t _MODE_SELF_CAL = 0x40;
    static constexpr uint8_t _MODE_ZERO_SYS = 0x80;
    static constexpr uint8_t _MODE_FULL_SYS = 0xC0;

    static constexpr uint8_t _GAIN_BITS[8] = {
        0x00, 0x08, 0x10, 0x18, 0x20, 0x28, 0x30, 0x38,
    };

    static constexpr uint8_t _BIPOLAR   = 0x00;
    static constexpr uint8_t _UNIPOLAR  = 0x04;
    static constexpr uint8_t _UNBUFFERED = 0x00;
    static constexpr uint8_t _BUFFERED  = 0x02;
    static constexpr uint8_t _FSYNC_RUN = 0x00;
    static constexpr uint8_t _STBY_RUN  = 0x00;
    static constexpr uint8_t _STBY_SLEEP = 0x04;
    static constexpr uint8_t _DRDY_MASK = 0x80;

    static constexpr uint16_t _FS_RATES_1MHZ[4]   = { 20, 25, 100, 200 };
    static constexpr uint16_t _FS_RATES_2_4MHZ[4] = { 50, 60, 250, 500 };
};

/** @brief AD7706 minimal driver — Channel 1 voltage read with sensible defaults. */
class AD7706Minimal : public _AD7706Base {
public:
    /** @brief Construct and initialise the AD7706 with default configuration.
     *  @param connection SPI connection bound to the device.
     *  @param vref       Externally-supplied reference voltage in V.
     *  @param mclk_hz    Master clock frequency in Hz.
     *  @param reset_pin  Optional OutputPin driving RESET.
     */
    AD7706Minimal(Connection& connection, float vref, uint32_t mclk_hz, OutputPin* reset_pin = nullptr)
        : _AD7706Base(connection, vref, mclk_hz, reset_pin) {}

    /** @brief Block until DRDY, then read and return the raw 16-bit Data Register code on Channel 1.
     *  @return Raw 16-bit code (0–65535).
     */
    uint16_t read_raw();

    /** @brief Block until DRDY, then return the input voltage on Channel 1 in V.
     *  @return Voltage in V.
     */
    float read_voltage();
};

/** @brief AD7706 full driver — adds per-channel configuration, calibration, and power control. */
class AD7706Full : public AD7706Minimal {
public:
    AD7706Full(Connection& connection, float vref, uint32_t mclk_hz, OutputPin* reset_pin = nullptr)
        : AD7706Minimal(connection, vref, mclk_hz, reset_pin) {}

    using AD7706Minimal::read_raw;
    using AD7706Minimal::read_voltage;

    /** @brief Write the Setup and Clock Registers for the given channel.
     *
     *  Does not calibrate — call self_calibrate() (or one of the
     *  system-calibration methods) afterward.
     *
     *  @param channel       1, 2, or 3 (AIN1, AIN2, AIN3 — all relative to COMMON).
     *  @param gain          PGA gain: 1, 2, 4, 8, 16, 32, 64, or 128.
     *  @param bipolar       true for bipolar, false for unipolar.
     *  @param buffered      true to enable the analog input buffer.
     *  @param output_rate_hz One of the four rates in the mclk_hz family.
     */
    void configure(uint8_t channel, uint8_t gain, bool bipolar, bool buffered, uint16_t output_rate_hz);

    /** @brief Block until DRDY, then read the raw 16-bit code for the channel.
     *  @param channel 1, 2, or 3.
     *  @return Raw 16-bit code (0–65535).
     */
    uint16_t read_raw(uint8_t channel);

    /** @brief Block until DRDY, then return the input voltage on the channel in V.
     *  @param channel 1, 2, or 3.
     *  @return Voltage in V.
     */
    float read_voltage(uint8_t channel);

    /** @brief Run an internal self-calibration on the channel.
     *  @param channel 1, 2, or 3.
     */
    void self_calibrate(uint8_t channel);

    /** @brief Run a zero-scale system calibration. The caller must present the zero-scale voltage at AIN first.
     *  @param channel 1, 2, or 3.
     */
    void system_calibrate_zero(uint8_t channel);

    /** @brief Run a full-scale system calibration. The caller must present the full-scale voltage at AIN first.
     *  @param channel 1, 2, or 3.
     */
    void system_calibrate_full(uint8_t channel);

    /** @brief Read the 24-bit Zero-Scale Calibration Register for the channel.
     *  @param channel 1, 2, or 3.
     *  @return 24-bit unsigned offset coefficient.
     */
    uint32_t get_offset_calibration(uint8_t channel);

    /** @brief Write a 24-bit Zero-Scale Calibration Register for the channel.
     *  @param value   24-bit unsigned offset coefficient.
     *  @param channel 1, 2, or 3.
     */
    void set_offset_calibration(uint32_t value, uint8_t channel);

    /** @brief Read the 24-bit Full-Scale Calibration Register for the channel.
     *  @param channel 1, 2, or 3.
     *  @return 24-bit unsigned gain coefficient.
     */
    uint32_t get_gain_calibration(uint8_t channel);

    /** @brief Write a 24-bit Full-Scale Calibration Register for the channel.
     *  @param value   24-bit unsigned gain coefficient.
     *  @param channel 1, 2, or 3.
     */
    void set_gain_calibration(uint32_t value, uint8_t channel);

    /** @brief Enter standby (~10 µA, registers retained). */
    void standby();

    /** @brief Exit standby and block until a fresh conversion is available. */
    void wakeup();

    /** @brief Pulse the hardware RESET line. Requires a reset_pin to have been supplied. */
    void reset();
};
