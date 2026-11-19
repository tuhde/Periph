#pragma once
#include <stdint.h>
#include "../../connection/RegisterConnection.h"

/** @brief MPL115A2 miniature I²C digital barometer (50–115 kPa) — minimal interface.
 *
 *  Reads the compensated absolute pressure in pascals and an approximate
 *  temperature in degrees Celsius. The four per-device compensation
 *  coefficients (a0, b1, b2, c12) are read once at construction and cached;
 *  each reading writes CONVERT, waits 5 ms and reads the ADC registers. The
 *  chip has a fixed I²C address (0x60) and no configuration registers.
 *
 *  @param connection RegisterConnection (I²C, address 0x60) pointing at the device.
 */
class MPL115A2Minimal {
public:
    explicit MPL115A2Minimal(RegisterConnection& connection);

    /** @brief Run one conversion and return the compensated pressure.
     *  @return Pressure in pascals.
     */
    float read_pressure();

    /** @brief Run one conversion and return an approximate temperature.
     *
     *  The datasheet gives no temperature formula; this uses the common
     *  approximation 25 + (Tadc - 498) / -5.35 and is accurate to only a few
     *  degrees. Use it for trends, not as a measurement.
     *
     *  @return Approximate temperature in degrees Celsius.
     */
    float read_temperature();

protected:
    static constexpr uint8_t REG_PADC    = 0x00;
    static constexpr uint8_t REG_COEF    = 0x04;
    static constexpr uint8_t REG_CONVERT = 0x12;

    static constexpr unsigned long CONVERSION_MS = 5;

    RegisterConnection& _connection;

    float _a0  = 0.0f;
    float _b1  = 0.0f;
    float _b2  = 0.0f;
    float _c12 = 0.0f;

    void  _load_coefficients();
    void  _convert(uint16_t& padc, uint16_t& tadc);
    float _compensate(uint16_t padc, uint16_t tadc) const;
    static float _temperature_c(uint16_t tadc);
};

/** @brief MPL115A2 full interface — extends MPL115A2Minimal.
 *
 *  Adds a combined reading, raw ADC counts, coefficient access, the pure
 *  compensation function, hPa and altitude helpers. The chip has no
 *  configuration registers; power state is hardware (SHDN / RST pins).
 *
 *  @param connection RegisterConnection (I²C, address 0x60) pointing at the device.
 */
class MPL115A2Full : public MPL115A2Minimal {
public:
    explicit MPL115A2Full(RegisterConnection& connection);

    /** @brief Run one conversion and return both values.
     *  @param pressure_pa   Receives the pressure in pascals.
     *  @param temperature_c Receives the approximate temperature in degrees Celsius.
     */
    void  read(float& pressure_pa, float& temperature_c);

    /** @brief Run one conversion and return the raw 10-bit ADC counts.
     *  @param padc Receives the pressure count (0–1023).
     *  @param tadc Receives the temperature count (0–1023).
     */
    void  read_raw(uint16_t& padc, uint16_t& tadc);

    /** @brief Return the cached compensation coefficients.
     *  @param a0  Receives a0.
     *  @param b1  Receives b1.
     *  @param b2  Receives b2.
     *  @param c12 Receives c12.
     */
    void  coefficients(float& a0, float& b1, float& b2, float& c12) const;

    /** @brief Re-read the coefficient block (e.g. after a power cycle). */
    void  reload_coefficients();

    /** @brief Compute pressure from raw counts and the cached coefficients (no bus access).
     *  @param padc 10-bit pressure ADC count.
     *  @param tadc 10-bit temperature ADC count.
     *  @return Pressure in pascals.
     */
    float compensate(uint16_t padc, uint16_t tadc) const;

    /** @brief Run one conversion and return the pressure in hectopascals.
     *  @return Pressure in hPa.
     */
    float pressure_hpa();

    /** @brief Compute the altitude from a fresh pressure reading (barometric formula, informative).
     *  @param sea_level_pa Reference sea-level pressure in Pa (default 101325).
     *  @return Altitude in metres.
     */
    float altitude(float sea_level_pa = 101325.0f);
};
