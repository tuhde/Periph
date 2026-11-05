#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/RegisterConnection.h"

/** @brief AD5243 dual 256-position I²C digital potentiometer (Analog Devices) — minimal interface.
 *
 *  Two independent 3-terminal potentiometers (A, W, B) usable as voltage
 *  dividers or 2-terminal rheostats. The chip has no register map: a write is
 *  `[instruction, data]` where the instruction byte carries the channel select
 *  (A0, bit 7) and the shutdown flag (SD, bit 6); it is passed as the
 *  "register" of the RegisterConnection. The I²C address is fixed at 0x2F.
 *  Wiper values are volatile (power-up midscale 0x80).
 *
 *  Resistances follow `R_WB = D/256 · RAB + 2·RW`. The constructor makes no
 *  bus access.
 *
 *  @param connection RegisterConnection (I²C or SMBus) pointing at the device (0x2F).
 *  @param rAb        End-to-end resistance RAB in Ω (default 10 kΩ part).
 *  @param rWiper     Wiper resistance RW in Ω (typical 160 Ω).
 */
class AD5243Minimal {
public:
    /** @brief Fixed 7-bit I²C address (no address pins). */
    static constexpr uint8_t I2C_ADDRESS = 0x2F;

    explicit AD5243Minimal(RegisterConnection& connection, float rAb = 10000.0f, float rWiper = 160.0f);

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
     *  @return Resistance in Ω, `D/256 · RAB + 2·RW`. */
    float getResistance(uint8_t channel);

protected:
    static constexpr uint8_t INSTR_A0  = 0x80;
    static constexpr uint8_t INSTR_SD  = 0x40;
    static constexpr uint8_t MIDSCALE  = 0x80;

    RegisterConnection& _connection;
    float _rAb;
    float _rW;
    uint8_t _code[2];
    bool _sd[2];

    static uint8_t _clampCode(int code);
    uint8_t _instr(uint8_t channel) const;
    bool _writeCode(uint8_t channel, int code);
    uint8_t _readCode(uint8_t channel);
};

/** @brief AD5243 full interface — extends Minimal with ratio and W–A helpers,
 *  increment/decrement, per-channel shutdown and reset.
 *
 *  The shutdown state is write-only on the chip, so the driver tracks it
 *  (`false` after construction and after reset()).
 *
 *  @param connection RegisterConnection (I²C or SMBus) pointing at the device (0x2F).
 *  @param rAb        End-to-end resistance RAB in Ω.
 *  @param rWiper     Wiper resistance RW in Ω.
 */
class AD5243Full : public AD5243Minimal {
public:
    explicit AD5243Full(RegisterConnection& connection, float rAb = 10000.0f, float rWiper = 160.0f);

    /** @brief Write both wipers (two transactions).
     *  @param code1 Channel 0 wiper position 0–255.
     *  @param code2 Channel 1 wiper position 0–255. */
    void setBoth(int code1, int code2);

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
     *  @return Resistance in Ω, `(256 − D)/256 · RAB + 2·RW`. */
    float getResistanceWa(uint8_t channel);

    /** @brief Move the wiper relative to its current position.
     *  @param channel 0 or 1.
     *  @param steps   Signed step count (negative to decrement).
     *  @return The new wiper code, clamped to 0–255. */
    uint8_t increment(uint8_t channel, int steps = 1);

    /** @brief Shut a channel down: terminal A opens and the wiper shorts to B.
     *  The RDAC contents are kept (the tracked code is rewritten with SD = 1).
     *  @param channel 0 or 1. */
    void shutdown(uint8_t channel);

    /** @brief Leave shutdown (SD = 0), rewriting the tracked code.
     *  @param channel 0 or 1. */
    void resume(uint8_t channel);

    /** @brief Driver-tracked shutdown state (the chip cannot report it).
     *  @param channel 0 or 1.
     *  @return true if the channel was shut down by this driver. */
    bool isShutdown(uint8_t channel) const;

    /** @brief Restore the power-up preset: both wipers 0x80, SD = 0. */
    void reset();

    /** @brief Change the resistance parameters used by conversions (no bus traffic).
     *  @param rAb    End-to-end resistance RAB in Ω.
     *  @param rWiper Wiper resistance RW in Ω; a negative value keeps the current RW. */
    void setRab(float rAb, float rWiper = -1.0f);
};
