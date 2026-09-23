#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/Connection.h"

/** @brief MCP9808 ±0.5°C maximum accuracy digital temperature sensor — minimal interface.
 *
 *  Band-gap temperature sensor with a delta-sigma ADC, read over I²C.
 *  Registers are 16-bit, big-endian, addressed through a non-incrementing
 *  Register Pointer. Eight selectable addresses (0x18–0x1F) via the
 *  A0/A1/A2 strap pins.
 *
 *  The constructor checks MANUFACTURER_ID (0x0054) and the DEVICE_ID byte
 *  (0x04) and calls abort() on a mismatch (exceptions are disabled on every
 *  C++ target in this repo), so construct it only after the bus is started.
 *  No register is written: the POR default (continuous conversion at
 *  0.0625 °C resolution, Alert output disabled) already serves the primary
 *  use case.
 *
 *  @param connection Configured I²C connection pointing at the device.
 */
class MCP9808Minimal {
public:
    /** @brief Default 7-bit I²C address (A0 = A1 = A2 = GND). Valid range 0x18–0x1F. */
    static constexpr uint8_t I2C_ADDRESS = 0x18;
    /** @brief Expected MANUFACTURER_ID register value. */
    static constexpr uint16_t MANUFACTURER_ID = 0x0054;
    /** @brief Expected DEVICE_ID (upper byte of DEVICE_ID_REV). */
    static constexpr uint8_t DEVICE_ID = 0x04;

    explicit MCP9808Minimal(Connection& connection);

    /** @brief Read the ambient temperature.
     *
     *  Masks off TA's three boundary-status bits and decodes the 13-bit
     *  two's-complement value (0.0625 °C per LSB).
     *
     *  @return Ambient temperature in °C.
     */
    float readTemperature();

protected:
    static constexpr uint8_t REG_CONFIG     = 0x01;
    static constexpr uint8_t REG_TUPPER     = 0x02;
    static constexpr uint8_t REG_TLOWER     = 0x03;
    static constexpr uint8_t REG_TCRIT      = 0x04;
    static constexpr uint8_t REG_TA         = 0x05;
    static constexpr uint8_t REG_MFR_ID     = 0x06;
    static constexpr uint8_t REG_DEVICE_ID  = 0x07;
    static constexpr uint8_t REG_RESOLUTION = 0x08;

    Connection& _connection;

    uint16_t _readReg(uint8_t reg);
    void _writeReg(uint8_t reg, uint16_t value);
};

/** @brief MCP9808 full interface — extends Minimal with resolution control,
 *  Shutdown mode, the TUPPER/TLOWER/TCRIT boundaries, hysteresis, the one-way
 *  register locks, and the Level-2 Alert/interrupt API.
 *
 *  @param connection Configured I²C connection pointing at the device.
 */
class MCP9808Full : public MCP9808Minimal {
public:
    /** @brief TA < TLOWER. */
    static constexpr uint8_t SOURCE_LOWER    = 0x01;
    /** @brief TA > TUPPER. */
    static constexpr uint8_t SOURCE_UPPER    = 0x02;
    /** @brief TA ≥ TCRIT. */
    static constexpr uint8_t SOURCE_CRITICAL = 0x04;

    /** @brief Which boundaries drive the Alert output (ALERT_SEL). */
    enum class AlertMode : uint8_t {
        All          = 0,  ///< TUPPER, TLOWER and TCRIT
        CriticalOnly = 1,  ///< TCRIT only
    };
    /** @brief Alert output behavior (ALERT_MOD). */
    enum class AlertOutput : uint8_t {
        Comparator = 0,  ///< Follows the boundary state
        Interrupt  = 1,  ///< Latches until clearInterrupt()
    };
    /** @brief Alert output polarity (ALERT_POL). */
    enum class AlertPolarity : uint8_t {
        ActiveLow  = 0,  ///< Needs an external pull-up (POR default)
        ActiveHigh = 1,
    };

    explicit MCP9808Full(Connection& connection);

    /** @brief Set the measurement resolution.
     *
     *  Finer steps take longer to convert: 0.5 °C = 30 ms, 0.25 °C = 65 ms,
     *  0.125 °C = 130 ms, 0.0625 °C = 250 ms (typical).
     *
     *  @param celsius One of 0.5, 0.25, 0.125, 0.0625.
     *  @return true if written, false for an unsupported step (no write).
     */
    bool setResolution(float celsius);

    /** @brief Read the measurement resolution.
     *  @return Resolution step in °C. */
    float getResolution();

    /** @brief Enter Shutdown (low-power) mode; TA holds its last value.
     *  No-op while either lock bit is set (the chip ignores SHDN=1 then). */
    void shutdown();

    /** @brief Leave Shutdown mode and resume continuous conversion. */
    void wake();

    /** @brief Report whether the sensor is in Shutdown mode.
     *  @return true if SHDN is set. */
    bool isShutdown();

    /** @brief Read the TUPPER boundary.
     *  @return Upper boundary in °C (0.25 °C steps). */
    float getUpperLimit();
    /** @brief Write the TUPPER boundary, rounded to the nearest 0.25 °C
     *  (ignored by the chip while WIN_LOCK is set).
     *  @param celsius Upper boundary in °C (−256.0 to 255.75, clamped). */
    void setUpperLimit(float celsius);

    /** @brief Read the TLOWER boundary.
     *  @return Lower boundary in °C (0.25 °C steps). */
    float getLowerLimit();
    /** @brief Write the TLOWER boundary, rounded to the nearest 0.25 °C
     *  (ignored by the chip while WIN_LOCK is set).
     *  @param celsius Lower boundary in °C (−256.0 to 255.75, clamped). */
    void setLowerLimit(float celsius);

    /** @brief Read the TCRIT boundary.
     *  @return Critical boundary in °C (0.25 °C steps). */
    float getCriticalLimit();
    /** @brief Write the TCRIT boundary, rounded to the nearest 0.25 °C
     *  (ignored by the chip while CRIT_LOCK is set).
     *  @param celsius Critical boundary in °C (−256.0 to 255.75, clamped). */
    void setCriticalLimit(float celsius);

    /** @brief Set the boundary hysteresis (applies to the cooling edge only;
     *  ignored by the chip while either lock bit is set).
     *  @param celsius One of 0, 1.5, 3.0, 6.0.
     *  @return true if written, false for an unsupported value (no write). */
    bool setHysteresis(float celsius);

    /** @brief Read the boundary hysteresis.
     *  @return Hysteresis in °C. */
    float getHysteresis();

    /** @brief Lock TCRIT (and ALERT_SEL/POL/MOD).
     *  Irreversible except by power-on reset. */
    void lockCriticalLimit();

    /** @brief Lock TUPPER/TLOWER (and ALERT_SEL/POL/MOD).
     *  Irreversible except by power-on reset. */
    void lockWindowLimits();

    /** @brief Report whether CRIT_LOCK is set.
     *  @return true if TCRIT is locked. */
    bool isCriticalLimitLocked();

    /** @brief Report whether WIN_LOCK is set.
     *  @return true if TUPPER/TLOWER are locked. */
    bool isWindowLimitsLocked();

    /** @brief Configure the Alert output's source, mode and polarity together.
     *  @param mode     Boundaries that drive the Alert output.
     *  @param output   Comparator or latching interrupt output.
     *  @param polarity Active-low (POR default) or active-high.
     *  @return true if written, false if either lock bit is set (no write). */
    bool configureAlert(AlertMode mode = AlertMode::All,
                        AlertOutput output = AlertOutput::Comparator,
                        AlertPolarity polarity = AlertPolarity::ActiveLow);

    /** @brief Enable the Alert output (ALERT_CNT = 1). */
    void enableAlert();

    /** @brief Disable the Alert output (ALERT_CNT = 0). */
    void disableAlert();

    /** @brief Report whether the Alert output is currently asserted.
     *  @return true if ALERT_STAT is set. */
    bool isAlertAsserted();

    /** @brief Clear an asserted interrupt-mode Alert output (INT_CLEAR = 1).
     *  Has no effect in comparator mode. */
    void clearInterrupt();

    /** @brief Subscribe to Alert edges.
     *
     *  The edge direction follows the configured ALERT_POL (falling for
     *  active-low, rising for active-high); call configureAlert() first.
     *  Requires an InputPin — either @p intPin or connection.intPin().
     *
     *  @param callback Called with the pollInterrupt() mask on every edge.
     *  @param intPin   Optional InputPin for this call, overriding connection.intPin(). */
    void onInterrupt(void (*callback)(uint8_t status), InputPin* intPin = nullptr);

    /** @brief Unsubscribe and stop delivery. */
    void offInterrupt();

    /** @brief Read TA's live boundary-status bits (nothing is cleared).
     *  @return Mask of SOURCE_LOWER / SOURCE_UPPER / SOURCE_CRITICAL. */
    uint8_t pollInterrupt();

protected:
    // CONFIG (0x01) bits.
    static constexpr uint16_t CFG_THYST_SHIFT = 9;
    static constexpr uint16_t CFG_THYST_MASK  = 0x0600;
    static constexpr uint16_t CFG_SHDN        = 0x0100;
    static constexpr uint16_t CFG_CRIT_LOCK   = 0x0080;
    static constexpr uint16_t CFG_WIN_LOCK    = 0x0040;
    static constexpr uint16_t CFG_INT_CLEAR   = 0x0020;
    static constexpr uint16_t CFG_ALERT_STAT  = 0x0010;
    static constexpr uint16_t CFG_ALERT_CNT   = 0x0008;
    static constexpr uint16_t CFG_ALERT_SEL   = 0x0004;
    static constexpr uint16_t CFG_ALERT_POL   = 0x0002;
    static constexpr uint16_t CFG_ALERT_MOD   = 0x0001;
    static constexpr uint16_t CFG_LOCKS       = 0x00C0;
    // Writable bits: all but the unimplemented 15:11, the read-only
    // ALERT_STAT, and the self-clearing INT_CLEAR (set only on purpose).
    static constexpr uint16_t CFG_WRITE_MASK  = 0x07CF;

    uint16_t _readConfig();
    void _writeConfig(uint16_t value);

    static float _decodeLimit(uint16_t raw);
    static uint16_t _encodeLimit(float celsius);

    void (*_callback)(uint8_t status) = nullptr;
    InputPin* _intPinUsed = nullptr;

    static MCP9808Full* _activeInstance;
    static void _edgeTrampoline();
    void _handleEdge();
};
