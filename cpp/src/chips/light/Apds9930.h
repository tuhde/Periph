#pragma once
#include <stdint.h>
#include "../../connection/Connection.h"

/** @brief APDS-9930 digital ambient light and proximity sensor — minimal interface.
 *
 * Provides illuminance (lux, IR-compensated) and raw proximity count with
 * no configuration required beyond the connection. Both engines are
 * enabled at construction with sensible defaults that give stable
 * readings under typical indoor/outdoor lighting.
 *
 * Default configuration (written at construction):
 * - ATIME   = 0xDB (101 ms integration — rejects 50/60 Hz fluorescent flicker)
 * - PTIME   = 0xFF (2.73 ms proximity ADC time, datasheet default)
 * - PPULSE  = 0x08 (8 LED pulses — factory-calibrated for 100 mm range)
 * - CONTROL = 0x20 (PDIODE=Ch1, PDRIVE=100 mA, PGAIN=1x, AGAIN=1x)
 * - ENABLE  = 0x07 (PON + AEN + PEN; wait timer and interrupts disabled)
 *
 * I²C address: 0x39 (fixed).
 *
 * @param connection Configured I2C connection pointing at the device (address 0x39).
 */
class APDS9930Minimal {
public:
    APDS9930Minimal(Connection& connection);

    /** @brief Read the ambient illuminance.
     *
     *  Uses Ch0 (visible + IR) and Ch1 (IR-only) to compensate for the
     *  IR component of ambient light, then applies the open-air lux
     *  coefficients from the datasheet.
     *
     *  @return Illuminance in lux.
     */
    float lux();

    /** @brief Read the proximity ADC count.
     *
     *  Higher counts mean a closer object. Realistically limited to
     *  10 bits (0-1023) at the default PTIME=0xFF (one ADC cycle).
     *
     *  @return Raw 16-bit proximity count.
     */
    uint16_t proximity();

protected:
    static constexpr uint8_t REG_ENABLE   = 0x00;
    static constexpr uint8_t REG_ATIME    = 0x01;
    static constexpr uint8_t REG_PTIME    = 0x02;
    static constexpr uint8_t REG_WTIME    = 0x03;
    static constexpr uint8_t REG_AILTL    = 0x04;
    static constexpr uint8_t REG_AILTH    = 0x05;
    static constexpr uint8_t REG_AIHTL    = 0x06;
    static constexpr uint8_t REG_AIHTH    = 0x07;
    static constexpr uint8_t REG_PILTL    = 0x08;
    static constexpr uint8_t REG_PILTH    = 0x09;
    static constexpr uint8_t REG_PIHTL    = 0x0A;
    static constexpr uint8_t REG_PIHTH    = 0x0B;
    static constexpr uint8_t REG_PERS     = 0x0C;
    static constexpr uint8_t REG_CONFIG   = 0x0D;
    static constexpr uint8_t REG_PPULSE   = 0x0E;
    static constexpr uint8_t REG_CONTROL  = 0x0F;
    static constexpr uint8_t REG_ID       = 0x12;
    static constexpr uint8_t REG_STATUS   = 0x13;
    static constexpr uint8_t REG_CH0DATAL = 0x14;
    static constexpr uint8_t REG_CH0DATAH = 0x15;
    static constexpr uint8_t REG_CH1DATAL = 0x16;
    static constexpr uint8_t REG_CH1DATAH = 0x17;
    static constexpr uint8_t REG_PDATAL   = 0x18;
    static constexpr uint8_t REG_PDATAH   = 0x19;
    static constexpr uint8_t REG_POFFSET  = 0x1E;

    static constexpr uint8_t CFN_CLEAR_PROXIMITY = 0x05;
    static constexpr uint8_t CFN_CLEAR_ALS       = 0x06;
    static constexpr uint8_t CFN_CLEAR_BOTH      = 0x07;
    static constexpr uint8_t CMD_SPECIAL         = 0xE0;

    static constexpr uint8_t ATIME_DEFAULT   = 0xDB;
    static constexpr uint8_t PTIME_DEFAULT   = 0xFF;
    static constexpr uint8_t PPULSE_DEFAULT  = 0x08;
    static constexpr uint8_t CONTROL_DEFAULT = 0x20;
    static constexpr uint8_t ENABLE_DEFAULT  = 0x07;

    static constexpr uint8_t CMD_WRITE = 0x80;
    static constexpr uint8_t CMD_READ  = 0xA0;

    static uint8_t cmd_write(uint8_t reg) { return CMD_WRITE | (reg & 0x1F); }
    static uint8_t cmd_read(uint8_t reg)  { return CMD_READ  | (reg & 0x1F); }
    static uint8_t cmd_special(uint8_t f) { return CMD_SPECIAL | (f & 0x1F); }

    void _write_reg(uint8_t reg, uint8_t value);
    uint8_t _read_reg(uint8_t reg);
    uint16_t _read_reg16(uint8_t reg);
    void _special(uint8_t function_code);

    Connection& _connection;
};

/** @brief APDS-9930 full interface — extends APDS9930Minimal.
 *
 * Adds ALS/proximity integration-time and gain configuration, raw
 * channel reads, interrupt thresholds with persistence, status decoding,
 * sleep-after-interrupt, and proximity offset compensation.
 */
class APDS9930Full : public APDS9930Minimal {
public:
    APDS9930Full(Connection& connection);

    /** @brief Configure ALS integration time, AGAIN index, and AGL flag.
     *  @param atime ATIME register value 0-255.
     *  @param again ALS gain index 0-3 (0=1x, 1=8x, 2=16x, 3=120x).
     *  @param agl   True to enable the AGL divide-by-6 gain-level bit.
     */
    void configure_als(uint8_t atime = 0xDB, uint8_t again = 0, bool agl = false);

    /** @brief Configure proximity LED pulses, gain, drive, and ADC integration time.
     *  @param ppulse Number of LED pulses 1-255.
     *  @param pgain  Proximity gain index 0-3 (0=1x, 1=2x, 2=4x, 3=8x).
     *  @param pdrive LED drive current index 0-3 (0=100, 1=50, 2=25, 3=12.5 mA).
     *  @param pdl    True to enable PDL (reduces drive to 1/9 of PDRIVE).
     *  @param ptime  PTIME register value 0-255.
     */
    void configure_proximity(uint8_t ppulse = 8, uint8_t pgain = 0,
                             uint8_t pdrive = 0, bool pdl = false,
                             uint8_t ptime = 0xFF);

    /** @brief Configure wait time and enable the wait timer.
     *  @param wtime WTIME register value 0-255.
     *  @param wlong True to enable WLONG (multiplies wait by 12x).
     */
    void configure_wait(uint8_t wtime = 0xFF, bool wlong = false);

    /** @brief Clear WEN in ENABLE (disable the wait timer). */
    void disable_wait();

    /** @brief Read the raw Ch0 (visible + IR) ADC count.
     *  @return 16-bit ADC count.
     */
    uint16_t ch0();

    /** @brief Read the raw Ch1 (IR-only) ADC count.
     *  @return 16-bit ADC count.
     */
    uint16_t ch1();

    /** @brief Read the STATUS register decoded into named fields.
     *  @param avalid Output: AVALID bit.
     *  @param pvalid Output: PVALID bit.
     *  @param psat   Output: PSAT bit.
     *  @param aint   Output: AINT bit.
     *  @param pint   Output: PINT bit.
     */
    void status(bool& avalid, bool& pvalid, bool& psat, bool& aint, bool& pint);

    /** @brief Set ALS interrupt thresholds and enable AIEN.
     *
     *  Thresholds are evaluated against raw Ch0 counts, not lux.
     *
     *  @param low  16-bit low threshold.
     *  @param high 16-bit high threshold.
     *  @param persistence APERS value 0-15 (0=every, 1, 2, 3, 5, 10, 15, 20,
     *                    25, 30, 35, 40, 45, 50, 55, 60 consecutive).
     */
    void set_als_thresholds(uint16_t low, uint16_t high, uint8_t persistence = 1);

    /** @brief Set proximity interrupt thresholds and enable PIEN.
     *  @param low  16-bit low threshold.
     *  @param high 16-bit high threshold.
     *  @param persistence PPERS value 0-15 (0=every, 1-15=N consecutive).
     */
    void set_proximity_thresholds(uint16_t low, uint16_t high, uint8_t persistence = 1);

    /** @brief Clear pending interrupt(s).
     *  @param channel 0=both, 1=ALS, 2=proximity.
     */
    void clear_interrupt(uint8_t channel = 0);

    /** @brief Set the proximity offset (sign-magnitude).
     *  @param offset Signed integer -127..+127 (positive shifts data up).
     */
    void set_proximity_offset(int8_t offset);

    /** @brief Enable or disable SAI (sleep after interrupt).
     *  @param enable True to set SAI, False to clear it.
     */
    void sleep_after_interrupt(bool enable);
};