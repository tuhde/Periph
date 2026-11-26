#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/RegisterConnection.h"

/** @brief CCS811 I²C indoor air quality gas sensor (ScioSense / ams) — minimal interface.
 *
 *  One call returns the equivalent CO₂ (eCO₂) and total VOC (TVOC) values of the chip's
 *  internal algorithm.
 *
 *  init() verifies HW_ID = 0x81, writes APP_START if the chip is still in boot mode (fails if
 *  no valid application is loaded) and writes MEAS_MODE = 0x10 (drive mode 1, one sample per
 *  second, interrupts off). No environment data is written (the chip assumes 50 %RH, 25 °C).
 *
 *  The chip stretches the I²C clock; tie nWAKE low. New sensors need a 48 h burn-in and values
 *  settle about 20 min after each power-up. I²C address 0x5A (ADDR low) or 0x5B (ADDR high).
 *
 *  @param connection Register-addressed connection (I²C) bound to the chip's address. The
 *  constructor does no bus access.
 */
class CCS811Minimal {
public:
    static constexpr uint8_t STATUS_FW_MODE    = 0x80; ///< 1 = application mode
    static constexpr uint8_t STATUS_APP_VALID  = 0x10; ///< valid application firmware loaded
    static constexpr uint8_t STATUS_DATA_READY = 0x08; ///< new sample in ALG_RESULT_DATA
    static constexpr uint8_t STATUS_ERROR      = 0x01; ///< error, see ERROR_ID

    explicit CCS811Minimal(RegisterConnection& connection);

    /** @brief Verify the chip, start the application firmware and select drive mode 1.
     *  @return true on success; false for a wrong HW_ID, no valid application, a chip that does
     *  not enter application mode, or a bus error.
     */
    bool init();

    /** @brief Wait for the next sample (DATA_READY) and read eCO₂ and TVOC.
     *  @param[out] eco2_ppm Equivalent CO₂ in ppm (400–8192).
     *  @param[out] tvoc_ppb Total VOC in ppb (0–1187).
     *  @return true on success; false on timeout, bus error or when the chip reports an error
     *  (STATUS.ERROR; ERROR_ID stays unread for the caller).
     */
    bool read(uint16_t& eco2_ppm, uint16_t& tvoc_ppb);

protected:
    static constexpr uint8_t REG_STATUS          = 0x00;
    static constexpr uint8_t REG_MEAS_MODE       = 0x01;
    static constexpr uint8_t REG_ALG_RESULT_DATA = 0x02;
    static constexpr uint8_t REG_RAW_DATA        = 0x03;
    static constexpr uint8_t REG_ENV_DATA        = 0x05;
    static constexpr uint8_t REG_NTC             = 0x06;
    static constexpr uint8_t REG_THRESHOLDS      = 0x10;
    static constexpr uint8_t REG_BASELINE        = 0x11;
    static constexpr uint8_t REG_HW_ID           = 0x20;
    static constexpr uint8_t REG_HW_VERSION      = 0x21;
    static constexpr uint8_t REG_FW_BOOT_VERSION = 0x23;
    static constexpr uint8_t REG_FW_APP_VERSION  = 0x24;
    static constexpr uint8_t REG_ERROR_ID        = 0xE0;
    static constexpr uint8_t REG_APP_START       = 0xF4;
    static constexpr uint8_t REG_SW_RESET        = 0xFF;

    static constexpr uint8_t HW_ID  = 0x81;
    static constexpr uint8_t MODE_1 = 0x10;

    RegisterConnection& _connection;
    uint8_t _meas_mode = MODE_1;  ///< shadow of MEAS_MODE

    bool _write_reg(uint8_t reg, const uint8_t* data, size_t len);
    bool _read_reg(uint8_t reg, uint8_t* buf, size_t len);
    bool _start_app();
    bool _wait_data_ready();
    bool _read_result(uint16_t& eco2_ppm, uint16_t& tvoc_ppb);
};

/** @brief Bootloader / application firmware version (major.minor.trivial). */
struct CCS811Version {
    uint8_t major;
    uint8_t minor;
    uint8_t trivial;
};

/** @brief CCS811 full interface — extends CCS811Minimal with drive modes, raw sensor data,
 *  environment compensation, the NTC input, thresholds, baseline save/restore, versions, soft
 *  reset and the nINT interrupt.
 *
 *  nINT is active low and open-drain (external pull-up); it is released when ALG_RESULT_DATA
 *  is read.
 *
 *  @param connection Register-addressed connection (I²C) bound to the chip's address.
 */
class CCS811Full : public CCS811Minimal {
public:
    static constexpr uint8_t SOURCE_DATA_READY = 0x01; ///< MEAS_MODE.INT_DATARDY: new sample
    static constexpr uint8_t SOURCE_THRESHOLD  = 0x02; ///< INT_DATARDY + INT_THRESH: eCO₂ zone change

    explicit CCS811Full(RegisterConnection& connection);

    /** @brief Wait for the next sample and return the equivalent CO₂.
     *  @param[out] eco2_ppm eCO₂ in ppm.
     */
    bool read_eco2(uint16_t& eco2_ppm);

    /** @brief Wait for the next sample and return the total VOC.
     *  @param[out] tvoc_ppb TVOC in ppb.
     */
    bool read_tvoc(uint16_t& tvoc_ppb);

    /** @brief Read RAW_DATA.
     *  @param[out] current_ua Sense current, 0–63 µA.
     *  @param[out] voltage_v  Voltage across the sensor (ADC × 1.65 / 1023).
     */
    bool read_raw(uint8_t& current_ua, float& voltage_v);

    /** @brief Read STATUS.DATA_READY.
     *  @param[out] ready true if a new sample is waiting.
     */
    bool data_ready(bool& ready);

    /** @brief Select the drive mode, keeping the interrupt bits.
     *
     *  0 idle, 1 every second, 2 every 10 s, 3 every 60 s, 4 every 250 ms (raw data only). When
     *  moving to a slower mode, idle (mode 0) first; the first sample at the new rate may be stale.
     *  @param mode Drive mode 0–4.
     *  @return false for a mode above 4 or a bus error.
     */
    bool set_mode(uint8_t mode);

    /** @brief Write ambient humidity and temperature (ENV_DATA) for the compensation.
     *  @param humidity_rh   Relative humidity in % (0–100).
     *  @param temperature_c Temperature in °C (-25 to 102).
     *  @return false for out-of-range values or a bus error.
     */
    bool set_environment(float humidity_rh, float temperature_c);

    /** @brief Read the NTC circuit voltages.
     *  @param[out] v_ref_mv Voltage across R_REF in mV.
     *  @param[out] v_ntc_mv Voltage across R_NTC in mV.
     */
    bool read_ntc(uint16_t& v_ref_mv, uint16_t& v_ntc_mv);

    /** @brief NTC resistance V_NTC × R_REF / V_REF.
     *  @param r_ref_ohm       Reference resistor in ohms.
     *  @param[out] r_ntc_ohm  NTC resistance in ohms.
     *  @return false on a bus error or when V_REF is 0 (no NTC circuit).
     */
    bool ntc_resistance(float r_ref_ohm, float& r_ntc_ohm);

    /** @brief Set the eCO₂ boundaries for the threshold interrupt.
     *  @param low_medium_ppm  Low-to-medium boundary in ppm.
     *  @param medium_high_ppm Medium-to-high boundary in ppm.
     *  @param hysteresis_ppm  Hysteresis in ppm.
     *  @return false if low_medium_ppm ≥ medium_high_ppm or on a bus error.
     */
    bool set_thresholds(uint16_t low_medium_ppm = 1500, uint16_t medium_high_ppm = 2500,
                        uint8_t hysteresis_ppm = 50);

    /** @brief Read the encoded algorithm baseline (save it to non-volatile storage).
     *  @param[out] baseline 16-bit baseline.
     */
    bool get_baseline(uint16_t& baseline);

    /** @brief Restore a saved baseline (only after about 20 min of operation).
     *  @param baseline Value from get_baseline().
     */
    bool set_baseline(uint16_t baseline);

    /** @brief Read STATUS.
     *  @param[out] status STATUS byte (STATUS_* bits).
     */
    bool status(uint8_t& status);

    /** @brief Read ERROR_ID (bit 0 write-invalid … bit 5 heater supply).
     *  @param[out] error_id ERROR_ID byte.
     */
    bool error(uint8_t& error_id);

    /** @brief Read the hardware ID and version.
     *  @param[out] hw_id      HW_ID (0x81).
     *  @param[out] hw_version HW_VERSION (0x1X).
     */
    bool hardware_version(uint8_t& hw_id, uint8_t& hw_version);

    /** @brief Read the bootloader and application firmware versions.
     *  @param[out] boot Bootloader version.
     *  @param[out] app  Application version.
     */
    bool firmware_version(CCS811Version& boot, CCS811Version& app);

    /** @brief SW_RESET into boot mode, then start the application and mode 1 again (init()).
     *  Drive mode, interrupt bits, thresholds and environment data return to their defaults.
     */
    bool soft_reset();

    /** @brief Enable nINT for a source, keeping the drive mode.
     *  @param source SOURCE_DATA_READY and/or SOURCE_THRESHOLD (which also sets INT_DATARDY).
     */
    bool enable_interrupt(uint8_t source);

    /** @brief Disable nINT for a source, keeping the drive mode.
     *  @param source SOURCE_DATA_READY and/or SOURCE_THRESHOLD.
     */
    bool disable_interrupt(uint8_t source);

    /** @brief Read STATUS. Does not release nINT (reading ALG_RESULT_DATA does).
     *  @return STATUS byte (0 on a bus error); STATUS_DATA_READY marks a new sample.
     */
    uint8_t poll_interrupt();

    /** @brief Subscribe to nINT (falling edge). The callback receives the STATUS byte and should
     *  read the sample to release the pin. Without any pin (no @p intPin and none on the
     *  connection) nothing is delivered — poll with poll_interrupt().
     *  @param callback Called with STATUS when DATA_READY is set.
     *  @param intPin   Optional pin overriding connection.intPin().
     */
    void on_interrupt(void (*callback)(uint8_t status), InputPin* intPin = nullptr);

    /** @brief Unsubscribe from interrupt edges. */
    void off_interrupt();

private:
    static constexpr uint8_t INT_DATARDY = 0x08;
    static constexpr uint8_t INT_THRESH  = 0x04;

    void (*_callback)(uint8_t) = nullptr;
    InputPin* _intPinUsed = nullptr;
    static CCS811Full* _activeInstance;
    static void _edgeTrampoline();
    void _handleEdge();
    static uint8_t _int_bits(uint8_t source);
    bool _write_meas_mode();
    bool _read16(uint8_t reg, uint16_t& value);
    bool _version(uint8_t reg, CCS811Version& version);
};
