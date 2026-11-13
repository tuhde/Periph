#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/RegisterConnection.h"

/** @brief MAX44009 ultra-low-power ambient light sensor with ADC (Maxim / Analog Devices) — minimal interface.
 *
 *  An on-chip photodiode with automatic ranging reports the illuminance as a 12-bit floating-point
 *  value (4-bit exponent, 8-bit mantissa): `lux = 2^E · M · 0.045`, from 0.045 lx to 188 006.4 lx.
 *  The I²C address is 0x4A (A0 = GND) or 0x4B (A0 = VCC).
 *
 *  The constructor runs the initialization sequence (interrupt off, CONFIG 0x03, thresholds fully
 *  open, timer 0xFF, INT_STATUS read once) and waits 900 ms (one 800 ms cycle plus the 100 ms
 *  integration time) so the first lux() returns a real measurement. A RegisterConnection read ends
 *  with a STOP, so the lux registers are read as high, low, high and repeated once if the high
 *  byte changed in between.
 *
 *  @param connection RegisterConnection (I²C, 1-byte register address) pointing at the device.
 */
class MAX44009Minimal {
public:
    /** @brief 7-bit I²C address with A0 = GND. */
    static constexpr uint8_t I2C_ADDRESS = 0x4A;
    /** @brief 7-bit I²C address with A0 = VCC. */
    static constexpr uint8_t I2C_ADDRESS_A0_HIGH = 0x4B;
    /** @brief Wait after the initialization writes, in ms (tests may set it to 0). */
    static unsigned initWaitMs;

    explicit MAX44009Minimal(RegisterConnection& connection);

    /** @brief Read the ambient illuminance.
     *  @return Illuminance in lux (`2^E · M · 0.045`); the maximum 188006.4 lx on overrange (E = 15). */
    float lux();

protected:
    static constexpr uint8_t REG_INT_STATUS = 0x00;
    static constexpr uint8_t REG_INT_ENABLE = 0x01;
    static constexpr uint8_t REG_CONFIG     = 0x02;
    static constexpr uint8_t REG_LUX_HIGH   = 0x03;
    static constexpr uint8_t REG_LUX_LOW    = 0x04;
    static constexpr uint8_t REG_UPPER      = 0x05;
    static constexpr uint8_t REG_LOWER      = 0x06;
    static constexpr uint8_t REG_TIMER      = 0x07;
    static constexpr uint8_t CFG_CONT   = 0x80;
    static constexpr uint8_t CFG_MANUAL = 0x40;
    static constexpr uint8_t CFG_CDR    = 0x08;
    static constexpr uint8_t CFG_TIM_MASK = 0x07;

    RegisterConnection& _connection;
    uint8_t _lastExponent = 0;

    static void _delayMs(unsigned ms);
    uint8_t _readReg(uint8_t reg);
    void _writeReg(uint8_t reg, uint8_t value);
    /** Read LUX_HIGH / LUX_LOW consistently; returns the exponent and mantissa. */
    void _readLuxRegs(uint8_t& exponent, uint8_t& mantissa);
    static float _luxFrom(uint8_t exponent, uint8_t mantissa);
};

/** @brief MAX44009 full interface — extends Minimal with raw readings, measurement mode and range
 *  configuration, interrupt thresholds and delay, and interrupt delivery.
 *
 *  @param connection RegisterConnection (I²C, 1-byte register address) pointing at the device.
 */
class MAX44009Full : public MAX44009Minimal {
public:
    /** @brief Status bit 0: light level outside the threshold window for longer than the delay. */
    static constexpr uint8_t SOURCE_THRESHOLD = 0x01;

    /** @brief Decoded CONFIG register. */
    struct Config {
        bool continuous;
        bool manual;
        bool divideBy8;
        float integrationMs;
    };

    explicit MAX44009Full(RegisterConnection& connection);

    /** @brief Read the lux registers as one consistent sample.
     *  @param exponent Output: exponent field 0–15.
     *  @param mantissa Output: 8-bit mantissa.
     *  @return true if the exponent field was 15 (overrange). */
    bool readRaw(uint8_t& exponent, uint8_t& mantissa);

    /** @brief Whether the last reading was overrange (E = 15). */
    bool isOverrange() const { return _lastExponent >= 15; }

    /** @brief Lux per mantissa LSB for the last reading, `2^E · 0.045`. */
    float resolution() const;

    /** @brief Select continuous or low-power measurement mode.
     *  @param continuous true: a new measurement as soon as one finishes; false: one per 800 ms. */
    void setContinuous(bool continuous);

    /** @brief Select automatic mode (the chip chooses current divider and integration time). */
    void setAutomatic();

    /** @brief Select manual mode.
     *  @param integrationMs Integration time; rounded to the nearest of 800, 400, 200, 100, 50, 25, 12.5, 6.25 ms.
     *  @param divideBy8 true to divide the photodiode current by 8 (high brightness). */
    void setManual(float integrationMs, bool divideBy8 = false);

    /** @brief Read the CONFIG register. */
    Config getConfig();

    /** @brief Set the interrupt threshold window.
     *  @param lowLux  Lower threshold in lux.
     *  @param highLux Upper threshold in lux.
     *  @return false (nothing written) if lowLux > highLux. */
    bool setThresholds(float lowLux, float highLux);

    /** @brief Read the interrupt threshold window (the upper one carries the implied low nibble 1111).
     *  @param lowLux  Output: lower threshold in lux.
     *  @param highLux Output: upper threshold in lux. */
    void getThresholds(float& lowLux, float& highLux);

    /** @brief Set how long the light must stay outside the window before the interrupt.
     *  @param seconds Delay in seconds, 0–25.5 (0.1 s steps). */
    void setThresholdDelay(float seconds);

    /** @brief Read the threshold delay in seconds. */
    float getThresholdDelay();

    /** @brief Enable the interrupt (INT_ENABLE = 1). */
    void enableInterrupt();

    /** @brief Disable the interrupt (INT_ENABLE = 0; also clears a pending status). */
    void disableInterrupt();

    /** @brief Read INT_STATUS (clearing it).
     *  @return Status; bit 0 (SOURCE_THRESHOLD) set if the light was outside the window for longer than the delay. */
    uint8_t pollInterrupt();

    /** @brief Subscribe to INT assertions.
     *
     *  Wires @p intPin->onEdge() (or connection.intPin() if @p intPin is nullptr) on the falling
     *  edge. Without any InputPin, call service() periodically instead.
     *  @param callback void(*)(uint8_t status), called after INT_STATUS has been read and cleared.
     *  @param intPin Optional InputPin overriding connection.intPin(). */
    void onInterrupt(void (*callback)(uint8_t status), InputPin* intPin = nullptr);

    /** @brief Unsubscribe and stop delivery. */
    void offInterrupt();

    /** @brief Poll once and dispatch the callback (for use without an INT pin; also the INT edge handler). */
    void service();

protected:
    void (*_callback)(uint8_t status) = nullptr;
    InputPin* _intPinUsed = nullptr;

    void _updateConfig(uint8_t setBits, uint8_t clearBits);
    static uint8_t _encodeThreshold(float lux);
    static float _decodeThreshold(uint8_t code, bool upper);

    static MAX44009Full* _activeInstance;
    static void _edgeTrampoline();
};
