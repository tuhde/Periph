#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/RegisterConnection.h"

/** @brief Four channel counts of one APDS-9250 conversion (unsigned, up to 20 bit). */
struct Apds9250Counts {
    uint32_t red;
    uint32_t green;
    uint32_t blue;
    uint32_t ir;
};

/** @brief APDS-9250 digital RGB, IR and ambient light sensor (Broadcom) — minimal interface.
 *
 *  Four simultaneously integrating photodiode channels (red, green, blue, IR; green doubles as the
 *  ambient-light channel) with a 13- to 20-bit ADC, five gains and a 25 ms to 2 s measurement
 *  rate. Results are unsigned counts in 3-byte registers (LSB first). The I²C address is fixed at
 *  0x52.
 *
 *  The constructor reads PART_ID (the upper nibble must be 0xB; otherwise valid() stays false and
 *  nothing is configured), writes the defaults (18 bit, 100 ms, gain x3, interrupt off) and sets
 *  LS_EN and CS_MODE. Reading MAIN_STATUS clears its flags, so the driver keeps the interrupt and
 *  power-on bits it has seen until pollInterrupt() / status() hands them out.
 *
 *  @param connection RegisterConnection (I²C, 1-byte register address) pointing at the device.
 */
class Apds9250Minimal {
public:
    /** @brief Fixed 7-bit I²C address. */
    static constexpr uint8_t I2C_ADDRESS = 0x52;
    /** @brief Wait after enabling the sensor so the support blocks settle, in ms (tests may set it to 0). */
    static unsigned startWaitMs;
    /** @brief Poll step while waiting for LS_DATA, in ms (tests may set it to 0). */
    static unsigned pollStepMs;

    explicit Apds9250Minimal(RegisterConnection& connection);

    /** @brief Whether PART_ID identified an APDS-9250 at construction. */
    bool valid() const { return _valid; }

    /** @brief Read all four channels from one conversion.
     *
     *  Waits for a new result (at most one integration time plus 50 ms), then block-reads
     *  registers 0x0A-0x15.
     *  @param counts Output: the four unsigned counts.
     *  @return false (counts untouched) if the sensor was not found at construction. */
    bool read(Apds9250Counts& counts);

protected:
    static constexpr uint8_t REG_MAIN_CTRL       = 0x00;
    static constexpr uint8_t REG_LS_MEAS_RATE    = 0x04;
    static constexpr uint8_t REG_LS_GAIN         = 0x05;
    static constexpr uint8_t REG_PART_ID         = 0x06;
    static constexpr uint8_t REG_MAIN_STATUS     = 0x07;
    static constexpr uint8_t REG_DATA_IR         = 0x0A;
    static constexpr uint8_t REG_DATA_GREEN      = 0x0D;
    static constexpr uint8_t REG_DATA_BLUE       = 0x10;
    static constexpr uint8_t REG_DATA_RED        = 0x13;
    static constexpr uint8_t REG_INT_CFG         = 0x19;
    static constexpr uint8_t REG_INT_PERSISTENCE = 0x1A;
    static constexpr uint8_t REG_THRES_UP        = 0x21;
    static constexpr uint8_t REG_THRES_LOW       = 0x24;
    static constexpr uint8_t REG_THRES_VAR       = 0x27;
    static constexpr uint8_t CTRL_SW_RESET = 0x10;
    static constexpr uint8_t CTRL_CS_MODE  = 0x04;
    static constexpr uint8_t CTRL_LS_EN    = 0x02;
    static constexpr uint8_t INT_EN        = 0x04;
    static constexpr uint8_t INT_VAR_MODE  = 0x08;
    static constexpr uint8_t INT_SEL_MASK  = 0x30;
    static constexpr uint8_t ST_POWER_ON   = 0x20;
    static constexpr uint8_t ST_INTERRUPT  = 0x10;
    static constexpr uint8_t ST_DATA_READY = 0x08;

    RegisterConnection& _connection;
    bool _valid = false;
    uint8_t _pending = 0;
    float _integrationS = 0.1f;

    static void _delayMs(unsigned ms);
    uint8_t _readReg(uint8_t reg);
    void _writeReg(uint8_t reg, uint8_t value);
    void _updateReg(uint8_t reg, uint8_t setBits, uint8_t clearBits);
    uint8_t _readStatus();
    void _waitData();
    void _readBlock(Apds9250Counts& counts);
    uint32_t _readChannel(uint8_t reg);
    static uint32_t _count(const uint8_t* b);
};

/** @brief APDS-9250 full interface — extends Minimal with single-channel reads, mode, resolution,
 *  rate and gain configuration, thresholds, variation and persistence, and interrupt delivery.
 *
 *  Setters that take a restricted value (resolution, rate, gain, persistence, variance, channel)
 *  return false and write nothing when the value is not supported.
 *
 *  @param connection RegisterConnection (I²C, 1-byte register address) pointing at the device.
 */
class Apds9250Full : public Apds9250Minimal {
public:
    /** @brief Interrupt source: selected channel outside the threshold window. */
    static constexpr uint8_t SOURCE_THRESHOLD = 0x01;
    /** @brief Interrupt source: selected channel changed by more than the variance threshold. */
    static constexpr uint8_t SOURCE_VARIATION = 0x02;
    /** @brief MAIN_STATUS bit 5: power-up event occurred. */
    static constexpr uint8_t STATUS_POWER_ON = 0x20;
    /** @brief MAIN_STATUS bit 4: interrupt condition fulfilled. */
    static constexpr uint8_t STATUS_INTERRUPT = 0x10;
    /** @brief MAIN_STATUS bit 3: new data available. */
    static constexpr uint8_t STATUS_DATA_READY = 0x08;
    /** @brief Interrupt channel: infrared. */
    static constexpr uint8_t CHANNEL_IR = 0;
    /** @brief Interrupt channel: green / ambient light. */
    static constexpr uint8_t CHANNEL_GREEN = 1;
    /** @brief Interrupt channel: red. */
    static constexpr uint8_t CHANNEL_RED = 2;
    /** @brief Interrupt channel: blue. */
    static constexpr uint8_t CHANNEL_BLUE = 3;

    explicit Apds9250Full(RegisterConnection& connection);

    /** @brief Read the red channel (3-byte read). */
    uint32_t readRed();
    /** @brief Read the green / ambient-light channel. */
    uint32_t readGreen();
    /** @brief Read the blue channel. */
    uint32_t readBlue();
    /** @brief Read the infrared channel. */
    uint32_t readIr();

    /** @brief Block-read all four channels without waiting (call after dataReady()). */
    void readChannels(Apds9250Counts& counts);

    /** @brief Report whether a new result is available.
     *
     *  Reads MAIN_STATUS (clearing its flags); the interrupt and power-on bits are kept for
     *  pollInterrupt() and status().
     *  @return true if LS_DATA was set. */
    bool dataReady();

    /** @brief Select colour-sensing or ALS/IR mode.
     *  @param colour true: CS mode (red, green, blue, IR); false: ALS/IR (green and IR only). */
    void setMode(bool colour);
    /** @brief Activate the light sensor (LS_EN = 1). */
    void enable();
    /** @brief Put the light sensor in standby (LS_EN = 0). */
    void disable();

    /** @brief Set the ADC resolution (and integration time); restarts the measurement.
     *  @param bits 13, 16, 17, 18, 19 or 20.
     *  @return false (nothing written) for an unsupported value. */
    bool setResolution(uint8_t bits);
    /** @brief Set the measurement rate.
     *  @param milliseconds 25, 50, 100, 200, 500, 1000 or 2000.
     *  @return false (nothing written) for an unsupported value. */
    bool setMeasurementRate(uint16_t milliseconds);
    /** @brief Set the analog gain for all channels; restarts the measurement.
     *  @param gain 1, 3, 6, 9 or 18.
     *  @return false (nothing written) for an unsupported value. */
    bool setGain(uint8_t gain);

    /** @brief Read the ADC resolution in bits. */
    uint8_t getResolution();
    /** @brief Read the measurement rate in milliseconds. */
    uint16_t getMeasurementRate();
    /** @brief Read the analog gain (1, 3, 6, 9 or 18). */
    uint8_t getGain();
    /** @brief Integration time of the current resolution in seconds (0.003125 ... 0.4). */
    float integrationTime();
    /** @brief Convert a count to counts per gain-second: `counts / (gain · integrationTime)`. */
    float normalized(uint32_t counts);

    /** @brief Read PART_ID.
     *  @param part Output: upper nibble (0xB).
     *  @param revision Output: lower nibble (0x5). */
    void partId(uint8_t& part, uint8_t& revision);

    /** @brief Read MAIN_STATUS (clearing the flags on the chip).
     *  @return Raw status byte with interrupt / power-on bits seen since the last call merged in. */
    uint8_t status();

    /** @brief Trigger a software reset (all registers to defaults, sensor in standby). */
    void softReset();

    /** @brief Set the 20-bit threshold window.
     *  @return false (nothing written) if a value exceeds 20 bit or low > high. */
    bool setThresholds(uint32_t low, uint32_t high);
    /** @brief Read the threshold window. */
    void getThresholds(uint32_t& low, uint32_t& high);

    /** @brief Select the channel the interrupt monitors.
     *  @param channel CHANNEL_IR, CHANNEL_GREEN, CHANNEL_RED or CHANNEL_BLUE.
     *  @return false (nothing written) for an unknown channel. */
    bool setInterruptChannel(uint8_t channel);
    /** @brief Set the number of consecutive events before the interrupt asserts.
     *  @param count 1-16.
     *  @return false (nothing written) if out of range. */
    bool setPersistence(uint8_t count);
    /** @brief Set the variation-interrupt threshold.
     *  @param counts 8, 16, 32, ... 1024.
     *  @return false (nothing written) if not 8 << n (n = 0-7). */
    bool setVariance(uint16_t counts);

    /** @brief Select threshold or variation mode and enable the interrupt.
     *  @param source SOURCE_THRESHOLD or SOURCE_VARIATION. */
    void enableInterrupt(uint8_t source = SOURCE_THRESHOLD);
    /** @brief Disable the interrupt (clears LS_INT_EN; both sources share the bit). */
    void disableInterrupt();

    /** @brief Read MAIN_STATUS (clearing the flags) and return it; same as status(). */
    uint8_t pollInterrupt();

    /** @brief Subscribe to INT assertions.
     *
     *  Wires @p intPin->onEdge() (or connection.intPin() if @p intPin is nullptr) on the falling
     *  edge. Without any InputPin, call service() periodically instead.
     *  @param callback void(*)(uint8_t status), called with the MAIN_STATUS byte after it was read.
     *  @param intPin Optional InputPin overriding connection.intPin(). */
    void onInterrupt(void (*callback)(uint8_t status), InputPin* intPin = nullptr);

    /** @brief Unsubscribe and stop delivery. */
    void offInterrupt();

    /** @brief Poll once and dispatch the callback when STATUS_INTERRUPT is set
     *  (for use without an INT pin; also the INT edge handler). */
    void service();

protected:
    void (*_callback)(uint8_t status) = nullptr;
    InputPin* _intPinUsed = nullptr;

    static Apds9250Full* _activeInstance;
    static void _edgeTrampoline();
};
