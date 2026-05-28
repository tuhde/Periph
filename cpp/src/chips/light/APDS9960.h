#pragma once
#include <stdint.h>
#include "../../transport/Transport.h"

/** @brief APDS-9960 digital proximity, ambient light, RGB and gesture sensor — minimal interface.
 *
 * Provides ambient light and color (RGBC) readings with no configuration
 * beyond the transport. The ALS/Color engine is enabled at construction
 * with sensible defaults.
 *
 * Default configuration (written at construction):
 * - ATIME = 0xB6 (72 cycles, ~200 ms integration, max count 65535)
 * - AGAIN = 1 (4x ALS gain)
 * - CONFIG2 = 0x01 (LED_BOOST=100%, reserved bit 0 set)
 * - PON + AEN enabled; no wait, proximity, gesture, or interrupts
 *
 * @param transport Configured I2C transport pointing at the device (address 0x39).
 */
class APDS9960Minimal {
public:
    APDS9960Minimal(Transport& transport);

    /** @brief Read the clear (unfiltered) channel.
     *  @return Raw clear channel count, 0-65535.
     */
    uint16_t color_clear();

    /** @brief Read the red channel.
     *  @return Raw red channel count, 0-65535.
     */
    uint16_t color_red();

    /** @brief Read the green channel.
     *  @return Raw green channel count, 0-65535.
     */
    uint16_t color_green();

    /** @brief Read the blue channel.
     *  @return Raw blue channel count, 0-65535.
     */
    uint16_t color_blue();

    /** @brief Read all four RGBC channels in one burst.
     *
     *  Reading CDATAL at 0x94 atomically latches all eight bytes 0x94-0x9B.
     *
     *  @param clear Output: clear channel count.
     *  @param red   Output: red channel count.
     *  @param green Output: green channel count.
     *  @param blue  Output: blue channel count.
     */
    void color(uint16_t& clear, uint16_t& red, uint16_t& green, uint16_t& blue);

protected:
    static constexpr uint8_t REG_ENABLE     = 0x80;
    static constexpr uint8_t REG_ATIME      = 0x81;
    static constexpr uint8_t REG_WTIME      = 0x83;
    static constexpr uint8_t REG_AILTL      = 0x84;
    static constexpr uint8_t REG_AILTH      = 0x85;
    static constexpr uint8_t REG_AIHTL      = 0x86;
    static constexpr uint8_t REG_AIHTH      = 0x87;
    static constexpr uint8_t REG_PILT       = 0x89;
    static constexpr uint8_t REG_PIHT       = 0x8B;
    static constexpr uint8_t REG_PERS       = 0x8C;
    static constexpr uint8_t REG_CONFIG1    = 0x8D;
    static constexpr uint8_t REG_PPULSE     = 0x8E;
    static constexpr uint8_t REG_CONTROL    = 0x8F;
    static constexpr uint8_t REG_CONFIG2    = 0x90;
    static constexpr uint8_t REG_ID         = 0x92;
    static constexpr uint8_t REG_STATUS     = 0x93;
    static constexpr uint8_t REG_CDATAL     = 0x94;
    static constexpr uint8_t REG_RDATAL     = 0x96;
    static constexpr uint8_t REG_GDATAL     = 0x98;
    static constexpr uint8_t REG_BDATAL     = 0x9A;
    static constexpr uint8_t REG_PDATA      = 0x9C;
    static constexpr uint8_t REG_POFFSET_UR = 0x9D;
    static constexpr uint8_t REG_POFFSET_DL = 0x9E;
    static constexpr uint8_t REG_CONFIG3    = 0x9F;
    static constexpr uint8_t REG_GPENTH     = 0xA0;
    static constexpr uint8_t REG_GEXTH      = 0xA1;
    static constexpr uint8_t REG_GCONF1     = 0xA2;
    static constexpr uint8_t REG_GCONF2     = 0xA3;
    static constexpr uint8_t REG_GOFFSET_U  = 0xA4;
    static constexpr uint8_t REG_GOFFSET_D  = 0xA5;
    static constexpr uint8_t REG_GPULSE     = 0xA6;
    static constexpr uint8_t REG_GOFFSET_L  = 0xA7;
    static constexpr uint8_t REG_GOFFSET_R  = 0xA9;
    static constexpr uint8_t REG_GCONF3     = 0xAA;
    static constexpr uint8_t REG_GCONF4     = 0xAB;
    static constexpr uint8_t REG_GFLVL      = 0xAE;
    static constexpr uint8_t REG_GSTATUS    = 0xAF;
    static constexpr uint8_t REG_PICLEAR    = 0xE5;
    static constexpr uint8_t REG_CICLEAR    = 0xE6;
    static constexpr uint8_t REG_AICLEAR    = 0xE7;
    static constexpr uint8_t REG_GFIFO_U    = 0xFC;

    static constexpr uint8_t ATIME_DEFAULT   = 0xB6;
    static constexpr uint8_t CONTROL_DEFAULT = 0x01;
    static constexpr uint8_t CONFIG2_DEFAULT = 0x01;

    Transport& _transport;

    void     _write_reg(uint8_t reg, uint8_t value);
    uint8_t  _read_reg(uint8_t reg);
    uint16_t _read_reg16_le(uint8_t reg);
};

/** @brief APDS-9960 full interface — extends APDS9960Minimal with proximity, gesture, and configuration.
 *
 * Adds proximity detection, gesture engine, wait engine, threshold and
 * interrupt configuration, status queries, and device identification.
 *
 * @param transport Configured I2C transport pointing at the device (address 0x39).
 */
class APDS9960Full : public APDS9960Minimal {
public:
    APDS9960Full(Transport& transport);

    /** @brief Enable or disable the proximity engine.
     *  @param enabled true to enable PEN, false to disable.
     */
    void enable_proximity(bool enabled);

    /** @brief Read the proximity count.
     *  @return Proximity count 0-255; higher means closer.
     */
    uint8_t proximity();

    /** @brief Enable or disable the wait engine.
     *  @param enabled true to enable WEN, false to disable.
     */
    void enable_wait(bool enabled);

    /** @brief Configure the wait time between ALS/proximity cycles.
     *  @param wtime WTIME register value 0-255.
     *  @param wlong true to enable WLONG 12x multiplier.
     */
    void configure_wait(uint8_t wtime, bool wlong = false);

    /** @brief Configure ALS integration time and gain.
     *  @param atime ATIME register value 0-255.
     *  @param again ALS gain 0-3 (0=1x, 1=4x, 2=16x, 3=64x).
     */
    void configure_als(uint8_t atime, uint8_t again);

    /** @brief Configure proximity LED drive, gain, pulse count and length.
     *  @param ldrive LED drive strength 0-3.
     *  @param pgain  Proximity gain 0-3.
     *  @param ppulse Pulse count minus 1, 0-63.
     *  @param pplen  Pulse length 0-3.
     */
    void configure_proximity_led(uint8_t ldrive, uint8_t pgain, uint8_t ppulse, uint8_t pplen);

    /** @brief Set additional LED current boost.
     *  @param boost LED_BOOST 0-3 (0=100%, 1=150%, 2=200%, 3=300%).
     */
    void set_led_boost(uint8_t boost);

    /** @brief Set ALS interrupt thresholds.
     *  @param low  Low threshold 0-65535.
     *  @param high High threshold 0-65535.
     */
    void als_threshold(uint16_t low, uint16_t high);

    /** @brief Set proximity interrupt thresholds.
     *  @param low  Low threshold 0-255.
     *  @param high High threshold 0-255.
     */
    void proximity_threshold(uint8_t low, uint8_t high);

    /** @brief Set interrupt persistence filters.
     *  @param ppers Proximity persistence 0-15.
     *  @param apers ALS persistence 0-15.
     */
    void set_persistence(uint8_t ppers, uint8_t apers);

    /** @brief Enable or disable ALS interrupt.
     *  @param enabled true to enable AIEN, false to disable.
     */
    void enable_als_interrupt(bool enabled);

    /** @brief Enable or disable proximity interrupt.
     *  @param enabled true to enable PIEN, false to disable.
     */
    void enable_proximity_interrupt(bool enabled);

    /** @brief Clear the proximity interrupt via address-only write to PICLEAR. */
    void clear_proximity_interrupt();

    /** @brief Clear the ALS/color interrupt via address-only write to CICLEAR. */
    void clear_als_interrupt();

    /** @brief Clear all non-gesture interrupts via address-only write to AICLEAR. */
    void clear_all_interrupts();

    /** @brief Set proximity offset for UP/RIGHT and DOWN/LEFT photodiodes.
     *  @param ur UP/RIGHT offset -127 to +127 (sign-magnitude).
     *  @param dl DOWN/LEFT offset -127 to +127 (sign-magnitude).
     */
    void set_proximity_offset(int8_t ur, int8_t dl);

    /** @brief Mask individual photodiodes in proximity detection.
     *  @param u true to mask UP.
     *  @param d true to mask DOWN.
     *  @param l true to mask LEFT.
     *  @param r true to mask RIGHT.
     */
    void set_proximity_mask(bool u, bool d, bool l, bool r);

    /** @brief Enable or disable the gesture engine.
     *  @param enabled true to enable GEN and set GMODE, false to disable.
     */
    void enable_gesture(bool enabled);

    /** @brief Configure gesture engine parameters.
     *  @param ggain   Gesture gain 0-3.
     *  @param gldrive Gesture LED drive 0-3.
     *  @param gpulse  Gesture pulse count minus 1, 0-63.
     *  @param gplen   Gesture pulse length 0-3.
     *  @param gwtime  Gesture wait time 0-7.
     *  @param gpenth  Gesture proximity entry threshold 0-255.
     *  @param gexth   Gesture exit threshold 0-255.
     */
    void configure_gesture(uint8_t ggain, uint8_t gldrive, uint8_t gpulse,
                           uint8_t gplen, uint8_t gwtime, uint8_t gpenth, uint8_t gexth);

    /** @brief Check if gesture data is available in the FIFO.
     *  @return true if GSTATUS.GVALID is set.
     */
    bool gesture_available();

    /** @brief Read gesture datasets from the FIFO.
     *  @param buf     Output buffer for (U, D, L, R) datasets (4 bytes each).
     *  @param max_len Maximum number of datasets the buffer can hold.
     *  @return Number of datasets read.
     */
    uint8_t read_gesture_fifo(uint8_t* buf, uint8_t max_len);

    /** @brief Read the number of datasets in the gesture FIFO.
     *  @return Number of 4-byte datasets currently in FIFO.
     */
    uint8_t gesture_fifo_level();

    /** @brief Clear the gesture FIFO by setting GFIFO_CLR in GCONF4. */
    void clear_gesture_fifo();

    /** @brief Enable or disable gesture interrupt.
     *  @param enabled true to enable GIEN, false to disable.
     */
    void enable_gesture_interrupt(bool enabled);

    /** @brief Read the raw STATUS register.
     *  @return Raw STATUS byte.
     */
    uint8_t status();

    /** @brief Check if ALS/color data is valid.
     *  @return true if STATUS.AVALID is set.
     */
    bool is_als_valid();

    /** @brief Check if proximity data is valid.
     *  @return true if STATUS.PVALID is set.
     */
    bool is_proximity_valid();

    /** @brief Check if the clear photodiode is saturated.
     *  @return true if STATUS.CPSAT is set.
     */
    bool is_als_saturated();

    /** @brief Check if analog saturation occurred during proximity.
     *  @return true if STATUS.PGSAT is set.
     */
    bool is_proximity_saturated();

    /** @brief Read the device ID register.
     *  @return ID register value (expect 0xAB).
     */
    uint8_t chip_id();
};
