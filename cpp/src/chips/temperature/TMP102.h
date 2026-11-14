#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/RegisterConnection.h"

/** @brief TMP102 12-bit digital temperature sensor with ALERT output — minimal interface.
 *
 *  Two-wire temperature sensor (0.0625 °C per LSB, ±0.5 °C typical) read
 *  over an I²C/SMBus-compatible bus. Registers are 16-bit, big-endian,
 *  addressed through a persistent Register Pointer. Four selectable
 *  addresses (0x48–0x4B) via the ADD0 strap.
 *
 *  The constructor makes no bus access: the power-on default (continuous
 *  conversion at 4 Hz, comparator mode, 12-bit) already serves the primary
 *  use case. The 13-bit extended format is detected from bit 0 of the result.
 *
 *  @param connection RegisterConnection (I²C or SMBus) pointing at the device.
 */
class TMP102Minimal {
public:
    /** @brief Default 7-bit I²C address (ADD0 = GND). Valid range 0x48–0x4B. */
    static constexpr uint8_t I2C_ADDRESS = 0x48;

    explicit TMP102Minimal(RegisterConnection& connection);

    /** @brief Read the temperature.
     *
     *  Decodes the 12-bit (or, in extended mode, 13-bit) two's-complement
     *  TEMPERATURE value (0.0625 °C per LSB). Reads 0 °C until the first
     *  conversion after power-up completes (about 10 ms).
     *
     *  @return Temperature in °C.
     */
    float readTemperature();

protected:
    static constexpr uint8_t REG_TEMPERATURE = 0x00;
    static constexpr uint8_t REG_CONFIG      = 0x01;
    static constexpr uint8_t REG_T_LOW       = 0x02;
    static constexpr uint8_t REG_T_HIGH      = 0x03;

    RegisterConnection& _connection;

    uint16_t _readReg(uint8_t reg);
    void _writeReg(uint8_t reg, uint16_t value);

    static int16_t _decodeRaw(uint16_t raw16);
};

/** @brief TMP102 full interface — extends Minimal with conversion rate, extended
 *  mode, the T_LOW/T_HIGH limits, fault queue, polarity, thermostat mode,
 *  shutdown/one-shot, alert status, general-call reset and the ALERT interrupt API.
 *
 *  @param connection RegisterConnection (I²C or SMBus) pointing at the device.
 */
class TMP102Full : public TMP102Minimal {
public:
    explicit TMP102Full(RegisterConnection& connection);

    /** @brief Read the raw signed temperature count.
     *  @return Signed 12-bit (13-bit in extended mode) count; temperature = count × 0.0625 °C. */
    int16_t readRaw();

    /** @brief Read the 16-bit CONFIGURATION register (reset value 0x60A0).
     *  @return Register value. */
    uint16_t readConfig();

    /** @brief Write the 16-bit CONFIGURATION register; read-only bits are masked out.
     *  @param value Register value; only OS, F1:F0, POL, TM, SD, CR1:CR0 and EM are applied. */
    void writeConfig(uint16_t value);

    /** @brief Set the conversion rate.
     *  @param hz 0.25, 1, 4 or 8 Hz.
     *  @return true if written, false for an unsupported rate (no write). */
    bool setConversionRate(float hz);

    /** @brief Enable or disable the 13-bit extended mode (EM bit).
     *  @param on true for 13-bit (−256…+255.9375 °C), false for 12-bit. */
    void setExtendedMode(bool on);

    /** @brief Set the T_HIGH limit (reset 80 °C), rounded to 0.0625 °C.
     *  @param celsius Limit in °C, clamped to −55…+150 °C (12-bit mode: up to +127.9375 °C). */
    void setTHigh(float celsius);
    /** @brief Read the T_HIGH limit.
     *  @return Limit in °C. */
    float getTHigh();

    /** @brief Set the T_LOW limit (reset 75 °C), rounded to 0.0625 °C.
     *  @param celsius Limit in °C, clamped to −55…+150 °C (12-bit mode: up to +127.9375 °C). */
    void setTLow(float celsius);
    /** @brief Read the T_LOW limit.
     *  @return Limit in °C. */
    float getTLow();

    /** @brief Set the number of consecutive faults that trigger ALERT.
     *  @param count 1, 2, 4 or 6.
     *  @return true if written, false for an unsupported count (no write). */
    bool setFaultQueue(uint8_t count);

    /** @brief Set the ALERT polarity (POL bit).
     *  @param activeHigh true for active-high, false for active-low (default). */
    void setPolarity(bool activeHigh);

    /** @brief Select the thermostat mode (TM bit).
     *  @param interrupt true for interrupt mode, false for comparator mode (default). */
    void setThermostatMode(bool interrupt);

    /** @brief Shut the device down after the current conversion (SD = 1). */
    void shutdown();

    /** @brief Resume continuous conversion (SD = 0). */
    void wake();

    /** @brief Take a single conversion.
     *
     *  Puts the device in shutdown if needed, sets OS = 1, polls OS until the
     *  conversion is done (30 ms timeout) and restores the previous mode.
     *
     *  @param celsius Receives the temperature in °C.
     *  @return true on success, false if the conversion timed out. */
    bool oneShot(float& celsius);

    /** @brief Report whether ALERT is currently active (AL bit corrected for POL).
     *  @return true while the comparator state is alerting. */
    bool alertState();

    /** @brief Send the general-call reset (0x00 0x06).
     *
     *  Resets every general-call device on the bus to power-up values. Needs a
     *  connection that transmits the bytes to the general-call address. */
    void reset();

    /** @brief Subscribe to ALERT edges.
     *
     *  The edge direction follows POL (falling for active-low, rising for
     *  active-high). Requires an InputPin — either @p intPin or connection.intPin().
     *
     *  @param callback Called with the temperature in °C on every active edge.
     *  @param intPin   Optional InputPin for this call, overriding connection.intPin(). */
    void onInterrupt(void (*callback)(float temperatureC), InputPin* intPin = nullptr);

    /** @brief Unsubscribe and stop delivery. */
    void offInterrupt();

    /** @brief Read the alert state and clear an interrupt-mode alert.
     *
     *  Reads CONFIGURATION, then TEMPERATURE (any register read clears an
     *  interrupt-mode ALERT).
     *
     *  @return true while the comparator state is alerting. */
    bool pollInterrupt();

protected:
    // CONFIGURATION (0x01) bits.
    static constexpr uint16_t CFG_OS       = 0x8000;
    static constexpr uint16_t CFG_F_SHIFT  = 11;
    static constexpr uint16_t CFG_F_MASK   = 0x1800;
    static constexpr uint16_t CFG_POL      = 0x0400;
    static constexpr uint16_t CFG_TM       = 0x0200;
    static constexpr uint16_t CFG_SD       = 0x0100;
    static constexpr uint16_t CFG_CR_SHIFT = 6;
    static constexpr uint16_t CFG_CR_MASK  = 0x00C0;
    static constexpr uint16_t CFG_AL       = 0x0020;
    static constexpr uint16_t CFG_EM       = 0x0010;
    static constexpr uint16_t CFG_WRITABLE = 0x9FD0;
    static constexpr uint16_t CFG_RESERVED = 0x6000;  // R1:R0, read-only, always 11

    void _updateConfig(uint16_t clear, uint16_t set);
    void _setLimit(uint8_t reg, float celsius);
    float _getLimit(uint8_t reg);

    void (*_callback)(float temperatureC) = nullptr;
    InputPin* _intPinUsed = nullptr;

    static TMP102Full* _activeInstance;
    static void _edgeTrampoline();
    void _handleEdge();
};
