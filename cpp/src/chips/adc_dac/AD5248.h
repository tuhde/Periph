#pragma once
#include <stdint.h>
#include "../../connection/RegisterConnection.h"

/** @brief AD5248 dual 256-position I²C digital rheostat — minimal interface.
 *
 * Sets and reads back the wiper position (0–255) or the wiper-to-B resistance of
 * either channel. The chip has no register map: the instruction byte (channel
 * select bit A0 in bit 7, shutdown bit SD in bit 6) plays the role of the register
 * address, so the driver calls connection.read/write with the instruction byte.
 *
 * Default behaviour:
 * - RAB = 10 kΩ, RW = 160 Ω; both wipers at the power-up midscale (0x80)
 * - Both channels assumed active (not in shutdown); no I²C traffic at construction
 *
 * An invalid channel (not 0 or 1) calls abort(), matching the library's convention
 * for unrecoverable misuse in exception-free builds.
 */
class AD5248Minimal {
public:
    /**
     * @brief Construct the driver; performs no I²C traffic.
     * @param connection  RegisterConnection (I²C) bound to the chip's address (0x2C–0x2F).
     * @param r_ab        End-to-end resistance RAB in ohms (2500, 10000, 50000 or 100000).
     * @param r_wiper     Wiper resistance RW in ohms (typically 160 Ω at 5 V).
     */
    AD5248Minimal(RegisterConnection& connection, float r_ab = 10000.0f, float r_wiper = 160.0f);

    /**
     * @brief Set the wiper position of a channel.
     *
     * Clamps the code to [0, 255]. On a shut-down channel the RDAC is updated and
     * the channel stays in shutdown.
     * @param channel  Channel index, 0 or 1.
     * @param code     Wiper position 0–255 (0 = at B, 255 = at A).
     */
    void set_wiper(uint8_t channel, int code);

    /**
     * @brief Read the wiper position of a channel.
     *
     * Sends the channel's instruction byte alone, then reads one byte.
     * @param channel  Channel index, 0 or 1.
     * @return Wiper position 0–255.
     */
    uint8_t get_wiper(uint8_t channel);

    /**
     * @brief Set the W–B resistance to the nearest reachable value.
     * @param channel  Channel index, 0 or 1.
     * @param ohms     Target wiper-to-B resistance in ohms (clamped to the reachable range).
     */
    void set_resistance(uint8_t channel, float ohms);

    /**
     * @brief Read the W–B resistance for the current wiper position.
     * @param channel  Channel index, 0 or 1.
     * @return Resistance in ohms, D / 256 * RAB + 2 * RW.
     */
    float get_resistance(uint8_t channel);

protected:
    static constexpr uint8_t SD_BIT = 0x40;

    RegisterConnection& _connection;
    float _r_ab;
    float _r_wiper;
    bool  _shutdown[2];

    void    _check_channel(uint8_t channel);
    uint8_t _instruction(uint8_t channel);
};

/** @brief AD5248 full interface — extends AD5248Minimal with ratio, increment, shutdown and reset. */
class AD5248Full : public AD5248Minimal {
public:
    /**
     * @brief Construct the driver; performs no I²C traffic.
     * @param connection  RegisterConnection (I²C) bound to the chip's address (0x2C–0x2F).
     * @param r_ab        End-to-end resistance RAB in ohms.
     * @param r_wiper     Wiper resistance RW in ohms.
     */
    AD5248Full(RegisterConnection& connection, float r_ab = 10000.0f, float r_wiper = 160.0f)
        : AD5248Minimal(connection, r_ab, r_wiper) {}

    /**
     * @brief Write both wipers (two transactions).
     * @param code1  Channel 0 wiper position 0–255.
     * @param code2  Channel 1 wiper position 0–255.
     */
    void set_both(int code1, int code2);

    /**
     * @brief Set the wiper to a divider ratio of the full scale.
     *
     * Wiper code = round(ratio × 256) clamped to 255. Only meaningful if terminal A
     * is wired externally; the AD5248 does not pin out A, so prefer set_resistance().
     * @param channel  Channel index, 0 or 1.
     * @param ratio    Ratio 0.0–1.0.
     */
    void set_ratio(uint8_t channel, float ratio);

    /**
     * @brief Read the wiper position as a ratio.
     * @param channel  Channel index, 0 or 1.
     * @return code / 256.
     */
    float get_ratio(uint8_t channel);

    /**
     * @brief Move the wiper by a number of steps.
     * @param channel  Channel index, 0 or 1.
     * @param steps    Steps to add (negative to decrement).
     * @return New wiper code, clamped to 0–255.
     */
    uint8_t increment(uint8_t channel, int steps = 1);

    /**
     * @brief Put a channel in shutdown (terminal A open, wiper shorted to B); RDAC is kept.
     * @param channel  Channel index, 0 or 1.
     */
    void shutdown(uint8_t channel);

    /**
     * @brief Leave shutdown; the RDAC contents are restored to the wiper.
     * @param channel  Channel index, 0 or 1.
     */
    void resume(uint8_t channel);

    /**
     * @brief Return the driver-tracked shutdown state (the chip cannot be read back).
     * @param channel  Channel index, 0 or 1.
     * @return true if the channel was shut down by this driver.
     */
    bool is_shutdown(uint8_t channel);

    /** @brief Write midscale (0x80) to both channels with SD = 0 (power-up preset). */
    void reset();

    /**
     * @brief Change the resistance model used by the conversions (no I²C traffic).
     * @param r_ab     End-to-end resistance RAB in ohms.
     * @param r_wiper  Wiper resistance RW in ohms; pass a negative value to keep the current RW.
     */
    void set_rab(float r_ab, float r_wiper = -1.0f);
};
