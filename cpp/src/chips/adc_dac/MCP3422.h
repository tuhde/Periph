#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/Connection.h"

/** @brief MCP3422 2-channel 18-bit delta-sigma ADC with PGA (Microchip) — minimal interface.
 *
 *  Two differential inputs, an on-board 2.048 V reference, a PGA (x1, x2, x4, x8) and an on-board
 *  oscillator. The resolution is selected together with the data rate: 12 bit / 240 SPS,
 *  14 bit / 60 SPS, 16 bit / 15 SPS, 18 bit / 3.75 SPS. The full-scale differential input range is
 *  ±2.048 V / gain. The chip has no register map: a write is one configuration byte
 *  (`RDY C1 C0 O/C S1 S0 G1 G0`) and a read returns the 2 (12/14/16 bit) or 3 (18 bit) result bytes
 *  followed by the configuration byte, whose RDY bit (0 = fresh result) says whether the conversion
 *  has finished. The driver therefore uses the plain Connection `write()` / `read()` calls.
 *
 *  Defaults: one-shot mode (lowest power), 16 bit / 15 SPS (LSB 62.5 µV), gain x1. The constructor
 *  writes that configuration with RDY = 0, so nothing is started. A conversion that does not finish
 *  within twice its nominal time plus 10 ms makes the read return 0 and lastReadOk() false.
 *
 *  @param connection Connection (I²C) pointing at the device (0x68–0x6F).
 */
class MCP3422Minimal {
public:
    /** @brief 7-bit I²C address of the standard part (order code A0). */
    static constexpr uint8_t I2C_ADDRESS = 0x68;

    explicit MCP3422Minimal(Connection& connection);

    /** @brief Run a one-shot conversion on a channel and return the signed code.
     *  @param channel 0 (CH1) or 1 (CH2); values outside 0–1 are clamped.
     *  @return Signed code, −2^(N−1) … 2^(N−1) − 1 (N = resolution bits); 0 if lastReadOk() is false. */
    int32_t readRaw(int channel);

    /** @brief Run a one-shot conversion on a channel and return the differential voltage.
     *  @param channel 0 (CH1) or 1 (CH2); values outside 0–1 are clamped.
     *  @return `CHn+ − CHn−` in volts, `raw · lsb / gain`; 0 if lastReadOk() is false. */
    float readVoltage(int channel);

    /** @brief Convert both channels, one conversion each.
     *  @param out Output: two voltages, CH1 then CH2.
     *  @return true if both conversions finished. */
    bool readAll(float* out);

    /** @brief Size of one code before the gain, `2 · 2.048 / 2^bits` in volts. */
    float lsb() const;

    /** @brief Whether the most recent conversion finished in time. */
    bool lastReadOk() const { return _lastOk; }

protected:
    static constexpr uint8_t CFG_RDY  = 0x80;
    static constexpr uint8_t CFG_CONT = 0x10;

    Connection& _connection;
    uint8_t _bits = 16;
    uint8_t _gain = 1;
    uint8_t _channel = 0;
    bool _continuous = false;
    bool _discard = false;
    bool _lastOk = true;

    static void _delayMs(unsigned ms);
    uint8_t _config(bool rdy) const;
    uint8_t _nbytes() const { return _bits == 18 ? 3 : 2; }
    float _convSeconds() const;
    int32_t _decode(const uint8_t* data) const;
    int32_t _waitReady();
    int32_t _convert(int channel);
};

/** @brief MCP3422 full interface — extends Minimal with resolution, gain and channel selection,
 *  continuous conversion, status reads, scans and general calls.
 *
 *  The driver tracks resolution, gain, channel and mode (the chip only reports the configuration byte).
 *
 *  @param connection Connection (I²C) pointing at the device (0x68–0x6F).
 */
class MCP3422Full : public MCP3422Minimal {
public:
    explicit MCP3422Full(Connection& connection);

    /** @brief Select the resolution (and with it the data rate).
     *  @param bits 12 (240 SPS), 14 (60 SPS), 16 (15 SPS) or 18 (3.75 SPS).
     *  @return false (nothing changed) for any other value. */
    bool setResolution(int bits);

    /** @brief The selected resolution: 12, 14, 16 or 18. */
    int getResolution() const { return _bits; }

    /** @brief Select the PGA gain.
     *  @param gain 1, 2, 4 or 8.
     *  @return false (nothing changed) for any other value. */
    bool setGain(int gain);

    /** @brief The selected PGA gain: 1, 2, 4 or 8. */
    int getGain() const { return _gain; }

    /** @brief Select the channel used by continuous conversion (0 or 1). */
    void setChannel(int channel);

    /** @brief Switch to continuous conversion at the current rate and gain.
     *  @param channel 0 or 1 to select first, or a negative value to keep the current channel. */
    void startContinuous(int channel = -1);

    /** @brief Return to one-shot mode / standby (writes O/C = 0, RDY = 0). */
    void stopContinuous();

    /** @brief true in continuous mode. */
    bool isContinuous() const { return _continuous; }

    /** @brief Wait for a fresh result in continuous mode and return the signed code.
     *
     *  The first sample after a configuration change is discarded.
     *  @return Signed code; 0 (and lastReadOk() false) if not in continuous mode or on timeout. */
    int32_t readContinuousRaw();

    /** @brief Voltage form of readContinuousRaw(), in volts. */
    float readContinuous();

    /** @brief Read the configuration byte (the last byte of a read); bit 7 is the live RDY flag. */
    uint8_t readConfig();

    /** @brief true if a fresh result is available (RDY = 0). */
    bool isReady();

    /** @brief Run one one-shot conversion per entry, in order.
     *  @param channels Channels (0 or 1).
     *  @param count    Number of entries.
     *  @param out      Output: @p count voltages in volts.
     *  @return true if every conversion finished. */
    bool readScan(const uint8_t* channels, size_t count, float* out);

    /** @brief Send the general call reset (0x00 0x06): power-on defaults on the chip (continuous,
     *  12 bit, gain x1, CH1); the driver state follows. Needs a Connection that transmits to the
     *  general-call address. */
    void generalCallReset();

    /** @brief Send the general call conversion (0x00 0x08): a one-shot conversion on every device on
     *  the bus; read the result with readPendingRaw(). */
    void generalCallConvert();

    /** @brief Wait for RDY = 0 and return the code of a conversion started elsewhere (0 and lastReadOk() false on timeout). */
    int32_t readPendingRaw();

    /** @brief Full-scale differential input range at the current gain, `2.048 / gain` in volts. */
    float fullScale() const;

protected:
    void _apply();
};
