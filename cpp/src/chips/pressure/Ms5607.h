#pragma once
#include <stdint.h>
#include "../../connection/Connection.h"

/** @brief MS5607 barometric pressure sensor (TE Connectivity) — minimal interface.
 *
 * init() resets the chip, reads the eight PROM words and verifies the CRC-4. Each read() converts
 * the temperature (D2) and then the pressure (D1) at oversampling ratio 4096 (waiting 10 ms each)
 * and applies the first- and second-order compensation. Pressure is in pascals (1 Pa = 0.01 mbar,
 * the chip's native unit).
 *
 * The device is command driven (no register pointer), so a plain Connection is used.
 *
 * @param connection Connection (I²C) pointing at the device (address 0x77, or 0x76 with CSB high).
 *        The constructor does no bus access.
 */
class MS5607Minimal {
public:
    MS5607Minimal(Connection& connection);

    /** @brief Reset (0x1E), wait 3 ms, read the PROM and verify its CRC-4.
     *  @return true on success; false on a bus error or CRC mismatch.
     */
    bool init();

    /** @brief Measure temperature and pressure (OSR 4096, second-order compensation).
     *  @param[out] temperature_c Temperature in degrees Celsius.
     *  @param[out] pressure_pa   Pressure in pascals.
     *  @return true on success; false on a bus error or an ADC result of 0.
     */
    bool read(float& temperature_c, float& pressure_pa);

protected:
    static constexpr uint8_t CMD_RESET      = 0x1E;
    static constexpr uint8_t CMD_CONVERT_D1 = 0x40;
    static constexpr uint8_t CMD_CONVERT_D2 = 0x50;
    static constexpr uint8_t CMD_ADC_READ   = 0x00;
    static constexpr uint8_t CMD_PROM_READ  = 0xA0;

    Connection& _connection;
    uint8_t _osr_index = 4;
    uint16_t _prom[8] = {0};

    bool _load_prom();
    bool _read_prom_words(uint16_t out[8]);
    bool _convert(uint8_t kind, uint32_t& value);
    bool _read_adc(uint32_t& value);
    void _compensate(uint32_t d1, uint32_t d2, bool second_order, float& t, float& p) const;
    static uint8_t _crc4(const uint16_t words[8]);
    static uint8_t _wait_ms(uint8_t osr_index);
};

/** @brief MS5607 full interface — single values, raw codes, oversampling, non-blocking conversion, PROM access, altitude.
 *
 * Extends MS5607Minimal with single values, raw codes, the oversampling ratio, explicit conversion
 * start / ADC read, pure compensation, reset, PROM and coefficient access and the barometric altitude.
 *
 * @param connection Connection (I²C) pointing at the device (address 0x77, or 0x76 with CSB high).
 */
class MS5607Full : public MS5607Minimal {
public:
    static constexpr uint8_t PRESSURE    = 0;
    static constexpr uint8_t TEMPERATURE = 1;

    MS5607Full(Connection& connection);

    /** @brief Convert D2 and D1 at the current OSR and return the raw 24-bit codes.
     *  @return true on success.
     */
    bool read_raw(uint32_t& d1, uint32_t& d2);

    /** @brief Measure and return the compensated pressure in Pa; NAN on failure. */
    float read_pressure();

    /** @brief Convert D2 only and return the temperature in °C (with the low-temperature correction); NAN on failure. */
    float read_temperature();

    /** @brief Set the oversampling ratio (256, 512, 1024, 2048 or 4096) used for both conversions.
     *  @return false (and no change) for any other value.
     */
    bool set_oversampling(uint16_t osr);

    /** @brief Current oversampling ratio (256 ... 4096). */
    uint16_t get_oversampling() const;

    /** @brief Maximum conversion time in seconds for an OSR (0 = the current one); 0 for an invalid OSR. */
    float conversion_time(uint16_t osr = 0) const;

    /** @brief Write a conversion command without waiting (non-blocking use).
     *  @param kind PRESSURE (D1) or TEMPERATURE (D2).
     *  @param osr  Oversampling ratio; 0 for the current one.
     *  @return true on success; false for an invalid OSR or a bus error.
     */
    bool start_conversion(uint8_t kind, uint16_t osr = 0);

    /** @brief ADC read command plus 3 bytes; only valid after the conversion time.
     *  @return true on success; false on a bus error or a result of 0 (read too early or repeated).
     */
    bool read_adc(uint32_t& value);

    /** @brief Pure computation with the stored coefficients (no bus access).
     *  @param second_order false for the first-order result only.
     */
    void compensate(uint32_t d1, uint32_t d2, float& temperature_c, float& pressure_pa, bool second_order = true) const;

    /** @brief Reset (0x1E), wait 3 ms and reload and verify the PROM. */
    bool reset();

    /** @brief Read the eight PROM words from the chip. */
    bool read_prom(uint16_t words[8]);

    /** @brief The calibration coefficients C1 ... C6 (read at init). */
    void coefficients(uint16_t c[6]) const;

    /** @brief Verify the CRC-4 of the PROM words read at init (no bus access). */
    bool prom_crc_ok() const;

    /** @brief Serial code (PROM word 7, bits 15:4). */
    uint16_t serial_code() const;

    /** @brief Altitude in metres from one pressure reading (international barometric formula); NAN on failure. */
    float altitude(float sea_level_pa = 101325.0f);
};
