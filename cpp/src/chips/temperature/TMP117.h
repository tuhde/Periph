#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/Connection.h"

/** @brief TMP117 ±0.1°C high-accuracy, low-power digital temperature sensor — minimal interface.
 *
 *  NIST-traceable 16-bit temperature sensor (0.0078125 °C per LSB) read
 *  over an I²C/SMBus-compatible bus. Registers are 16-bit, big-endian,
 *  addressed through a non-incrementing Register Pointer. Four selectable
 *  addresses (0x48–0x4B) via the 4-level ADD0 strap.
 *
 *  The constructor checks DEVICE_ID bits 11:0 (0x117) and calls abort() on
 *  a mismatch (exceptions are disabled on every C++ target in this repo),
 *  so construct it only after the bus is started. No register is written:
 *  the POR/EEPROM default (continuous conversion, 8-conversion averaging,
 *  1 s cycle, Alert mode) already serves the primary use case.
 *
 *  @param connection Configured I²C connection pointing at the device.
 */
class TMP117Minimal {
public:
    /** @brief Default 7-bit I²C address (ADD0 = GND). Valid range 0x48–0x4B. */
    static constexpr uint8_t I2C_ADDRESS = 0x48;
    /** @brief Expected DEVICE_ID bits 11:0 (bits 15:12 are the silicon revision). */
    static constexpr uint16_t DEVICE_ID = 0x117;

    explicit TMP117Minimal(Connection& connection);

    /** @brief Read the temperature.
     *
     *  Decodes TEMP_RESULT's 16-bit two's-complement value (0.0078125 °C
     *  per LSB). Returns −256.0 until the first conversion after power-up
     *  completes.
     *
     *  @return Temperature in °C.
     */
    float readTemperature();

protected:
    static constexpr uint8_t REG_TEMP_RESULT = 0x00;
    static constexpr uint8_t REG_CONFIG      = 0x01;
    static constexpr uint8_t REG_THIGH       = 0x02;
    static constexpr uint8_t REG_TLOW        = 0x03;
    static constexpr uint8_t REG_EEPROM_UL   = 0x04;
    static constexpr uint8_t REG_EEPROM1     = 0x05;
    static constexpr uint8_t REG_EEPROM2     = 0x06;
    static constexpr uint8_t REG_TEMP_OFFSET = 0x07;
    static constexpr uint8_t REG_EEPROM3     = 0x08;
    static constexpr uint8_t REG_DEVICE_ID   = 0x0F;

    Connection& _connection;

    uint16_t _readReg(uint8_t reg);
    void _writeReg(uint8_t reg, uint16_t value);

    static float _decodeTemperature(uint16_t raw);
    static uint16_t _encodeTemperature(float celsius);
};

/** @brief TMP117 full interface — extends Minimal with conversion mode,
 *  averaging and cycle-time control, one-shot triggering, both temperature
 *  limits, the calibration offset, soft reset, EEPROM persistence and
 *  scratch storage, and the Level-2 Alert/interrupt API.
 *
 *  @param connection Configured I²C connection pointing at the device.
 */
class TMP117Full : public TMP117Minimal {
public:
    /** @brief Result > THIGH_LIMIT (HIGH_Alert). */
    static constexpr uint8_t SOURCE_HIGH = 0x01;
    /** @brief Result < TLOW_LIMIT (LOW_Alert; Alert mode only). */
    static constexpr uint8_t SOURCE_LOW  = 0x02;

    /** @brief Conversion mode (MOD[1:0]). */
    enum class Mode : uint8_t {
        Continuous = 0,  ///< Continuous conversion (POR default)
        Shutdown   = 1,  ///< No conversions; TEMP_RESULT holds its last value
        OneShot    = 3,  ///< One conversion, then Shutdown
    };
    /** @brief ALERT behavior (T/nA). */
    enum class AlertMode : uint8_t {
        Alert = 0,  ///< Window alert: HIGH_Alert and LOW_Alert (POR default)
        Therm = 1,  ///< Latching thermostat; TLOW_LIMIT is the reset threshold
    };
    /** @brief ALERT pin polarity (POL). */
    enum class AlertPolarity : uint8_t {
        ActiveLow  = 0,  ///< Needs an external pull-up (POR default)
        ActiveHigh = 1,
    };
    /** @brief ALERT pin function (DR/Alert). */
    enum class AlertPinFunction : uint8_t {
        Alert     = 0,  ///< Reflects the alert/Therm status (POR default)
        DataReady = 1,  ///< Reflects Data_Ready
    };

    /** @brief Decoded conversion configuration, as returned by getConfig(). */
    struct Config {
        Mode mode;           ///< Conversion mode
        uint8_t averaging;   ///< Conversions averaged per result: 0, 8, 32 or 64
        float cycleSeconds;  ///< CONV[2:0] cycle time in s (no-averaging column)
    };

    explicit TMP117Full(Connection& connection);

    /** @brief Set conversion mode, averaging and cycle time.
     *
     *  The cycle time is matched to the nearest CONV[2:0] step from the
     *  no-averaging column (15.5 ms, 125 ms, 250 ms, 500 ms, 1 s, 4 s, 8 s,
     *  16 s); at higher averaging the hardware lengthens short cycles
     *  automatically. The Alert configuration bits are preserved.
     *
     *  @param mode         Conversion mode.
     *  @param averaging    Conversions averaged per result: 0, 8, 32 or 64.
     *  @param cycleSeconds Desired conversion cycle time in s.
     *  @return true if written, false for an unsupported averaging (no write). */
    bool configure(Mode mode = Mode::Continuous, uint8_t averaging = 8, float cycleSeconds = 1.0f);

    /** @brief Read the conversion mode, averaging and cycle time.
     *  @return Decoded configuration; cycleSeconds is the no-averaging CONV step. */
    Config getConfig();

    /** @brief Report whether the sensor is in Shutdown mode.
     *  @return true if MOD[1:0] is Shutdown. */
    bool isShutdown();

    /** @brief Start a single conversion (MOD[1:0] = One-Shot); the sensor
     *  returns to Shutdown once the conversion (including averaging) completes. */
    void triggerOneShot();

    /** @brief Report whether a fresh conversion result is available.
     *  Reading this flag clears it (as does reading TEMP_RESULT).
     *  @return true if Data_Ready is set. */
    bool isDataReady();

    /** @brief Read THIGH_LIMIT.
     *  @return High limit in °C. */
    float getHighLimit();
    /** @brief Write THIGH_LIMIT, rounded to the nearest 0.0078125 °C.
     *  @param celsius High limit in °C (−256.0 to 255.9921875, clamped). */
    void setHighLimit(float celsius);

    /** @brief Read TLOW_LIMIT.
     *  @return Low limit in °C. */
    float getLowLimit();
    /** @brief Write TLOW_LIMIT, rounded to the nearest 0.0078125 °C
     *  (in Therm mode this is HIGH_Alert's reset threshold).
     *  @param celsius Low limit in °C (−256.0 to 255.9921875, clamped). */
    void setLowLimit(float celsius);

    /** @brief Read TEMP_OFFSET.
     *  @return Calibration offset in °C. */
    float getTemperatureOffset();
    /** @brief Write TEMP_OFFSET, added to every result after linearization.
     *  @param celsius Calibration offset in °C (−256.0 to 255.9921875, clamped). */
    void setTemperatureOffset(float celsius);

    /** @brief Software reset (Soft_Reset = 1), then wait the 2 ms reset time.
     *  Reloads CONFIGURATION, THIGH_LIMIT, TLOW_LIMIT and TEMP_OFFSET from EEPROM. */
    void reset();

    /** @brief Unlock the EEPROM (EUN = 1).
     *
     *  While unlocked, writes to CONFIGURATION, THIGH_LIMIT, TLOW_LIMIT,
     *  TEMP_OFFSET and EEPROM2 also program the EEPROM as the new power-on
     *  default. Poll isEepromBusy() after each such write. */
    void unlockEeprom();

    /** @brief Lock the EEPROM (EUN = 0); register writes become volatile only. */
    void lockEeprom();

    /** @brief Report whether an EEPROM programming operation is in progress.
     *  @return true if EEPROM_Busy is set. */
    bool isEepromBusy();

    /** @brief Read a general-purpose EEPROM scratch register.
     *  @param slot  1 (EEPROM1), 2 (EEPROM2) or 3 (EEPROM3); slots 1 and 3
     *               hold factory NIST-traceability data.
     *  @param value Receives the 16-bit register value.
     *  @return true if read, false for an invalid slot. */
    bool readEepromScratch(uint8_t slot, uint16_t& value);

    /** @brief Write the general-purpose EEPROM2 scratch register.
     *
     *  Only slot 2 is writable — EEPROM1/EEPROM3 hold factory
     *  NIST-traceability data. Persists across power cycles only while the
     *  EEPROM is unlocked.
     *
     *  @param slot  Must be 2.
     *  @param value 16-bit value.
     *  @return true if written, false for any slot other than 2 (no write). */
    bool writeEepromScratch(uint8_t slot, uint16_t value);

    /** @brief Configure the ALERT output's mode, polarity and pin function together.
     *  @param mode        Window alert or latching Therm.
     *  @param polarity    Active-low (POR default) or active-high.
     *  @param pinFunction Alert/Therm status or Data-Ready. */
    void configureAlert(AlertMode mode = AlertMode::Alert,
                        AlertPolarity polarity = AlertPolarity::ActiveLow,
                        AlertPinFunction pinFunction = AlertPinFunction::Alert);

    /** @brief Subscribe to ALERT edges.
     *
     *  The edge direction follows the configured POL (falling for
     *  active-low, rising for active-high); call configureAlert() first.
     *  Requires an InputPin — either @p intPin or connection.intPin().
     *
     *  @param callback Called with the pollInterrupt() mask on every edge.
     *  @param intPin   Optional InputPin for this call, overriding connection.intPin(). */
    void onInterrupt(void (*callback)(uint8_t status), InputPin* intPin = nullptr);

    /** @brief Unsubscribe and stop delivery. */
    void offInterrupt();

    /** @brief Read CONFIGURATION's HIGH_Alert / LOW_Alert flags.
     *
     *  In Alert mode this read also clears both flags (a hardware side
     *  effect). In Therm mode HIGH_Alert clears only once the temperature
     *  drops below TLOW_LIMIT.
     *
     *  @return Mask of SOURCE_HIGH / SOURCE_LOW. */
    uint8_t pollInterrupt();

protected:
    // CONFIGURATION (0x01) bits.
    static constexpr uint16_t CFG_HIGH_ALERT  = 0x8000;
    static constexpr uint16_t CFG_LOW_ALERT   = 0x4000;
    static constexpr uint16_t CFG_DATA_READY  = 0x2000;
    static constexpr uint16_t CFG_MOD_SHIFT   = 10;
    static constexpr uint16_t CFG_MOD_MASK    = 0x0C00;
    static constexpr uint16_t CFG_CONV_SHIFT  = 7;
    static constexpr uint16_t CFG_CONV_MASK   = 0x0380;
    static constexpr uint16_t CFG_AVG_SHIFT   = 5;
    static constexpr uint16_t CFG_AVG_MASK    = 0x0060;
    static constexpr uint16_t CFG_TNA         = 0x0010;
    static constexpr uint16_t CFG_POL         = 0x0008;
    static constexpr uint16_t CFG_DR_ALERT    = 0x0004;
    static constexpr uint16_t CFG_SOFT_RESET  = 0x0002;
    // Writable bits: MOD/CONV/AVG/T-nA/POL/DR-Alert. Soft_Reset is set only on purpose.
    static constexpr uint16_t CFG_WRITE_MASK  = 0x0FFC;

    // EEPROM_UL (0x04) bits.
    static constexpr uint16_t EUN         = 0x8000;
    static constexpr uint16_t EEPROM_BUSY = 0x4000;

    uint16_t _readConfig();
    void _writeConfig(uint16_t value);

    void (*_callback)(uint8_t status) = nullptr;
    InputPin* _intPinUsed = nullptr;

    static TMP117Full* _activeInstance;
    static void _edgeTrampoline();
    void _handleEdge();
};
