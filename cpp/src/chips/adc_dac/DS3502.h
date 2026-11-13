#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/RegisterConnection.h"

/** @brief DS3502 7-bit nonvolatile high-voltage I²C digital potentiometer (Maxim) — minimal interface.
 *
 *  A single 128-position potentiometer (10 kΩ end to end, ±20 %) whose terminals RH / RL / RW
 *  accept up to 15.5 V (V+ = 4.5–15.5 V) while the logic runs from VCC = 2.5–5.5 V. The wiper
 *  position lives in the Wiper Register (WR, SRAM); the Initial Value Register (IVR, EEPROM)
 *  holds the power-up position (factory default 0x40). The I²C address is 0x28 (A1 = A0 = GND)
 *  to 0x2B (A1 = A0 = VCC).
 *
 *  Two registers exist: 0x00 is WR (and, depending on the MODE bit of CR, also IVR; reads always
 *  return WR) and 0x02 is the Control Register whose bit 7 selects MODE (1 = writes to 0x00 only
 *  change WR). The constructor runs the initialization sequence (CR = 0x80 with one retry after
 *  3 ms, then reads WR), so routine wiper writes never wear the EEPROM.
 *
 *  @param connection RegisterConnection (I²C, 1-byte register address) pointing at the device.
 *  @param vHigh      Voltage applied to RH, in volts (default 1.0).
 *  @param vLow       Voltage applied to RL, in volts (default 0.0).
 */
class DS3502Minimal {
public:
    /** @brief 7-bit I²C address with A1 = A0 = GND. */
    static constexpr uint8_t I2C_ADDRESS = 0x28;

    explicit DS3502Minimal(RegisterConnection& connection, float vHigh = 1.0f, float vLow = 0.0f);

    /** @brief Set the wiper position (volatile, SRAM only).
     *  @param code Wiper position 0–127 (clamped); 0 = at RL, 127 = at RH. */
    void setWiper(int code);

    /** @brief Read back the wiper position (WR).
     *  @return Wiper position 0–127. */
    uint8_t getWiper();

    /** @brief Set the unloaded RW output voltage to the nearest reachable code.
     *  @param volts Target voltage in volts (clamped to the range between vLow and vHigh). */
    void setVoltage(float volts);

    /** @brief Read the unloaded RW voltage for the current wiper code.
     *  @return Voltage in volts, `vLow + (vHigh − vLow) · code / 127`. */
    float getVoltage();

protected:
    static constexpr uint8_t REG_WR  = 0x00;
    static constexpr uint8_t REG_CR  = 0x02;
    static constexpr uint8_t CR_MODE_SRAM   = 0x80;
    static constexpr uint8_t CR_MODE_EEPROM = 0x00;
    static constexpr uint8_t MAX_CODE = 127;

    RegisterConnection& _connection;
    float _vHigh;
    float _vLow;
    uint8_t _code;

    static uint8_t _clampCode(int code);
    static void _delayMs(unsigned ms);
    uint8_t _readReg(uint8_t reg);
    void _writeReg(uint8_t reg, uint8_t value);
    void _writeCode(int code);
    uint8_t _readCode();
};

/** @brief DS3502 full interface — extends Minimal with ratio / resistance helpers, increment,
 *  EEPROM storage of the power-up value, MODE control and acknowledge polling.
 *
 *  @param connection RegisterConnection (I²C, 1-byte register address) pointing at the device.
 *  @param vHigh      Voltage applied to RH, in volts.
 *  @param vLow       Voltage applied to RL, in volts.
 */
class DS3502Full : public DS3502Minimal {
public:
    explicit DS3502Full(RegisterConnection& connection, float vHigh = 1.0f, float vLow = 0.0f);

    /** @brief Set the wiper by divider ratio V(RW−RL)/V(RH−RL).
     *  @param ratio 0.0–1.0; the code is round(ratio × 127). */
    void setRatio(float ratio);

    /** @brief Read the divider ratio.
     *  @return `code / 127`. */
    float getRatio();

    /** @brief Nominal wiper resistance for the current code (RTOTAL = 10 kΩ ±20 %).
     *  @param terminal 'L' for wiper-to-RL, 'H' for wiper-to-RH.
     *  @return Resistance in Ω (approximate; excludes the wiper resistance), or -1 for a bad terminal. */
    float getResistance(char terminal = 'L');

    /** @brief Move the wiper relative to its current position (SRAM only).
     *  @param steps Signed step count (negative to decrement).
     *  @return The new wiper code, clamped to 0–127. */
    uint8_t increment(int steps = 1);

    /** @brief Write the Initial Value Register (EEPROM power-up position).
     *
     *  Sets MODE = 0, writes WR / IVR, waits for the EEPROM write (acknowledge polling, ≤ 20 ms)
     *  and restores MODE = 1, also when the wait times out. The wiper moves to @p code. Each call
     *  counts toward the EEPROM endurance (50 000 writes at 70 °C, 200 000 at 25 °C).
     *  @param code Position 0–127 to store; a negative value stores the current wiper position.
     *  @return false if the EEPROM write did not finish in time. */
    bool storeWiper(int code = -1);

    /** @brief Select what a write to the wiper register updates (advanced; storeWiper handles it).
     *  @param eeprom true for MODE = 0 (writes also program the IVR), false for MODE = 1 (SRAM only). */
    void setMode(bool eeprom);

    /** @brief Read the MODE bit of the Control Register.
     *  @return true if MODE = 0 (writes reach the EEPROM). */
    bool getMode();

    /** @brief Acknowledge polling: repeat a read of CR until the chip ACKs.
     *  @param timeoutMs Maximum time to wait, in milliseconds.
     *  @return false if the chip still NACKs after @p timeoutMs. */
    bool waitReady(float timeoutMs = 30.0f);

    /** @brief Change the RH / RL voltages used by the conversions (no bus traffic).
     *  @param vHigh Voltage applied to RH, in volts.
     *  @param vLow  Voltage applied to RL, in volts. */
    void setEndpoints(float vHigh, float vLow = 0.0f);
};
