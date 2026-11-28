#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/RegisterConnection.h"

/** @brief TSL2561 two-channel ambient light sensor (TAOS / ams) — minimal interface.
 *
 *  Channel 0 responds to visible and infrared light, channel 1 mainly to infrared; the two 16-bit
 *  integrating ADC counts are combined with the datasheet's empirical formula into lux. The I²C
 *  address is 0x29 (ADDR SEL = GND), 0x39 (floating) or 0x49 (VDD).
 *
 *  Every register access starts with a command byte (`0x80 | addr` for a byte, `0xA0 | addr` for a
 *  little-endian word); the driver passes the command byte as the RegisterConnection register and
 *  reads both channels in one 4-byte block read (`0x9C`) so the pair belongs to one integration.
 *
 *  init() powers the chip up, verifies CONTROL, selects 1× gain / 402 ms and waits one integration
 *  (450 ms). Methods that read report failures through lastError() and return NAN / false.
 *
 *  @param connection RegisterConnection (I²C, 1-byte register address) pointing at the device.
 */
class TSL2561Minimal {
public:
    /** @brief 7-bit I²C address with ADDR SEL floating (default of most breakout boards). */
    static constexpr uint8_t I2C_ADDRESS = 0x39;
    /** @brief 7-bit I²C address with ADDR SEL = GND. */
    static constexpr uint8_t I2C_ADDRESS_GND = 0x29;
    /** @brief 7-bit I²C address with ADDR SEL = VDD. */
    static constexpr uint8_t I2C_ADDRESS_VDD = 0x49;

    /** @brief Error codes reported by lastError(). */
    static constexpr int ERR_NONE = 0;
    /** @brief A bus transfer was not acknowledged. */
    static constexpr int ERR_BUS = 1;
    /** @brief CONTROL did not read back as powered up during init(). */
    static constexpr int ERR_INIT = 2;
    /** @brief A channel count is at or above the full-scale limit of the integration time. */
    static constexpr int ERR_SATURATED = 3;
    /** @brief An argument was out of range. */
    static constexpr int ERR_ARG = 4;

    /** @brief Lux coefficient sets (T, FN and CL share one set). */
    enum Package : uint8_t { PACKAGE_T = 0, PACKAGE_FN = 1, PACKAGE_CL = 2, PACKAGE_CS = 3 };

    /** @brief Scale for every integration wait in percent (default 100; tests set 0 to skip sleeps). */
    static unsigned waitPercent;

    /** @brief Construct the driver; no bus traffic until init().
     *  @param connection RegisterConnection pointing at the device. */
    explicit TSL2561Minimal(RegisterConnection& connection) : _connection(connection) {}

    /** @brief Run the initialization sequence: CONTROL ← 0x03, read back, TIMING ← 0x02 (1×, 402 ms),
     *  wait one integration.
     *  @return true on success; false with lastError() = ERR_BUS or ERR_INIT. */
    bool init();

    /** @brief Read the illuminance.
     *  @return Lux (T/FN/CL coefficients unless changed); 0 for a channel ratio above 1.30; NAN with
     *  lastError() = ERR_SATURATED on a saturated channel or ERR_BUS on a bus error. */
    float readLux();

    /** @brief Error of the most recent operation (ERR_NONE on success). */
    int lastError() const { return _error; }

    /** @brief Convert raw counts into lux with the datasheet's empirical formula.
     *  @param ch0 Channel 0 count (visible + IR).
     *  @param ch1 Channel 1 count (IR).
     *  @param gain 1 or 16.
     *  @param integrationMs 14, 101 or 402 (any other value is treated as 402 ms).
     *  @param package Coefficient set.
     *  @return Lux; 0 for ch0 = 0 or a ratio above 1.30. */
    static float calculateLux(uint16_t ch0, uint16_t ch1, uint8_t gain = 1, uint16_t integrationMs = 402,
                              Package package = PACKAGE_T);

protected:
    static constexpr uint8_t CMD = 0x80;
    static constexpr uint8_t CMD_CLEAR = 0xC0;
    static constexpr uint8_t CMD_WORD = 0xA0;
    static constexpr uint8_t CMD_DATA_BLOCK = 0x9C;
    static constexpr uint8_t REG_CONTROL = 0x0;
    static constexpr uint8_t REG_TIMING = 0x1;
    static constexpr uint8_t REG_THRESH_LOW = 0x2;
    static constexpr uint8_t REG_THRESH_HIGH = 0x4;
    static constexpr uint8_t REG_INTERRUPT = 0x6;
    static constexpr uint8_t REG_ID = 0xA;
    static constexpr uint8_t POWER_ON = 0x03;
    static constexpr uint8_t INTEG_MANUAL = 0x03;

    RegisterConnection& _connection;
    uint8_t _gain = 1;
    uint8_t _integ = 2;
    Package _package = PACKAGE_T;
    int _error = ERR_NONE;

    static void _delayMs(unsigned ms);
    static void _wait(unsigned ms);
    bool _fail(int err) { _error = err; return false; }
    bool _readByte(uint8_t reg, uint8_t& value);
    bool _writeByte(uint8_t reg, uint8_t value);
    bool _readWord(uint8_t reg, uint16_t& value);
    bool _writeWord(uint8_t reg, uint16_t value);
    bool _writeTiming(bool manual = false);
    bool _readChannels(uint16_t& ch0, uint16_t& ch1);
    uint16_t _fullScale() const;
    uint16_t _integrationMs() const;
    unsigned _settleMs() const;
    float _luxFrom(uint16_t ch0, uint16_t ch1);
};

/** @brief TSL2561 full interface — extends Minimal with raw counts, gain and integration time, package
 *  coefficients, auto-ranging, power control, manual integration, the interrupt window and
 *  interrupt delivery.
 *
 *  @param connection RegisterConnection (I²C, 1-byte register address) pointing at the device.
 */
class TSL2561Full : public TSL2561Minimal {
public:
    /** @brief Construct the driver; call init() before use.
     *  @param connection RegisterConnection pointing at the device. */
    explicit TSL2561Full(RegisterConnection& connection) : TSL2561Minimal(connection) {}

    /** @brief Read both ADC channels in one block read.
     *  @param ch0 Receives the channel 0 count (visible + IR).
     *  @param ch1 Receives the channel 1 count (IR).
     *  @return false on a bus error. */
    bool readRaw(uint16_t& ch0, uint16_t& ch1);

    /** @brief Set the ADC gain (TIMING.GAIN); wait one integration before trusting the next reading.
     *  @param gain 1 or 16.
     *  @return false with ERR_ARG on other values, or on a bus error. */
    bool setGain(uint8_t gain);

    /** @brief Current ADC gain (1 or 16). */
    uint8_t getGain() const { return _gain; }

    /** @brief Set the integration time (TIMING.INTEG); wait one integration before trusting the next reading.
     *  @param ms 14 (13.7 ms), 101 or 402.
     *  @return false with ERR_ARG on other values, or on a bus error. */
    bool setIntegrationTime(uint16_t ms);

    /** @brief Current integration time in ms (14, 101 or 402; 0 in manual timing mode). */
    uint16_t getIntegrationTime() const { return _integrationMs(); }

    /** @brief Select the lux coefficient set.
     *  @param package PACKAGE_T, PACKAGE_FN, PACKAGE_CL or PACKAGE_CS. */
    void setPackage(Package package) { _package = package; }

    /** @brief Read lux and both channel counts from one sample.
     *  @param lux Receives the illuminance in lux.
     *  @param ch0 Receives the channel 0 count.
     *  @param ch1 Receives the channel 1 count.
     *  @return false on a bus error or a saturated channel (counts are still filled in). */
    bool readLuxRaw(float& lux, uint16_t& ch0, uint16_t& ch1);

    /** @brief Pick gain and integration time for the current light level and read lux.
     *
     *  Steps through 16×/402 ms, 1×/402 ms, 1×/101 ms and 1×/13.7 ms until neither channel
     *  saturates and channel 0 is above 100 counts, waiting for a fresh integration after every
     *  change; the chosen setting stays active.
     *  @return Lux, or NAN with ERR_SATURATED if a channel still saturates at 1×/13.7 ms. */
    float autoRange();

    /** @brief Power up (CONTROL ← 0x03); integration restarts. */
    bool powerUp();

    /** @brief Power down (CONTROL ← 0x00); the registers keep their values. */
    bool powerDown();

    /** @brief True if CONTROL reads 0x03 (false also on a bus error). */
    bool isPowered();

    /** @brief Start a host-timed integration (INTEG = 11, MANUAL = 1). readLux() then uses the 402 ms scale. */
    bool startManual();

    /** @brief Stop the host-timed integration (INTEG = 11, MANUAL = 0); read the counts next. */
    bool stopManual();

    /** @brief Set the channel-0 interrupt window (word writes of 0x2–0x3 and 0x4–0x5).
     *  @param low Low threshold in channel-0 counts.
     *  @param high High threshold in channel-0 counts. */
    bool setThresholds(uint16_t low, uint16_t high);

    /** @brief Read the channel-0 interrupt window.
     *  @param low Receives the low threshold.
     *  @param high Receives the high threshold. */
    bool getThresholds(uint16_t& low, uint16_t& high);

    /** @brief Configure the INTERRUPT register.
     *  @param mode 0 disabled, 1 level interrupt, 2 SMBAlert style.
     *  @param persist 0 every cycle, 1 any value outside, N (2–15) N consecutive cycles outside.
     *  @return false with ERR_ARG on out-of-range values. */
    bool configureInterrupt(uint8_t mode, uint8_t persist = 0);

    /** @brief Read the ID register; `(id >> 4) == 1` for the TSL2561.
     *  @return ID byte, or -1 on a bus error. */
    int readId();

    /** @brief Check channel 0 against the programmed window (persistence not modelled, interrupt not cleared).
     *  @return true if channel 0 is ≤ the low or > the high threshold (false also on a bus error). */
    bool pollInterrupt();

    /** @brief Send the command byte 0xC0 (CMD + CLEAR) — releases a level interrupt. */
    bool clearInterrupt();

    /** @brief Subscribe to INT (falling edge).
     *
     *  With a pin (intPin, else connection.intPin()) the edge handler reads channel 0, calls
     *  `callback(ch0)` and clears the level interrupt. Without a pin call service() periodically.
     *  @param callback Called with the current channel-0 count.
     *  @param intPin Optional InputPin overriding connection.intPin(). */
    void onInterrupt(void (*callback)(uint16_t ch0), InputPin* intPin = nullptr);

    /** @brief Unsubscribe and stop delivery. */
    void offInterrupt();

    /** @brief Polling delivery without a pin: calls the callback if pollInterrupt() is true. */
    void service();

protected:
    void (*_callback)(uint16_t ch0) = nullptr;
    InputPin* _intPinUsed = nullptr;

    bool _applyRange(int index);
    void _handleEdge();

    static TSL2561Full* _activeInstance;
    static void _edgeTrampoline();
};
