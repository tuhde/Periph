#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/Connection.h"

/** @brief CAT5171 256-position I²C digital potentiometer (ON Semiconductor) — minimal interface.
 *
 *  A single 3-terminal potentiometer (A, W, B) usable as a voltage divider or
 *  2-terminal rheostat. The chip has no register map: a write is
 *  `[instruction, data]` where the instruction byte carries the midscale-reset
 *  flag (RS, bit 6) and the shutdown flag (SD, bit 5); a read is a bare
 *  one-byte read returning the wiper register. The driver therefore uses the
 *  plain Connection `write()` / `read()` calls. The I²C address is 0x2C
 *  (AD0 = GND) or 0x2D (AD0 = VDD). The wiper is volatile (power-up midscale 0x80).
 *
 *  Resistances follow `R_WB = D/256 · RAB + RW`. The constructor makes no
 *  bus access.
 *
 *  @param connection Connection (I²C) pointing at the device (0x2C or 0x2D).
 *  @param rAb        End-to-end resistance RAB in Ω (50 kΩ or 100 kΩ part; default 100 kΩ).
 *  @param rWiper     Wiper resistance RW in Ω (typical 50 Ω at 5 V).
 */
class CAT5171Minimal {
public:
    /** @brief 7-bit I²C address with AD0 = GND. */
    static constexpr uint8_t I2C_ADDRESS = 0x2C;
    /** @brief 7-bit I²C address with AD0 = VDD. */
    static constexpr uint8_t I2C_ADDRESS_AD0_HIGH = 0x2D;

    explicit CAT5171Minimal(Connection& connection, float rAb = 100000.0f, float rWiper = 50.0f);

    /** @brief Set the wiper position.
     *  @param code Wiper position 0–255 (clamped). */
    void setWiper(int code);

    /** @brief Read back the wiper position with a single read transaction.
     *  @return Wiper position 0–255. */
    uint8_t getWiper();

    /** @brief Set the W–B resistance to the nearest reachable code.
     *  @param ohms Target W–B resistance in Ω (clamped to the reachable range). */
    void setResistance(float ohms);

    /** @brief Read the W–B resistance for the current wiper code.
     *  @return Resistance in Ω, `D/256 · RAB + RW`. */
    float getResistance();

protected:
    static constexpr uint8_t INSTR_RS  = 0x40;
    static constexpr uint8_t INSTR_SD  = 0x20;
    static constexpr uint8_t MIDSCALE  = 0x80;

    Connection& _connection;
    float _rAb;
    float _rW;
    uint8_t _code;
    bool _sd;

    static uint8_t _clampCode(int code);
    void _write(uint8_t instruction, uint8_t code);
    void _writeCode(int code);
    uint8_t _readCode();
};

/** @brief CAT5171 full interface — extends Minimal with ratio and W–A helpers,
 *  increment/decrement, midscale, shutdown and reset.
 *
 *  The shutdown state is write-only on the chip, so the driver tracks it
 *  (`false` after construction and after reset()).
 *
 *  @param connection Connection (I²C) pointing at the device (0x2C or 0x2D).
 *  @param rAb        End-to-end resistance RAB in Ω.
 *  @param rWiper     Wiper resistance RW in Ω.
 */
class CAT5171Full : public CAT5171Minimal {
public:
    explicit CAT5171Full(Connection& connection, float rAb = 100000.0f, float rWiper = 50.0f);

    /** @brief Set the wiper by divider ratio V(W−B)/V(A−B).
     *  @param ratio 0.0–1.0; the code is round(ratio × 256) clamped to 255. */
    void setRatio(float ratio);

    /** @brief Read the divider ratio.
     *  @return `code / 256`. */
    float getRatio();

    /** @brief Set the W–A resistance (rheostat mode, B open) to the nearest code.
     *  @param ohms Target W–A resistance in Ω. */
    void setResistanceWa(float ohms);

    /** @brief Read the W–A resistance for the current wiper code.
     *  @return Resistance in Ω, `(256 − D)/256 · RAB + RW`. */
    float getResistanceWa();

    /** @brief Move the wiper relative to its current position.
     *  @param steps Signed step count (negative to decrement).
     *  @return The new wiper code, clamped to 0–255. */
    uint8_t increment(int steps = 1);

    /** @brief Move the wiper to midscale (0x80) with the RS instruction bit; SD is kept if shut down. */
    void midscale();

    /** @brief Shut down: terminal A opens and the wiper shorts to B.
     *  The wiper register is kept (the tracked code is rewritten with SD = 1). */
    void shutdown();

    /** @brief Leave shutdown (SD = 0), rewriting the tracked code. */
    void resume();

    /** @brief Driver-tracked shutdown state (the chip cannot report it).
     *  @return true if the device was shut down by this driver. */
    bool isShutdown() const;

    /** @brief Midscale reset (RS = 1, SD = 0): wiper to 0x80, shutdown cleared. */
    void reset();

    /** @brief Change the resistance parameters used by conversions (no bus traffic).
     *  @param rAb    End-to-end resistance RAB in Ω.
     *  @param rWiper Wiper resistance RW in Ω; a negative value keeps the current RW. */
    void setRab(float rAb, float rWiper = -1.0f);
};
