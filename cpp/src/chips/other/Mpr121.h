#pragma once
#include <stdint.h>
#include "../../connection/Connection.h"

/** @brief MPR121 proximity capacitive touch sensor controller — minimal interface.
 *
 * Provides 12-electrode touch/release detection with no configuration
 * beyond the connection. Performs soft reset, applies default
 * touch/release thresholds, enables the chip's automatic CDC/CDT
 * configuration, and enters Run Mode on all 12 electrodes at
 * construction.
 *
 * Default configuration (written at construction):
 * - Touch threshold = 12 for each of ELE0..ELE11
 * - Release threshold = 6 for each of ELE0..ELE11
 * - MHDR = NHDR = MHDF = NHDF = 1 (baseline filter defaults)
 * - CDC_CONFIG = 0x10 (16 µA global CDC; FFI = 6 samples)
 * - CDT_CONFIG = 0x24 (CDT = 1 µS, ESI = 16 ms sample interval)
 * - AUTOCONFIG0 = 0x0B (FFI=00, BVA=10, ARE=1, ACE=1)
 * - USL = 0xC9, TL = 0xB4, LSL = 0x82 (3.3 V VDD)
 * - ECR = 0x8C (CL=10, ELEPROX_EN=00, ELE_EN=12 -> all 12)
 *
 * I²C address: 0x5A (default) / 0x5B / 0x5C / 0x5D (selected by ADDR pin).
 *
 * @param connection Configured I2C connection pointing at the device.
 */
class MPR121Minimal {
public:
    MPR121Minimal(Connection& connection);

    /** @brief Read the 12-bit electrode touch bitmask.
     *
     *  Reads ELE0_7_TOUCH and ELE8_PROX_TOUCH as a coherent two-byte
     *  snapshot from register 0x00; ELEPROX is masked out.
     *
     *  @return 12-bit bitmask; bit n = 1 if ELEn is currently touched.
     */
    uint16_t touched();

    /** @brief Check whether a single electrode is currently touched.
     *  @param electrode Electrode index 0-11.
     *  @return True if ELE_electrode is currently touched.
     */
    bool is_touched(uint8_t electrode);

protected:
    static constexpr uint8_t REG_ELE0_7_TOUCH  = 0x00;
    static constexpr uint8_t REG_ELE8_PROX_TCH = 0x01;
    static constexpr uint8_t REG_ELE0_7_OOR    = 0x02;
    static constexpr uint8_t REG_MHDR          = 0x2B;
    static constexpr uint8_t REG_NHDR          = 0x2C;
    static constexpr uint8_t REG_MHDF          = 0x2F;
    static constexpr uint8_t REG_NHDF          = 0x30;
    static constexpr uint8_t REG_E0TTH         = 0x41;
    static constexpr uint8_t REG_E0RTH         = 0x42;
    static constexpr uint8_t REG_EPROXTTH      = 0x59;
    static constexpr uint8_t REG_EPROXRTH      = 0x5A;
    static constexpr uint8_t REG_DEBOUNCE      = 0x5B;
    static constexpr uint8_t REG_CDC_CONFIG    = 0x5C;
    static constexpr uint8_t REG_CDT_CONFIG    = 0x5D;
    static constexpr uint8_t REG_ECR           = 0x5E;
    static constexpr uint8_t REG_AUTOCONFIG0   = 0x7B;
    static constexpr uint8_t REG_AUTOCONFIG1   = 0x7C;
    static constexpr uint8_t REG_USL           = 0x7D;
    static constexpr uint8_t REG_LSL           = 0x7E;
    static constexpr uint8_t REG_TL            = 0x7F;
    static constexpr uint8_t REG_SRST          = 0x80;

    static constexpr uint8_t SOFT_RESET_KEY     = 0x63;
    static constexpr uint8_t TOUCH_DEFAULT      = 12;
    static constexpr uint8_t RELEASE_DEFAULT    = 6;
    static constexpr uint8_t CDC_CONFIG_DEFAULT = 0x10;
    static constexpr uint8_t CDT_CONFIG_DEFAULT = 0x24;
    static constexpr uint8_t AUTOCONFIG0_DEFAULT = 0x0B;
    static constexpr uint8_t ECR_DEFAULT         = 0x8C;
    static constexpr uint8_t USL_3V3 = 0xC9;
    static constexpr uint8_t TL_3V3  = 0xB4;
    static constexpr uint8_t LSL_3V3 = 0x82;

    void _reset();
    void _write_reg(uint8_t reg, uint8_t value);
    uint8_t _read_reg(uint8_t reg);
    uint16_t _read_reg16(uint8_t reg);

    Connection& _connection;
};

/** @brief MPR121 full interface — extends MPR121Minimal.
 *
 * Adds explicit Stop/Run control, per-electrode and per-proximity
 * threshold configuration, filtered-data and baseline access, baseline
 * filter and AFE (sampling) configuration, debounce, autoconfig
 * recomputation, OOR status, and over-current flag clear.
 */
class MPR121Full : public MPR121Minimal {
public:
    MPR121Full(Connection& connection);

    static constexpr uint8_t SOURCE_OOR = 0x04;
    static constexpr uint8_t SOURCE_ARF = 0x02;
    static constexpr uint8_t SOURCE_ACF = 0x01;

    /** @brief Software-reset the chip and re-apply Minimal defaults. */
    void reset();

    /** @brief Enter Stop Mode (ECR=0x00). Required before writing most config registers. */
    void stop();

    /** @brief Enter Run Mode with the given electrode configuration.
     *  @param n_electrodes Number of electrodes to enable 1-12.
     *  @param cl Calibration lock / baseline init 0-3.
     *  @param eleprox_en Proximity enable 0-3.
     */
    void start(uint8_t n_electrodes = 12, uint8_t cl = 2, uint8_t eleprox_en = 0);

    /** @brief Set touch and release thresholds for a single electrode.
     *  @param electrode Electrode index 0-11.
     *  @param touch Touch threshold 0-255.
     *  @param release Release threshold 0-255.
     */
    void configure_thresholds(uint8_t electrode, uint8_t touch, uint8_t release);

    /** @brief Apply the same touch and release thresholds to all 12 electrodes. */
    void configure_all_thresholds(uint8_t touch, uint8_t release);

    /** @brief Set ELEPROX touch and release thresholds. */
    void configure_proximity_thresholds(uint8_t touch, uint8_t release);

    /** @brief Read the 10-bit filtered capacitance data for an electrode.
     *  @param electrode 0-11 for ELE0-ELE11, 12 for ELEPROX.
     *  @return 10-bit value, inversely proportional to capacitance.
     */
    uint16_t filtered(uint8_t electrode);

    /** @brief Read the 10-bit baseline for an electrode.
     *  @param electrode 0-11 for ELE0-ELE11, 12 for ELEPROX.
     *  @return 10-bit baseline value.
     */
    uint16_t baseline(uint8_t electrode);

    /** @brief Write a baseline value (Stop Mode only).
     *  @param electrode 0-11 for ELE0-ELE11, 12 for ELEPROX.
     *  @param value 10-bit value 0-1023 (only the 8 MSBs are stored).
     */
    void set_baseline(uint8_t electrode, uint16_t value);

    /** @brief Read the 13-bit out-of-range bitmask.
     *  @return bits 0-11 = ELE0-ELE11 OOR, bit 12 = ELEPROX OOR.
     */
    uint16_t oor_status();

    /** @brief Set the global baseline filter parameters (Stop Mode). */
    void configure_baseline_filter(uint8_t mhdr, uint8_t nhdr, uint8_t nclr, uint8_t fdlr,
                                   uint8_t mhdf, uint8_t nhdf, uint8_t nclf, uint8_t fdlf,
                                   uint8_t nhdt, uint8_t nclt, uint8_t fdlt);

    /** @brief Set global AFE (sampling) configuration (Stop Mode).
     *  @param cdc Global CDC 0-63 µA.
     *  @param cdt Global CDT 0-7.
     *  @param ffi First Filter Iterations 0-3.
     *  @param sfi Second Filter Iterations 0-3.
     *  @param esi Electrode Sample Interval 0-7.
     */
    void configure_sampling(uint8_t cdc, uint8_t cdt, uint8_t ffi,
                            uint8_t sfi, uint8_t esi);

    /** @brief Set debounce counts (Stop Mode).
     *  @param touch Debounce count for touch 0-7.
     *  @param release Debounce count for release 0-7.
     */
    void configure_debounce(uint8_t touch, uint8_t release);

    /** @brief Compute USL/TL/LSL from VDD and write autoconfig registers (Stop Mode). */
    void configure_autoconfig(uint16_t vdd_mv = 3300, uint8_t retry = 0,
                              bool scts = false, bool are = true, bool ace = true);

    /** @brief Return True if the ELEPROX virtual electrode is touched. */
    bool proximity_touched();

    /** @brief Clear the OVCF bit in register 0x01. */
    void clear_overcurrent();

    /** @brief Enable one of the AUTOCONFIG1-based interrupt sources. */
    void enable_interrupt(uint8_t source);

    /** @brief Disable one of the AUTOCONFIG1-based interrupt sources. */
    void disable_interrupt(uint8_t source);
};
