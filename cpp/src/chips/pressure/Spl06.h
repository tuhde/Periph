#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/RegisterConnection.h"

/** @brief Decoded SPL06-007 calibration coefficients (two's complement, sign-extended). */
struct SPL06Coefficients {
    int32_t c0;   ///< 12-bit temperature offset
    int32_t c1;   ///< 12-bit temperature gain
    int32_t c00;  ///< 20-bit pressure offset
    int32_t c10;  ///< 20-bit pressure linear term
    int32_t c01;  ///< 16-bit temperature dependency
    int32_t c11;  ///< 16-bit cross term
    int32_t c20;  ///< 16-bit pressure quadratic term
    int32_t c21;  ///< 16-bit cross quadratic term
    int32_t c30;  ///< 16-bit pressure cubic term
};

/** @brief SPL06-007 barometric pressure and temperature sensor (Goertek) — minimal interface.
 *
 *  One call returns compensated temperature and pressure. The chip stays in standby between
 *  readings; each read() runs one temperature and one pressure measurement in command mode.
 *
 *  init() verifies ID = 0x10, waits for SENSOR_RDY / COEF_RDY, reads the 18 coefficient bytes
 *  and writes TMP_CFG = 0x80 (external temperature sensor, 1×), PRS_CFG = 0x04 (16×, standard
 *  precision) and CFG_REG = 0x04 (P_SHIFT). I²C address 0x77 (SDO high or open) or 0x76 (SDO low).
 *
 *  @param connection Register-addressed connection (I²C) bound to the chip's address. The
 *  constructor does no bus access.
 */
class SPL06Minimal {
public:
    explicit SPL06Minimal(RegisterConnection& connection);

    /** @brief Verify the chip, read the calibration coefficients and apply the defaults.
     *  @return true on success; false for a wrong ID, a chip that never becomes ready or a bus error.
     */
    bool init();

    /** @brief Measure temperature, then pressure (command mode), and compensate both.
     *  @param[out] temperature_c Temperature in degrees Celsius.
     *  @param[out] pressure_pa   Pressure in pascals.
     *  @return true on success.
     */
    bool read(float& temperature_c, float& pressure_pa);

protected:
    static constexpr uint8_t REG_PRS_B2   = 0x00;
    static constexpr uint8_t REG_TMP_B2   = 0x03;
    static constexpr uint8_t REG_PRS_CFG  = 0x06;
    static constexpr uint8_t REG_TMP_CFG  = 0x07;
    static constexpr uint8_t REG_MEAS_CFG = 0x08;
    static constexpr uint8_t REG_CFG_REG  = 0x09;
    static constexpr uint8_t REG_INT_STS  = 0x0A;
    static constexpr uint8_t REG_FIFO_STS = 0x0B;
    static constexpr uint8_t REG_RESET    = 0x0C;
    static constexpr uint8_t REG_ID       = 0x0D;
    static constexpr uint8_t REG_COEF     = 0x10;

    static constexpr uint8_t CHIP_ID          = 0x10;
    static constexpr uint8_t MEAS_COEF_RDY    = 0x80;
    static constexpr uint8_t MEAS_SENSOR_RDY  = 0x40;
    static constexpr uint8_t MEAS_TMP_RDY     = 0x20;
    static constexpr uint8_t MEAS_PRS_RDY     = 0x10;
    static constexpr uint8_t CMD_IDLE         = 0x00;
    static constexpr uint8_t CMD_PRESSURE     = 0x01;
    static constexpr uint8_t CMD_TEMPERATURE  = 0x02;
    static constexpr uint8_t CFG_T_SHIFT      = 0x08;
    static constexpr uint8_t CFG_P_SHIFT      = 0x04;
    static constexpr uint8_t TMP_EXT          = 0x80;

    RegisterConnection& _connection;
    SPL06Coefficients _coef = {0, 0, 0, 0, 0, 0, 0, 0, 0};
    uint8_t _p_os = 4;       ///< log2 of the pressure oversampling (16×)
    uint8_t _t_os = 0;       ///< log2 of the temperature oversampling (1×)
    uint8_t _cfg = 0x04;     ///< shadow of CFG_REG
    int32_t _last_traw = 0;

    bool _write_reg(uint8_t reg, uint8_t value);
    bool _read_reg(uint8_t reg, uint8_t* buf, size_t len);
    bool _wait_ready();
    bool _read_coefficients();
    bool _apply_defaults();
    bool _measure(uint8_t command, uint8_t flag, uint8_t os_log2, int32_t& raw);
    bool _measure_raw(int32_t& praw, int32_t& traw);
    float _temperature(int32_t traw) const;
    float _pressure(int32_t praw, int32_t traw) const;
    static int32_t _raw24(const uint8_t* b);
    static int32_t _signed(uint32_t value, uint8_t bits);
    static float _scale(uint8_t os_log2);
};

/** @brief SPL06-007 full interface — extends SPL06Minimal with oversampling and rate configuration,
 *  background (continuous) mode, the 32-entry FIFO, raw values and compensation, altitude,
 *  soft reset and the data-ready / FIFO-full interrupt.
 *
 *  The interrupt output shares the SDO pin. INT_HL stays at its reset value 0 (active low), whose
 *  idle-high level matches the default address 0x77.
 *
 *  @param connection Register-addressed connection (I²C) bound to the chip's address.
 */
class SPL06Full : public SPL06Minimal {
public:
    static constexpr uint8_t SOURCE_PRESSURE    = 0x01; ///< INT_STS.INT_PRS: pressure result ready
    static constexpr uint8_t SOURCE_TEMPERATURE = 0x02; ///< INT_STS.INT_TMP: temperature result ready
    static constexpr uint8_t SOURCE_FIFO_FULL   = 0x04; ///< INT_STS.INT_FIFO_FULL: FIFO full

    /** @brief Kind of a FIFO entry (the LSB of the raw value). */
    enum Kind : uint8_t { KIND_TEMPERATURE = 0, KIND_PRESSURE = 1 };

    /** @brief One FIFO entry: kind and compensated value (Pa for pressure, °C for temperature). */
    struct FifoEntry {
        Kind  kind;
        float value;
    };

    explicit SPL06Full(RegisterConnection& connection);

    /** @brief Measure temperature, then pressure (or, in background mode, read the latest results).
     *  @param[out] temperature_c Temperature in degrees Celsius.
     *  @param[out] pressure_pa   Pressure in pascals.
     */
    bool read(float& temperature_c, float& pressure_pa);

    /** @brief Measure temperature and pressure and return the compensated pressure.
     *  @param[out] pressure_pa Pressure in pascals.
     */
    bool read_pressure(float& pressure_pa);

    /** @brief Measure and return the temperature.
     *  @param[out] temperature_c Temperature in degrees Celsius.
     */
    bool read_temperature(float& temperature_c);

    /** @brief Raw 24-bit two's-complement results. Command mode measures temperature, then
     *  pressure; background mode reads the latest results without starting a measurement.
     *  @param[out] praw Signed raw pressure.
     *  @param[out] traw Signed raw temperature.
     */
    bool read_raw(int32_t& praw, int32_t& traw);

    /** @brief Write PRS_CFG and P_SHIFT (set above 8×).
     *  @param oversampling 1, 2, 4, 8, 16, 32, 64 or 128.
     *  @param rate         Background measurements per second: 1, 2, 4, ..., 128.
     *  @return false for values other than powers of two 1-128.
     */
    bool set_pressure_config(uint8_t oversampling = 16, uint8_t rate = 1);

    /** @brief Write TMP_CFG (always TMP_EXT = 1) and T_SHIFT (set above 8×).
     *  @param oversampling 1, 2, 4, 8, 16, 32, 64 or 128.
     *  @param rate         Background measurements per second: 1, 2, 4, ..., 128.
     *  @return false for values other than powers of two 1-128.
     */
    bool set_temperature_config(uint8_t oversampling = 1, uint8_t rate = 1);

    /** @brief Start background mode (MEAS_CTRL = 101 / 110 / 111).
     *  @return false if both pressure and temperature are false.
     */
    bool start_continuous(bool pressure = true, bool temperature = true);

    /** @brief Stop background mode and return to standby (MEAS_CTRL = 000). */
    bool stop();

    /** @brief Wait for the background ready flags and return the latest results (no new measurement).
     *  @param[out] temperature_c Temperature in degrees Celsius.
     *  @param[out] pressure_pa   Pressure in pascals.
     */
    bool read_continuous(float& temperature_c, float& pressure_pa);

    /** @brief Read the MEAS_CFG ready flags.
     *  @param[out] pressure_ready    PRS_RDY.
     *  @param[out] temperature_ready TMP_RDY.
     */
    bool data_ready(bool& pressure_ready, bool& temperature_ready);

    /** @brief Enable the 32-entry result FIFO (CFG_REG.FIFO_EN). */
    bool enable_fifo();

    /** @brief Disable the result FIFO. */
    bool disable_fifo();

    /** @brief Drain the FIFO until FIFO_EMPTY. Pressure entries are compensated with the most
     *  recent temperature entry (or the last measured temperature).
     *  @param[out] entries     Destination array.
     *  @param      max_entries Capacity of @p entries (the FIFO holds up to 32).
     *  @return number of entries read.
     */
    uint8_t read_fifo(FifoEntry* entries, uint8_t max_entries);

    /** @brief Discard the FIFO contents (RESET.FIFO_FLUSH). */
    bool flush_fifo();

    /** @brief Read FIFO_STS.
     *  @param[out] empty FIFO_EMPTY.
     *  @param[out] full  FIFO_FULL.
     */
    bool fifo_status(bool& empty, bool& full);

    /** @brief Soft reset (RESET = 0x09), wait for the ready flags, re-read the coefficients and
     *  restore the init() defaults. */
    bool reset();

    /** @brief Decoded calibration coefficients.
     *  @param[out] coefficients c0, c1, c00, c10, c01, c11, c20, c21, c30.
     */
    void coefficients(SPL06Coefficients& coefficients) const;

    /** @brief Compensate raw values with the stored coefficients (no bus access).
     *  @param praw           Signed raw pressure.
     *  @param traw           Signed raw temperature.
     *  @param p_oversampling Pressure oversampling of @p praw.
     *  @param t_oversampling Temperature oversampling of @p traw.
     *  @param[out] temperature_c Temperature in degrees Celsius.
     *  @param[out] pressure_pa   Pressure in pascals.
     *  @return false for an oversampling other than a power of two 1-128.
     */
    bool compensate(int32_t praw, int32_t traw, uint8_t p_oversampling, uint8_t t_oversampling,
                    float& temperature_c, float& pressure_pa) const;

    /** @brief Measure the pressure and convert it to altitude (international barometric formula).
     *  @param[out] altitude_m   Altitude in metres.
     *  @param      sea_level_pa Reference sea-level pressure in pascals.
     */
    bool altitude(float& altitude_m, float sea_level_pa = 101325.0f);

    /** @brief Enable interrupt sources on SDO (CFG_REG.INT_PRS / INT_TMP / INT_FIFO).
     *  @param source SOURCE_* constant or a bitwise OR of them.
     */
    bool enable_interrupt(uint8_t source);

    /** @brief Disable interrupt sources.
     *  @param source SOURCE_* constant or a bitwise OR of them.
     */
    bool disable_interrupt(uint8_t source);

    /** @brief Read INT_STS (cleared on read).
     *  @return Bitmask of SOURCE_* flags (0 = none or bus error).
     */
    uint8_t poll_interrupt();

    /** @brief Subscribe to the interrupt on SDO (falling edge, INT_HL = 0). The callback receives
     *  the SOURCE_* bitmask. Without any pin (no @p intPin and none on the connection) nothing is
     *  delivered — poll with poll_interrupt().
     *  @param callback Called with the non-zero status bitmask.
     *  @param intPin   Optional pin overriding connection.intPin().
     */
    void on_interrupt(void (*callback)(uint8_t status), InputPin* intPin = nullptr);

    /** @brief Unsubscribe from interrupt edges. */
    void off_interrupt();

private:
    static constexpr uint8_t CFG_INT_HL   = 0x80;
    static constexpr uint8_t CFG_INT_FIFO = 0x40;
    static constexpr uint8_t CFG_INT_PRS  = 0x20;
    static constexpr uint8_t CFG_INT_TMP  = 0x10;
    static constexpr uint8_t CFG_FIFO_EN  = 0x02;

    uint8_t _p_rate = 0;
    uint8_t _t_rate = 0;
    uint8_t _continuous = 0;
    void (*_callback)(uint8_t) = nullptr;
    InputPin* _intPinUsed = nullptr;
    static SPL06Full* _activeInstance;
    static void _edgeTrampoline();
    void _handleEdge();
    static int8_t _log2(uint8_t value);
    static uint8_t _int_bits(uint8_t source);
    bool _write_cfg();
};
