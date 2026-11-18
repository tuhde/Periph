#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/RegisterConnection.h"
#include "../../connection/InputPin.h"

/** @brief MAX17048 / MAX17049 lithium fuel gauge (ModelGauge) — minimal interface.
 *
 * Reports the cell voltage and the state of charge. The constructor makes no bus access;
 * the chip runs with its power-on defaults (hibernate thresholds 0x8030, RCOMP 0x97, empty
 * alert at 4 %, voltage alert window disabled, on-chip default battery model). The first SOC
 * result is valid about 1 s after power-up.
 *
 * @param connection RegisterConnection (I²C, address 0x36).
 * @param cells      1 for the MAX17048 (default), 2 for the MAX17049; scales the voltage.
 */
class Max17048Minimal {
public:
    explicit Max17048Minimal(RegisterConnection& connection, uint8_t cells = 1);

    /** @brief Read the cell voltage.
     *  @return Voltage in volts ((VCELL >> 4) × 1.25 mV × cells).
     */
    float voltage();

    /** @brief Read the state of charge.
     *  @return State of charge in %, clamped to 0.0-100.0 (SOC / 256).
     */
    float percent();

protected:
    static constexpr uint8_t REG_VCELL   = 0x02;
    static constexpr uint8_t REG_SOC     = 0x04;
    static constexpr uint8_t REG_MODE    = 0x06;
    static constexpr uint8_t REG_VERSION = 0x08;
    static constexpr uint8_t REG_HIBRT   = 0x0A;
    static constexpr uint8_t REG_CONFIG  = 0x0C;
    static constexpr uint8_t REG_VALRT   = 0x14;
    static constexpr uint8_t REG_CRATE   = 0x16;
    static constexpr uint8_t REG_VRESET  = 0x18;
    static constexpr uint8_t REG_STATUS  = 0x1A;
    static constexpr uint8_t REG_CMD     = 0xFE;

    uint16_t _read_word(uint8_t reg);
    void     _write_word(uint8_t reg, uint16_t value);

    RegisterConnection& _connection;
    uint8_t _cells;
};

/** @brief MAX17048 / MAX17049 full interface — alerts, RCOMP, hibernate, sleep, reset.
 *
 * Interrupt source constants (callback / pollInterrupt / enableInterrupt): SOURCE_RESET,
 * SOURCE_OVERVOLTAGE, SOURCE_UNDERVOLTAGE, SOURCE_VRESET, SOURCE_LOW_SOC, SOURCE_SOC_CHANGE.
 * ALRT delivery uses the InputPin passed to onInterrupt() (or connection.intPin()).
 */
class Max17048Full : public Max17048Minimal {
public:
    static constexpr uint16_t SOURCE_RESET        = 0x0100;
    static constexpr uint16_t SOURCE_OVERVOLTAGE  = 0x0200;
    static constexpr uint16_t SOURCE_UNDERVOLTAGE = 0x0400;
    static constexpr uint16_t SOURCE_VRESET       = 0x0800;
    static constexpr uint16_t SOURCE_LOW_SOC      = 0x1000;
    static constexpr uint16_t SOURCE_SOC_CHANGE   = 0x2000;

    explicit Max17048Full(RegisterConnection& connection, uint8_t cells = 1);

    /** @brief Read the raw VCELL register. @return 16-bit value (12-bit result in bits 15:4). */
    uint16_t raw_voltage();
    /** @brief Read the raw SOC register. @return 16-bit value (high byte = 1 %). */
    uint16_t raw_soc();
    /** @brief Read the charge/discharge rate. @return Rate in %/h (signed CRATE × 0.208), positive while charging. */
    float charge_rate();
    /** @brief Read the VERSION register. @return Production version, 0x001x. */
    uint16_t version();
    /** @brief Read the factory-programmed ID. @return Low byte of VRESET/ID. */
    uint8_t chip_id();

    /** @brief Restart the SOC estimate from the present voltage (MODE.QuickStart); only if the first SOC is clearly wrong. */
    void quick_start();
    /** @brief Power-on reset (CMD ← 0x5400); tolerates the missing ACK and waits 1 s. */
    void reset();

    /** @brief Write RCOMP (CONFIG bits 15:8, read-modify-write). @param value 0-255 (default 0x97). */
    void set_rcomp(uint8_t value);
    /** @brief Read RCOMP. */
    uint8_t get_rcomp();
    /** @brief Set RCOMP from the datasheet formula with the default coefficients (RCOMP0 = 0x97).
     *  @param celsius Cell temperature in °C. */
    void compensate_temperature(float celsius);

    /** @brief Set the empty-alert threshold (CONFIG.ATHD = 32 − percent). @param percent 1-32 (default 4). */
    void set_alert_soc(uint8_t percent);
    /** @brief Read the empty-alert threshold in % (1-32). */
    uint8_t get_alert_soc();
    /** @brief Enable the alert on a SOC change of at least 1 % (CONFIG.ALSC). */
    void set_soc_change_alert(bool enabled);

    /** @brief Set the voltage alert window in 20 mV steps (VALRT).
     *  @param min_v Under-voltage threshold in V; 0 disables it.
     *  @param max_v Over-voltage threshold in V; 5.1 disables it.
     *  @return false (and nothing written) if min_v > max_v. */
    bool set_voltage_alert(float min_v, float max_v);
    /** @brief Read the voltage alert window in volts. */
    void get_voltage_alert(float* min_v, float* max_v);

    /** @brief Set the reset voltage (VRESET bits 15:9, 40 mV steps). @param volts 0.0-5.08 V. */
    void set_reset_voltage(float volts);
    /** @brief Read the reset voltage in volts. */
    float get_reset_voltage();
    /** @brief Let a voltage-reset event assert ALRT (STATUS.ENVR). */
    void enable_voltage_reset_alert(bool enabled);
    /** @brief Disable the analog comparator in hibernate mode (VRESET/ID.DIS, saves about 0.5 µA). */
    void set_comparator_disabled(bool disabled);

    /** @brief Program the hibernate thresholds (HIBRT).
     *  @param hib_thr_pct_per_h Hibernate when |CRATE| stays below this rate, in %/h (0.208 %/h steps).
     *  @param act_thr_v         Leave hibernate when |OCV − VCELL| exceeds this, in V (1.25 mV steps). */
    void set_hibernate_thresholds(float hib_thr_pct_per_h, float act_thr_v);
    /** @brief Read the hibernate thresholds. */
    void get_hibernate_thresholds(float* hib_thr_pct_per_h, float* act_thr_v);
    /** @brief Never hibernate (HIBRT ← 0x0000). */
    void disable_hibernate();
    /** @brief Always stay in hibernate (HIBRT ← 0xFFFF). */
    void force_hibernate();
    /** @brief Read MODE.HibStat; false if the register is unreadable. */
    bool is_hibernating();

    /** @brief Allow or forbid sleep mode (MODE.EnSleep; MODE is written whole). */
    void enable_sleep(bool enabled);
    /** @brief Enter sleep mode (CONFIG.SLEEP = 1); needs enable_sleep(true) first. */
    void sleep();
    /** @brief Leave sleep mode (CONFIG.SLEEP = 0). */
    void wake();

    /** @brief Read the STATUS register. */
    uint16_t read_status();
    /** @brief Clear the given STATUS bits and CONFIG.ALRT (releases the ALRT pin). @param mask STATUS bits, e.g. SOURCE_RESET. */
    void clear_status(uint16_t mask);
    /** @brief Read the raw CONFIG register. */
    uint16_t read_config();
    /** @brief Write the raw CONFIG register. */
    void write_config(uint16_t value);

    /** @brief Enable an alert source with a control bit (SOURCE_SOC_CHANGE: ALSC, SOURCE_VRESET: ENVR). */
    void enableInterrupt(uint16_t source);
    /** @brief Disable an alert source (control bit, or VALRT.MIN = 0 / VALRT.MAX = 0xFF). */
    void disableInterrupt(uint16_t source);
    /** @brief Subscribe to ALRT (falling edge).
     *  @param callback void(*)(uint16_t status); receives the STATUS word masked to the SOURCE_* bits.
     *  @param intPin   Optional InputPin overriding connection.intPin(). */
    void onInterrupt(void (*callback)(uint16_t status), InputPin* intPin = nullptr);
    /** @brief Unsubscribe. */
    void offInterrupt();
    /** @brief Read STATUS, clear the reported flags and CONFIG.ALRT.
     *  @return STATUS word masked to the SOURCE_* bits (0 = no alert). */
    uint16_t pollInterrupt();

private:
    static Max17048Full* _activeInstance;
    static void _edgeTrampoline();
    void _modify(uint8_t reg, uint16_t clearBits, uint16_t setBits);

    void (*_callback)(uint16_t) = nullptr;
    InputPin* _intPinUsed = nullptr;
    bool _sleepEnabled = false;
};
