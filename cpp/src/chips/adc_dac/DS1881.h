#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/Connection.h"

/** @brief DS1881 dual nonvolatile audio log-taper digital potentiometer (Maxim) — minimal interface.
 *
 *  Two 45 kΩ potentiometers whose wiper positions are steps in an attenuation
 *  table. Option 1 gives 64 positions (1 dB per step, 0 to −62 dB, position
 *  63 = mute); Option 2 (DS1808 compatible) gives 34 positions (12 × 1 dB,
 *  12 × 2 dB, 8 × 3 dB, position 33 = mute). The I²C address is 0x28
 *  (A2 = A1 = A0 = 0) to 0x2F.
 *
 *  The chip has no register map: a write is one or more command bytes
 *  (`00pppppp` wiper 0, `01pppppp` wiper 1, `10xxxVZC` configuration) and a
 *  read returns wiper 0, wiper 1 and the configuration register
 *  round-robin. The driver therefore uses the plain Connection `write()` /
 *  `read()` calls. Wipers and configuration live in EEPROM when V/NV = 0
 *  (50 000 writes); the drivers default to volatile operation. The CE pin is
 *  active low and is left to the Connection `enPin` (`enActiveHigh = false`).
 *
 *  The constructor makes no bus access; call init() once after power-up.
 *  Channel arguments other than 0 and 1 are ignored (getters return 0).
 *
 *  @param connection Connection (I²C) pointing at the device (0x28–0x2F).
 */
class DS1881Minimal {
public:
    /** @brief 7-bit I²C address with A2 = A1 = A0 = 0 (0x28–0x2F with the address pins). */
    static constexpr uint8_t I2C_ADDRESS = 0x28;

    explicit DS1881Minimal(Connection& connection);

    /** @brief Run the initialization sequence.
     *
     *  Waits the 1 ms power-up time, reads the three bytes and rewrites the
     *  configuration (volatile, zero-crossing on, Option 1) only if it
     *  differs, waiting for the EEPROM write that this triggers. The wipers
     *  are not touched. */
    void init();

    /** @brief Set a channel's attenuation to the nearest position.
     *  @param channel 0 or 1.
     *  @param db      Attenuation in dB (positive = quieter); rounded to the nearest step, clamped to 0…62 dB (Option 1). */
    void setAttenuation(uint8_t channel, float db);

    /** @brief Read a channel's attenuation.
     *  @param channel 0 or 1.
     *  @return Attenuation in dB (80.0 for mute). */
    float getAttenuation(uint8_t channel);

    /** @brief Mute a channel (position 63 in Option 1).
     *  @param channel 0 or 1. */
    void mute(uint8_t channel);

    /** @brief Write a raw wiper position.
     *  @param channel  0 or 1.
     *  @param position 0–63 (63 = mute); clamped. */
    void setPosition(uint8_t channel, int position);

    /** @brief Read a wiper position with the three-byte round-robin read.
     *  @param channel 0 or 1.
     *  @return Position (low 6 bits of the read-back byte). */
    uint8_t getPosition(uint8_t channel);

protected:
    static constexpr uint8_t CMD_WIPER1   = 0x40;
    static constexpr uint8_t CMD_CONFIG   = 0x80;
    static constexpr uint8_t CFG_VOLATILE = 0x04;
    static constexpr uint8_t CFG_ZC       = 0x02;
    static constexpr uint8_t CFG_OPTION2  = 0x01;
    static constexpr uint8_t CFG_MASK     = 0x07;
    static constexpr uint8_t CFG_DEFAULT  = 0x06;  // volatile, zero-crossing, Option 1

    Connection& _connection;
    uint8_t _config;   // cached configuration bits 2:0
    uint8_t _option;   // 1 or 2

    static void _delayMs(unsigned ms);
    bool _waitReady(float timeoutS);
    void _writeCommands(const uint8_t* data, size_t len);
    void _writePosition(uint8_t channel, int position);
    void _readAll(uint8_t& position0, uint8_t& position1, uint8_t& config);
    uint8_t _mutePosition() const;
    float _positionToDb(int position) const;
    virtual uint8_t _dbToPosition(float db) const;
    uint8_t _nearestPosition(float db) const;
};

/** @brief DS1881 configuration as read back from the chip. */
struct DS1881Config {
    uint8_t option;      /**< 1 or 2. */
    bool zeroCrossing;   /**< Zero-crossing detection enabled. */
    bool nonvolatile;    /**< Wiper writes go to EEPROM. */
    uint8_t raw;         /**< Raw configuration byte. */
};

/** @brief DS1881 full interface — extends Minimal with stereo writes, Option 2,
 *  zero-crossing and EEPROM control, and table helpers.
 *
 *  The driver caches the configuration (volatile, zero-crossing, option);
 *  init() and getConfig() refresh it from the chip.
 *
 *  @param connection Connection (I²C) pointing at the device (0x28–0x2F).
 */
class DS1881Full : public DS1881Minimal {
public:
    explicit DS1881Full(Connection& connection);

    /** @brief Write both wipers in one transaction (no STOP between them).
     *  @param position0 Position for channel 0 (clamped).
     *  @param position1 Position for channel 1 (clamped). */
    void setBoth(int position0, int position1);

    /** @brief Set both channels' attenuation (stereo volume).
     *  @param db0 Attenuation of channel 0 in dB.
     *  @param db1 Attenuation of channel 1 in dB; a negative value uses @p db0. */
    void setAttenuationBoth(float db0, float db1 = -1.0f);

    /** @brief Write the configuration byte if it differs from the cached value.
     *
     *  The configuration is stored in EEPROM, so a write waits for
     *  acknowledge polling. The wipers are not rewritten — positions keep their
     *  numeric value when the option changes.
     *  @param option       1 (63 positions + mute, 1 dB steps) or 2 (33 positions + mute, DS1808 compatible); other values are ignored.
     *  @param zeroCrossing Enable zero-crossing detection.
     *  @param nonvolatile  Store wiper writes in EEPROM and restore them at power-up (50 000 writes). */
    void configure(uint8_t option = 1, bool zeroCrossing = true, bool nonvolatile = false);

    /** @brief Read the configuration register (fresh read; refreshes the cache).
     *  @return The decoded configuration. */
    DS1881Config getConfig();

    /** @brief Switch between Option 1 and 2 (positions are not converted).
     *  @param option 1 or 2. */
    void setOption(uint8_t option);

    /** @brief Enable or disable zero-crossing detection.
     *  @param enabled true to enable. */
    void setZeroCrossing(bool enabled);

    /** @brief Select where wiper writes go (V/NV bit).
     *  @param enabled true: subsequent wiper writes are stored in EEPROM and restored at power-up (50 000 writes). */
    void setNonvolatile(bool enabled);

    /** @brief Persist the current wiper positions to EEPROM.
     *
     *  Reads both positions, switches to nonvolatile mode if needed, rewrites
     *  both wiper bytes in one transaction, waits by acknowledge polling and
     *  restores volatile mode if it was active. The EEPROM is rated for only
     *  50 000 writes. */
    void store();

    /** @brief Acknowledge-poll until the device answers (it NACKs while an EEPROM write is in progress).
     *  @param timeoutS Maximum time to wait in seconds.
     *  @return true if the device answered, false on timeout. */
    bool waitReady(float timeoutS = 0.05f);

    /** @brief Read wiper 0, wiper 1 and the configuration byte in one transaction.
     *  @param position0 Receives wiper 0.
     *  @param position1 Receives wiper 1.
     *  @param config    Receives the raw configuration byte. */
    void readAll(uint8_t& position0, uint8_t& position1, uint8_t& config);

    /** @brief Attenuation of a position in the current option.
     *  @param position Wiper position.
     *  @return dB (80.0 for mute and above). */
    float positionToDb(int position) const;

    /** @brief Nearest position for an attenuation in the current option.
     *  @param db Attenuation in dB; ≥ 70 dB maps to mute, negative to 0.
     *  @return Wiper position. */
    uint8_t dbToPosition(float db) const;

    /** @brief Last non-mute position of the current option.
     *  @return 62 (Option 1) or 32 (Option 2). */
    uint8_t maxPosition() const;

    /** @brief Volatile, zero-crossing on, Option 1 and both wipers to mute.
     *
     *  Sent as one transaction (configuration byte, then both wiper bytes);
     *  waits for the configuration EEPROM write. */
    void reset();

protected:
    uint8_t _dbToPosition(float db) const override;
};
