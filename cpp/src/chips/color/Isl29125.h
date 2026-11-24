#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/RegisterConnection.h"

/** @brief Three channel counts of one ISL29125 conversion (unsigned 16 bit). */
struct Isl29125Counts {
    uint16_t red;
    uint16_t green;
    uint16_t blue;
};

/** @brief ISL29125 digital red, green and blue colour light sensor (Renesas / Intersil) — minimal interface.
 *
 *  RGB colour sensor with an IR-blocking filter, a 12/16-bit integrating ADC (16 bit: 100 ms),
 *  two sensing ranges (375 lux and 10 000 lux full scale) and programmable IR compensation.
 *  Results are unsigned 16-bit counts, LSB first. The I²C address is fixed at 0x44.
 *
 *  The constructor reads DEVICE_ID (must be 0x7D; otherwise valid() stays false and nothing is
 *  configured), clears the brown-out flag and writes CONFIG1 = 0x0D (RGB, 10 000 lux, 16 bit),
 *  CONFIG2 = 0xBF (maximum IR compensation), CONFIG3 = 0x00. Reading STATUS clears its flags, so
 *  the driver keeps the flags it has seen until pollInterrupt() / status() hands them out.
 *
 *  @param connection RegisterConnection (I²C, 1-byte register address) pointing at the device.
 */
class Isl29125Minimal {
public:
    /** @brief Fixed 7-bit I²C address. */
    static constexpr uint8_t I2C_ADDRESS = 0x44;
    /** @brief Expected DEVICE_ID value. */
    static constexpr uint8_t DEVICE_ID = 0x7D;
    /** @brief Poll step while waiting for CONVENF, in ms (tests may set it to 0). */
    static unsigned pollStepMs;

    explicit Isl29125Minimal(RegisterConnection& connection);

    /** @brief Whether DEVICE_ID identified an ISL29125 at construction. */
    bool valid() const { return _valid; }

    /** @brief Read red, green and blue from one conversion.
     *
     *  Waits for a new result (at most 400 ms), then block-reads registers 0x09-0x0E.
     *  @param counts Output: the three unsigned 16-bit counts.
     *  @return false (counts untouched) if the sensor was not found at construction. */
    bool read(Isl29125Counts& counts);

protected:
    static constexpr uint8_t REG_DEVICE_ID = 0x00;
    static constexpr uint8_t REG_CONFIG1   = 0x01;
    static constexpr uint8_t REG_CONFIG2   = 0x02;
    static constexpr uint8_t REG_CONFIG3   = 0x03;
    static constexpr uint8_t REG_LOW_TH    = 0x04;
    static constexpr uint8_t REG_HIGH_TH   = 0x06;
    static constexpr uint8_t REG_STATUS    = 0x08;
    static constexpr uint8_t REG_GREEN     = 0x09;
    static constexpr uint8_t REG_RED       = 0x0B;
    static constexpr uint8_t REG_BLUE      = 0x0D;
    static constexpr uint8_t RESET_VALUE   = 0x46;
    static constexpr uint8_t CFG1_SYNC     = 0x20;
    static constexpr uint8_t CFG1_BITS_12  = 0x10;
    static constexpr uint8_t CFG1_RNG_10K  = 0x08;
    static constexpr uint8_t CFG1_MODE     = 0x07;
    static constexpr uint8_t CFG3_CONVEN   = 0x10;
    static constexpr uint8_t ST_BROWNOUT   = 0x04;
    static constexpr uint8_t ST_CONVERSION = 0x02;
    static constexpr uint8_t ST_THRESHOLD  = 0x01;

    RegisterConnection& _connection;
    bool _valid = false;
    uint8_t _pending = 0;

    static void _delayMs(unsigned ms);
    uint8_t _readReg(uint8_t reg);
    void _writeReg(uint8_t reg, uint8_t value);
    void _updateReg(uint8_t reg, uint8_t setBits, uint8_t clearBits);
    uint8_t _readStatus();
    void _waitData();
    void _readBlock(Isl29125Counts& counts);
    uint16_t _readChannel(uint8_t reg);
};

/** @brief ISL29125 full interface — extends Minimal with single-channel reads, mode, range,
 *  resolution, synchronisation and IR-compensation configuration, thresholds, persistence and
 *  interrupt delivery.
 *
 *  Setters that take a restricted value return false and write nothing when the value is not
 *  supported.
 *
 *  @param connection RegisterConnection (I²C, 1-byte register address) pointing at the device.
 */
class Isl29125Full : public Isl29125Minimal {
public:
    /** @brief Interrupt source: selected channel outside the threshold window. */
    static constexpr uint8_t SOURCE_THRESHOLD = 0x01;
    /** @brief Interrupt source: conversion complete. */
    static constexpr uint8_t SOURCE_CONVERSION = 0x02;
    /** @brief STATUS bit 2: power-down or brown-out occurred. */
    static constexpr uint8_t STATUS_BROWNOUT = 0x04;
    /** @brief STATUS bit 1: conversion completed. */
    static constexpr uint8_t STATUS_CONVERSION = 0x02;
    /** @brief STATUS bit 0: threshold window crossed. */
    static constexpr uint8_t STATUS_THRESHOLD = 0x01;
    /** @brief Mode: power-down. */
    static constexpr uint8_t MODE_POWER_DOWN = 0;
    /** @brief Mode: green only. */
    static constexpr uint8_t MODE_GREEN = 1;
    /** @brief Mode: red only. */
    static constexpr uint8_t MODE_RED = 2;
    /** @brief Mode: blue only. */
    static constexpr uint8_t MODE_BLUE = 3;
    /** @brief Mode: standby (no conversion). */
    static constexpr uint8_t MODE_STANDBY = 4;
    /** @brief Mode: green, red and blue. */
    static constexpr uint8_t MODE_RGB = 5;
    /** @brief Mode: green and red. */
    static constexpr uint8_t MODE_GREEN_RED = 6;
    /** @brief Mode: green and blue. */
    static constexpr uint8_t MODE_GREEN_BLUE = 7;
    /** @brief Interrupt channel: none. */
    static constexpr uint8_t CHANNEL_NONE = 0;
    /** @brief Interrupt channel: green. */
    static constexpr uint8_t CHANNEL_GREEN = 1;
    /** @brief Interrupt channel: red. */
    static constexpr uint8_t CHANNEL_RED = 2;
    /** @brief Interrupt channel: blue. */
    static constexpr uint8_t CHANNEL_BLUE = 3;

    explicit Isl29125Full(RegisterConnection& connection);

    /** @brief Read the red channel (2-byte read). */
    uint16_t readRed();
    /** @brief Read the green channel. */
    uint16_t readGreen();
    /** @brief Read the blue channel. */
    uint16_t readBlue();

    /** @brief Block-read all three channels without waiting. */
    void readRgb(Isl29125Counts& counts);

    /** @brief Report whether a conversion has completed.
     *
     *  Reads STATUS (clearing its flags); the flags are kept for pollInterrupt() and status().
     *  @return true if CONVENF was set. */
    bool dataReady();

    /** @brief Select the operating mode (conversions are continuous).
     *  @param mode MODE_POWER_DOWN ... MODE_GREEN_BLUE (0-7).
     *  @return false (nothing written) for an unknown mode. */
    bool setMode(uint8_t mode);
    /** @brief Select the sensing range.
     *  @param lux 375 or 10000 (full-scale illuminance).
     *  @return false (nothing written) for an unsupported range. */
    bool setRange(uint16_t lux);
    /** @brief Select the ADC resolution.
     *  @param bits 12 or 16.
     *  @return false (nothing written) for an unsupported value. */
    bool setResolution(uint8_t bits);
    /** @brief Select the conversion start source.
     *  @param external true: the rising edge of INT starts the conversion (INT becomes an input);
     *         false: the I²C write starts it. */
    void setSync(bool external);
    /** @brief Set the active IR compensation (CONFIG2).
     *  @param offset false: codes 0-63; true: codes 106-169.
     *  @param adjust Compensation adjust, 0-63.
     *  @return false (nothing written) if adjust exceeds 63. */
    bool setIrCompensation(bool offset, uint8_t adjust);

    /** @brief Read DEVICE_ID (0x7D for the ISL29125). */
    uint8_t deviceId();

    /** @brief Read STATUS (clearing the flags on the chip).
     *  @return Raw status byte with the flags seen since the last call merged in. */
    uint8_t status();

    /** @brief Reset all registers to their defaults (writes 0x46 to register 0x00). */
    void softReset();

    /** @brief Set the 16-bit threshold window.
     *  @return false (nothing written) if low > high. */
    bool setThresholds(uint16_t low, uint16_t high);
    /** @brief Read the threshold window. */
    void getThresholds(uint16_t& low, uint16_t& high);

    /** @brief Select the channel the threshold interrupt monitors.
     *  @param channel CHANNEL_NONE, CHANNEL_GREEN, CHANNEL_RED or CHANNEL_BLUE.
     *  @return false (nothing written) for an unknown channel. */
    bool setInterruptChannel(uint8_t channel);
    /** @brief Set the number of integration cycles before the interrupt asserts.
     *  @param count 1, 2, 4 or 8.
     *  @return false (nothing written) for an unsupported count. */
    bool setPersistence(uint8_t count);

    /** @brief Enable an interrupt source.
     *
     *  SOURCE_THRESHOLD needs a channel (setInterruptChannel); if none is selected, green is used.
     *  @param source SOURCE_THRESHOLD or SOURCE_CONVERSION. */
    void enableInterrupt(uint8_t source = SOURCE_THRESHOLD);
    /** @brief Disable an interrupt source (threshold: INTSEL = none; conversion: CONVEN = 0). */
    void disableInterrupt(uint8_t source = SOURCE_THRESHOLD);

    /** @brief Read STATUS (clearing the flags) and return it; same as status(). */
    uint8_t pollInterrupt();

    /** @brief Subscribe to INT assertions.
     *
     *  Wires @p intPin->onEdge() (or connection.intPin() if @p intPin is nullptr) on the falling
     *  edge. Without any InputPin, call service() periodically instead.
     *  @param callback void(*)(uint8_t status), called with the STATUS byte after it was read.
     *  @param intPin Optional InputPin overriding connection.intPin(). */
    void onInterrupt(void (*callback)(uint8_t status), InputPin* intPin = nullptr);

    /** @brief Unsubscribe and stop delivery. */
    void offInterrupt();

    /** @brief Poll once and dispatch the callback when a threshold or conversion flag is set
     *  (for use without an INT pin; also the INT edge handler). */
    void service();

protected:
    void (*_callback)(uint8_t status) = nullptr;
    InputPin* _intPinUsed = nullptr;

    static Isl29125Full* _activeInstance;
    static void _edgeTrampoline();
};
