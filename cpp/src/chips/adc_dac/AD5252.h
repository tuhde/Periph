#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/RegisterConnection.h"

/** @brief AD5252 dual 256-position I²C digital potentiometer with EEMEM (Analog Devices) — minimal interface.
 *
 *  Two independent 3-terminal potentiometers (A, W, B) usable as voltage
 *  dividers or 2-terminal rheostats. The chip has no register map: the
 *  instruction byte works as a one-byte register pointer (RDAC1 = 0x01 for
 *  channel 0, RDAC3 = 0x03 for channel 1), so the driver uses a
 *  RegisterConnection with a 1-byte register width. The I²C address is
 *  0x2C–0x2F (AD1, AD0 pins). Wipers power up from EEMEM (midscale 128 until
 *  a value is stored).
 *
 *  Resistances follow `R_WB = D/256 · RAB + RW`. The constructor makes no
 *  bus access.
 *
 *  @param connection RegisterConnection (I²C or SMBus) pointing at the device (0x2C–0x2F).
 *  @param rAb        End-to-end resistance RAB in Ω (default 10 kΩ part).
 *  @param rWiper     Wiper resistance RW in Ω (typical 75 Ω).
 */
class AD5252Minimal {
public:
    /** @brief Default 7-bit I²C address (AD1 = AD0 = GND); 0x2C–0x2F are possible. */
    static constexpr uint8_t I2C_ADDRESS = 0x2C;

    explicit AD5252Minimal(RegisterConnection& connection, float rAb = 10000.0f, float rWiper = 75.0f);

    /** @brief Set the wiper position of a channel.
     *  @param channel 0 or 1 (other values are ignored, returning false).
     *  @param code    Wiper position 0–255 (clamped).
     *  @return true if @p channel was valid and the write was issued. */
    bool setWiper(uint8_t channel, int code);

    /** @brief Read back the wiper position (instruction byte alone, then one byte).
     *  @param channel 0 or 1.
     *  @return Wiper position 0–255 (0 if @p channel is invalid). */
    uint8_t getWiper(uint8_t channel);

    /** @brief Set the W–B resistance to the nearest reachable code.
     *  @param channel 0 or 1.
     *  @param ohms    Target W–B resistance in Ω (clamped to the reachable range).
     *  @return true if @p channel was valid. */
    bool setResistance(uint8_t channel, float ohms);

    /** @brief Read the W–B resistance for the current wiper code.
     *  @param channel 0 or 1.
     *  @return Resistance in Ω, `D/256 · RAB + RW`. */
    float getResistance(uint8_t channel);

protected:
    RegisterConnection& _connection;
    float _rAb;
    float _rW;

    static uint8_t _clampCode(int code);
    bool _writeCode(uint8_t channel, int code);
    uint8_t _readCode(uint8_t channel);
};

/** @brief AD5252 full interface — extends Minimal with ratio and W–A helpers,
 *  quick commands (±1 step, ±6 dB), EEMEM store/restore/reset, user EEMEM and
 *  the factory RAB tolerance.
 *
 *  EEMEM writes (store, writeEemem, reset) take ≈ 26 ms during which the chip
 *  does not acknowledge; they poll with the NOP quick command (timeout 100 ms).
 *
 *  @param connection RegisterConnection (I²C or SMBus) pointing at the device (0x2C–0x2F).
 *  @param rAb        End-to-end resistance RAB in Ω.
 *  @param rWiper     Wiper resistance RW in Ω.
 */
class AD5252Full : public AD5252Minimal {
public:
    /** @brief Channel selector meaning "both channels" for the quick commands. */
    static constexpr uint8_t BOTH = 0xFF;

    explicit AD5252Full(RegisterConnection& connection, float rAb = 10000.0f, float rWiper = 75.0f);

    /** @brief Write both wipers in one consecutive-write transaction.
     *  @param code0 Channel 0 wiper position 0–255.
     *  @param code1 Channel 1 wiper position 0–255. */
    void setBoth(int code0, int code1);

    /** @brief Set the wiper by divider ratio V(W−B)/V(A−B).
     *  @param channel 0 or 1.
     *  @param ratio   0.0–1.0; the code is round(ratio × 256) clamped to 255.
     *  @return true if @p channel was valid. */
    bool setRatio(uint8_t channel, float ratio);

    /** @brief Read the divider ratio of a channel.
     *  @param channel 0 or 1.
     *  @return `code / 256`. */
    float getRatio(uint8_t channel);

    /** @brief Set the W–A resistance (rheostat mode, B open) to the nearest code.
     *  @param channel 0 or 1.
     *  @param ohms    Target W–A resistance in Ω.
     *  @return true if @p channel was valid. */
    bool setResistanceWa(uint8_t channel, float ohms);

    /** @brief Read the W–A resistance for the current wiper code.
     *  @param channel 0 or 1.
     *  @return Resistance in Ω, `(256 − D)/256 · RAB + RW`. */
    float getResistanceWa(uint8_t channel);

    /** @brief Increment the wiper by one step (quick command).
     *  @param channel 0, 1 or BOTH.
     *  @return true if @p channel was valid. */
    bool increment(uint8_t channel = BOTH);

    /** @brief Decrement the wiper by one step (quick command).
     *  @param channel 0, 1 or BOTH.
     *  @return true if @p channel was valid. */
    bool decrement(uint8_t channel = BOTH);

    /** @brief Increase by 6 dB — the code doubles (quick command).
     *  @param channel 0, 1 or BOTH.
     *  @return true if @p channel was valid. */
    bool doubleCode(uint8_t channel = BOTH);

    /** @brief Decrease by 6 dB — the code halves (quick command).
     *  @param channel 0, 1 or BOTH.
     *  @return true if @p channel was valid. */
    bool halve(uint8_t channel = BOTH);

    /** @brief Store the wiper in EEMEM, then wait for the ≈ 26 ms write cycle.
     *  @param channel 0, 1 or BOTH.
     *  @return true if the chip acknowledged again within 100 ms. */
    bool store(uint8_t channel = BOTH);

    /** @brief Restore the wiper from EEMEM, then send a NOP.
     *  @param channel 0, 1 or BOTH.
     *  @return true if @p channel was valid. */
    bool restore(uint8_t channel = BOTH);

    /** @brief Quick-command reset: restore both wipers from EEMEM and wait.
     *  @return true if the chip acknowledged again within 100 ms. */
    bool reset();

    /** @brief Read a user EEMEM byte.
     *  @param index 4–15.
     *  @return The stored byte (0 if @p index is out of range). */
    uint8_t readEemem(uint8_t index);

    /** @brief Write a user EEMEM byte and wait for the write cycle.
     *  @param index 4–15.
     *  @param value Byte to store.
     *  @return true if @p index was valid and the chip acknowledged again within 100 ms. */
    bool writeEemem(uint8_t index, uint8_t value);

    /** @brief Read the factory-stored RAB tolerance of a channel.
     *  @param channel 0 or 1.
     *  @return Tolerance in percent (0 if @p channel is invalid). */
    float readTolerance(uint8_t channel);

    /** @brief Actual RAB of a channel from its stored tolerance.
     *  @param channel 0 or 1.
     *  @return `rAb · (1 + tolerance/100)` in Ω. */
    float rabActual(uint8_t channel);

    /** @brief Send the NOP quick command (0x80). */
    void nop();

    /** @brief Change the resistance parameters used by conversions (no bus traffic).
     *  @param rAb    End-to-end resistance RAB in Ω.
     *  @param rWiper Wiper resistance RW in Ω; a negative value keeps the current RW. */
    void setRab(float rAb, float rWiper = -1.0f);

private:
    bool _quick(uint8_t base, uint8_t baseAll, uint8_t channel);
    void _quickByte(uint8_t b);
    bool _waitReady();
    static void _delayMs(unsigned ms);
};
