#pragma once
#include <stdint.h>
#include "../../connection/Connection.h"

/** @brief HDC1008 humidity and temperature sensor (Texas Instruments) — minimal interface.
 *
 * Each read() triggers one sequence measurement (pointer 0x00, temperature then humidity),
 * polls the NACKed read header until the chip ACKs (timeout 50 ms) and converts both words.
 * init() writes the configuration 0x1000 (14-bit, sequence mode, heater off).
 *
 * @param connection Connection (I²C) pointing at the device (0x40-0x43). The constructor does
 *        no bus access.
 */
class HDC1008Minimal {
public:
    HDC1008Minimal(Connection& connection);

    /** @brief Write the default configuration 0x1000 (14-bit T and RH, sequence mode, heater off). */
    void init();

    /** @brief Measure temperature and humidity.
     *  @param[out] temperature_c Temperature in degrees Celsius.
     *  @param[out] humidity_pct  Relative humidity in %RH.
     *  @return true on success; false if the chip did not answer within 50 ms.
     */
    bool read(float& temperature_c, float& humidity_pct);

protected:
    static constexpr uint8_t REG_TEMPERATURE = 0x00;
    static constexpr uint8_t REG_HUMIDITY    = 0x01;
    static constexpr uint8_t REG_CONFIG      = 0x02;
    static constexpr uint8_t REG_SERIAL1     = 0xFB;
    static constexpr uint16_t CFG_RST   = 0x8000;
    static constexpr uint16_t CFG_HEAT  = 0x2000;
    static constexpr uint16_t CFG_MODE  = 0x1000;
    static constexpr uint16_t CFG_BTST  = 0x0800;
    static constexpr uint16_t CFG_TRES  = 0x0400;
    static constexpr uint16_t CFG_HRES  = 0x0300;

    Connection& _connection;
    uint16_t _config = 0x1000;

    void _write_config();
    bool _trigger_read(uint8_t pointer, uint8_t* buf, uint8_t n);
    void _set_mode(bool sequence);
    bool _measure_pair(uint16_t& raw_t, uint16_t& raw_h);
    static float _to_temperature(uint16_t raw);
    static float _to_humidity(uint16_t raw);
};

/** @brief HDC1008 full interface — single values, resolution, heater, battery, serial ID, DRDYn.
 *
 * Extends HDC1008Minimal.
 *
 * @param connection Connection (I²C) pointing at the device (0x40-0x43); an optional intPin()
 *        on DRDYn is used by on_interrupt().
 */
class HDC1008Full : public HDC1008Minimal {
public:
    HDC1008Full(Connection& connection);

    /** @brief Measure temperature only (single mode); °C, NAN on failure. */
    float read_temperature();

    /** @brief Measure humidity only (single mode); %RH, NAN on failure. */
    float read_humidity();

    /** @brief Measure and return the raw 16-bit words (bits 1:0 zero). @return true on success. */
    bool read_raw(uint16_t& temperature_raw, uint16_t& humidity_raw);

    /** @brief Select the resolutions.
     *  @param temperature_bits 14 or 11.
     *  @param humidity_bits    14, 11 or 8.
     *  @return false (nothing written) for other values.
     */
    bool set_resolution(uint8_t temperature_bits, uint8_t humidity_bits);

    /** @brief Enable or disable the heater. */
    void set_heater(bool on);

    /** @brief True if the heater bit is set in the configuration register. */
    bool is_heater_on();

    /** @brief True if VDD is below 2.8 V (BTST). */
    bool battery_low();

    /** @brief Read the 16-bit configuration register. */
    uint16_t read_config();

    /** @brief Set RST, wait 15 ms, then rewrite the stored configuration. */
    void software_reset();

    /** @brief Read the 40-bit serial number from registers 0xFB-0xFD. */
    uint64_t serial_id();

    /** @brief Typical conversion time in seconds for the current resolutions (T + RH). */
    float conversion_time();

    /** @brief True when a triggered measurement is ready (read header ACKed; the bytes are cached for the next callback). */
    bool poll_interrupt();

    /** @brief Subscribe to DRDYn falling edges; the callback receives the converted result.
     *  @param callback Called with (temperature_c, humidity_pct) — or (temperature_c, NAN) after a single-mode trigger.
     *  @param intPin Optional pin overriding connection.intPin(); without any pin nothing is delivered (use poll_interrupt()). */
    void on_interrupt(void (*callback)(float temperature_c, float humidity_pct), InputPin* intPin = nullptr);

    /** @brief Unsubscribe from DRDYn edges. */
    void off_interrupt();

private:
    void (*_callback)(float, float) = nullptr;
    InputPin* _intPinUsed = nullptr;
    uint8_t _pending[4] = {0};
    uint8_t _pendingLen = 0;
    static HDC1008Full* _activeInstance;
    static void _edgeTrampoline();
    void _handleEdge();
};
