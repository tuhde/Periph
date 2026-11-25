#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/RegisterConnection.h"
#include "../../connection/InputPin.h"

/** @brief CRC-8 (ATM): polynomial 0x07, init 0x00, MSB first, no reflection, no final XOR.
 *  @param data   Bytes to checksum.
 *  @param length Number of bytes.
 *  @return CRC byte.
 */
uint8_t lc709203f_crc8(const uint8_t* data, size_t length);

/** @brief LC709203F 1-cell Li-ion fuel gauge — minimal interface.
 *
 * Reports cell voltage, state of charge and temperature. The constructor runs the
 * datasheet starting flow (operational mode, APA, battery profile, I²C temperature
 * mode at 25 °C). Every transfer carries a CRC-8 that the driver appends on writes and
 * verifies on reads (one retry); after a failed read, valid() is false and the value is 0.
 *
 * @param connection  RegisterConnection (I²C, address 0x0B).
 * @param capacityMah Design capacity in mAh (clamped to 100-3000 for the APA interpolation).
 * @param profile     Battery profile written to register 0x12 (0 or 1).
 */
class LC709203FMinimal {
public:
    explicit LC709203FMinimal(RegisterConnection& connection, uint16_t capacityMah = 1000, uint8_t profile = 1);

    /** @brief Read the cell voltage.
     *  @return Cell voltage in volts (raw × 1 mV).
     */
    float voltage();

    /** @brief Read the state of charge from the ITE register.
     *  @return State of charge in % (0.0-100.0).
     */
    float percent();

    /** @brief Read the cell temperature register.
     *  @return Cell temperature in °C.
     */
    float temperature();

    /** @brief Supply the cell temperature (I²C temperature mode); clamped to -20...+60 °C.
     *  @param celsius Cell temperature in °C.
     */
    void set_temperature(float celsius);

    /** @brief True if the most recent read passed the CRC check and the bus transfer succeeded. */
    bool valid() const { return _valid; }

    /** @brief APA value interpolated from the datasheet table for a design capacity.
     *  @param capacityMah Design capacity in mAh.
     */
    static uint8_t apaFor(uint16_t capacityMah);

protected:
    static constexpr uint8_t REG_BEFORE_RSOC     = 0x04;
    static constexpr uint8_t REG_THERMISTOR_B    = 0x06;
    static constexpr uint8_t REG_INITIAL_RSOC    = 0x07;
    static constexpr uint8_t REG_TEMPERATURE     = 0x08;
    static constexpr uint8_t REG_VOLTAGE         = 0x09;
    static constexpr uint8_t REG_DIRECTION       = 0x0A;
    static constexpr uint8_t REG_APA             = 0x0B;
    static constexpr uint8_t REG_APT             = 0x0C;
    static constexpr uint8_t REG_RSOC            = 0x0D;
    static constexpr uint8_t REG_ITE             = 0x0F;
    static constexpr uint8_t REG_IC_VERSION      = 0x11;
    static constexpr uint8_t REG_PROFILE         = 0x12;
    static constexpr uint8_t REG_ALARM_RSOC      = 0x13;
    static constexpr uint8_t REG_ALARM_VOLTAGE   = 0x14;
    static constexpr uint8_t REG_POWER_MODE      = 0x15;
    static constexpr uint8_t REG_STATUS          = 0x16;
    static constexpr uint8_t REG_PROFILE_ID      = 0x1A;

    uint16_t _read_word(uint8_t cmd);
    void     _write_word(uint8_t cmd, uint16_t value);
    void     _wake_and_operational();

    RegisterConnection& _connection;
    bool _valid = true;
};

/** @brief LC709203F full interface — RSOC control, thermistor mode, alarms, profiles, power mode.
 *
 * Alarm bitmask constants (pollInterrupt / callback): ALARM_RSOC, ALARM_VOLTAGE.
 * The chip has no status register; pollInterrupt() evaluates RSOC and voltage against the
 * cached thresholds. ALARMB delivery uses the InputPin passed to onInterrupt() (or connection.intPin()).
 */
class LC709203FFull : public LC709203FMinimal {
public:
    static constexpr uint16_t DIRECTION_AUTO      = 0x0000;
    static constexpr uint16_t DIRECTION_CHARGE    = 0x0001;
    static constexpr uint16_t DIRECTION_DISCHARGE = 0xFFFF;
    static constexpr uint8_t  ALARM_RSOC          = 0x01;
    static constexpr uint8_t  ALARM_VOLTAGE       = 0x02;

    explicit LC709203FFull(RegisterConnection& connection, uint16_t capacityMah = 1000, uint8_t profile = 1);

    /** @brief Read the RSOC register. @return State of charge in % (0-100). */
    uint8_t rsoc();
    /** @brief Force the RSOC register (use sleep mode to keep it). @param percent 0-100. */
    void set_rsoc(uint8_t percent);
    /** @brief Initialize RSOC from the present voltage (0x07 ← 0xAA55); waits 2 ms. */
    void init_rsoc();
    /** @brief Initialize RSOC from the maximum voltage since reset (0x04 ← 0xAA55); waits 2 ms. */
    void before_rsoc();
    /** @brief Switch to thermistor temperature mode. @param bConstant Thermistor B constant in K. */
    void use_thermistor(uint16_t bConstant = 3380);
    /** @brief Switch to I²C temperature mode and write a temperature. @param celsius Cell temperature in °C. */
    void use_i2c_temperature(float celsius = 25.0f);
    /** @brief True if the temperature source is the thermistor. */
    bool is_thermistor_mode();
    /** @brief Read the thermistor B constant (K). */
    uint16_t get_b_constant();
    /** @brief Write the Adjustment Pack Application register (0-255). */
    void set_apa(uint8_t value);
    /** @brief Read the Adjustment Pack Application register. */
    uint8_t get_apa();
    /** @brief Write the Adjustment Pack Thermistor register. */
    void set_apt(uint16_t value);
    /** @brief Read the Adjustment Pack Thermistor register. */
    uint16_t get_apt();
    /** @brief Set the current direction (DIRECTION_AUTO / CHARGE / DISCHARGE); other values are ignored.
     *  @return true if the value was valid and written. */
    bool set_current_direction(uint16_t mode);
    /** @brief Read the current direction register. */
    uint16_t get_current_direction();
    /** @brief Select battery profile 0 or 1 (re-initializes the chip; waits 90 ms). */
    void set_profile(uint8_t profile);
    /** @brief Read the selected battery profile. */
    uint8_t get_profile();
    /** @brief Read the Number of the Parameter register (e.g. 0x0301). */
    uint16_t profile_id();
    /** @brief Read the IC Version register. */
    uint16_t ic_version();
    /** @brief Set the low-RSOC alarm threshold. @param percent 0 disables, 1-100. */
    void set_alarm_rsoc(uint8_t percent);
    /** @brief Read the low-RSOC alarm threshold in %. */
    uint8_t get_alarm_rsoc();
    /** @brief Set the low-voltage alarm threshold. @param volts 0 disables; resolution 1 mV. */
    void set_alarm_voltage(float volts);
    /** @brief Read the low-voltage alarm threshold in volts. */
    float get_alarm_voltage();
    /** @brief Enter sleep mode (only I²C stays alive; charge counting stops). */
    void sleep();
    /** @brief Wake from sleep: dummy transaction, wait 400 µs, enter operational mode. */
    void wake();
    /** @brief True if the chip is in sleep mode (wakes the bus first). */
    bool is_sleeping();

    /** @brief Subscribe to ALARMB (falling edge).
     *  @param callback void(*)(uint8_t status); receives the ALARM_* bitmask.
     *  @param intPin   Optional InputPin overriding connection.intPin().
     */
    void onInterrupt(void (*callback)(uint8_t status), InputPin* intPin = nullptr);
    /** @brief Unsubscribe. */
    void offInterrupt();
    /** @brief Evaluate RSOC and voltage against the cached thresholds.
     *  @return Bitmask of ALARM_RSOC / ALARM_VOLTAGE (0 = no alarm). */
    uint8_t pollInterrupt();

private:
    static LC709203FFull* _activeInstance;
    static void _edgeTrampoline();

    void (*_callback)(uint8_t) = nullptr;
    InputPin* _intPinUsed = nullptr;
    uint16_t _alarmRsoc = 0;
    uint16_t _alarmVoltage = 0;
};
