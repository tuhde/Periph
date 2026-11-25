#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/RegisterConnection.h"

/** @brief Every measurement returned by AXP2101Full::readAll(). */
struct AXP2101Readings {
    float   batteryVoltage;   ///< Battery voltage in V.
    float   vbusVoltage;      ///< VBUS voltage in V.
    float   systemVoltage;    ///< System (VSYS) voltage in V.
    float   tsVoltage;        ///< TS pin voltage in V.
    float   dieTemperature;   ///< Die temperature in °C (vendor formula).
    uint8_t batteryPercent;   ///< Fuel-gauge state of charge, 0-100 %.
    uint8_t chargeState;      ///< One of AXP2101Minimal::CHARGE_*.
    bool    vbusPresent;      ///< True when a good input source is connected.
};

/** @brief Decoded STATUS1 / STATUS2 returned by AXP2101Full::status(). */
struct AXP2101Status {
    bool        vbusGood;           ///< Input source good.
    bool        batfetOpen;         ///< BATFET open.
    bool        batteryPresent;     ///< Battery present.
    bool        batteryActive;      ///< Battery in active mode.
    bool        thermalRegulation;  ///< Charger in thermal regulation.
    bool        currentLimit;       ///< In current limit.
    const char* batteryDirection;   ///< "standby", "charge", "discharge" or "reserved".
    bool        systemOn;           ///< System powered on.
    bool        vindpm;             ///< Input voltage regulation active.
    uint8_t     chargeState;        ///< One of AXP2101Minimal::CHARGE_*.
};

/** @brief One entry of the fast power-on start sequence for AXP2101Full::configureFastPoweron(). */
struct AXP2101FastEntry {
    const char* rail;   ///< Rail name, e.g. "dcdc1".
    int8_t      code;   ///< Start-sequence code 0-2, or -1 to disable the rail.
};

/** @brief AXP2101 single-cell Li-ion NVDC power-management IC — minimal interface.
 *
 *  Battery and power-source monitoring. Communicates over I²C at address 0x34.
 *  At construction the ADC channels (VBAT, TS, VBUS, VSYS, TDIE) and the fuel
 *  gauge are enabled; charger, input limits, rails, power key, interrupts and
 *  watchdog keep their power-on (eFuse) configuration. Bus errors are retried
 *  once; check valid() after construction.
 *
 *  @param connection RegisterConnection bound to I²C address 0x34.
 */
class AXP2101Minimal {
public:
    static constexpr uint8_t CHARGE_TRICKLE = 0;  ///< Trickle charge.
    static constexpr uint8_t CHARGE_PRE     = 1;  ///< Pre-charge.
    static constexpr uint8_t CHARGE_CC      = 2;  ///< Constant current.
    static constexpr uint8_t CHARGE_CV      = 3;  ///< Constant voltage.
    static constexpr uint8_t CHARGE_DONE    = 4;  ///< Charge done.
    static constexpr uint8_t CHARGE_NONE    = 5;  ///< Not charging.

    explicit AXP2101Minimal(RegisterConnection& connection);

    /** @brief True when the bus check at construction (read of STATUS1) succeeded. */
    bool valid() const { return _valid; }

    /** @brief Read the battery voltage.
     *  @return Battery voltage in V (1 mV / LSB). */
    float batteryVoltage();

    /** @brief Read the fuel-gauge state of charge.
     *  @return State of charge, 0-100 %. */
    uint8_t batteryPercent();

    /** @brief Read the charger state (STATUS2 bits 2:0).
     *  @return One of CHARGE_TRICKLE .. CHARGE_NONE. */
    uint8_t chargeState();

    /** @brief Check whether a good input source is connected (STATUS1.VBUS_GOOD).
     *  @return True when VBUS is good. */
    bool vbusPresent();

protected:
    static constexpr uint8_t REG_STATUS1       = 0x00;
    static constexpr uint8_t REG_STATUS2       = 0x01;
    static constexpr uint8_t REG_DATA_BUFFER0  = 0x04;
    static constexpr uint8_t REG_COMMON_CONFIG = 0x10;
    static constexpr uint8_t REG_BATFET_CTRL   = 0x12;
    static constexpr uint8_t REG_DIE_TEMP_CTRL = 0x13;
    static constexpr uint8_t REG_MIN_VSYS      = 0x14;
    static constexpr uint8_t REG_VINDPM        = 0x15;
    static constexpr uint8_t REG_IINLIM        = 0x16;
    static constexpr uint8_t REG_GAUGE_RESET   = 0x17;
    static constexpr uint8_t REG_MODULE_ENABLE = 0x18;
    static constexpr uint8_t REG_WATCHDOG_CTRL = 0x19;
    static constexpr uint8_t REG_LOWBAT_WARN   = 0x1A;
    static constexpr uint8_t REG_GPIO1_CTRL    = 0x1B;
    static constexpr uint8_t REG_PWRON_STATUS  = 0x20;
    static constexpr uint8_t REG_PWROFF_STATUS = 0x21;
    static constexpr uint8_t REG_PWROFF_EN     = 0x22;
    static constexpr uint8_t REG_DCDC_PROT     = 0x23;
    static constexpr uint8_t REG_VOFF          = 0x24;
    static constexpr uint8_t REG_PWROK_CTRL    = 0x25;
    static constexpr uint8_t REG_SLEEP_CTRL    = 0x26;
    static constexpr uint8_t REG_PWRKEY_LEVEL  = 0x27;
    static constexpr uint8_t REG_FAST_PWRON0   = 0x28;
    static constexpr uint8_t REG_ADC_ENABLE    = 0x30;
    static constexpr uint8_t REG_VBAT_H        = 0x34;
    static constexpr uint8_t REG_TS_H          = 0x36;
    static constexpr uint8_t REG_VBUS_H        = 0x38;
    static constexpr uint8_t REG_VSYS_H        = 0x3A;
    static constexpr uint8_t REG_TDIE_H        = 0x3C;
    static constexpr uint8_t REG_IRQ_EN0       = 0x40;
    static constexpr uint8_t REG_IRQ_STATUS0   = 0x48;
    static constexpr uint8_t REG_TS_CTRL       = 0x50;
    static constexpr uint8_t REG_TS_HYS_L2H    = 0x52;
    static constexpr uint8_t REG_TS_HYS_H2L    = 0x53;
    static constexpr uint8_t REG_VLTF_CHG      = 0x54;
    static constexpr uint8_t REG_VHTF_CHG      = 0x55;
    static constexpr uint8_t REG_VLTF_WORK     = 0x56;
    static constexpr uint8_t REG_VHTF_WORK     = 0x57;
    static constexpr uint8_t REG_JEITA_EN      = 0x58;
    static constexpr uint8_t REG_JEITA_CV      = 0x59;
    static constexpr uint8_t REG_JEITA_COOL    = 0x5A;
    static constexpr uint8_t REG_JEITA_WARM    = 0x5B;
    static constexpr uint8_t REG_IPRECHG       = 0x61;
    static constexpr uint8_t REG_ICC           = 0x62;
    static constexpr uint8_t REG_ITERM         = 0x63;
    static constexpr uint8_t REG_CV            = 0x64;
    static constexpr uint8_t REG_THERMAL_REG   = 0x65;
    static constexpr uint8_t REG_CHG_TIMER     = 0x67;
    static constexpr uint8_t REG_BAT_DETECT    = 0x68;
    static constexpr uint8_t REG_CHGLED_CTRL   = 0x69;
    static constexpr uint8_t REG_BTN_BAT_VTERM = 0x6A;
    static constexpr uint8_t REG_DCDC_EN       = 0x80;
    static constexpr uint8_t REG_DCDC_PWM      = 0x81;
    static constexpr uint8_t REG_BAT_PERCENT   = 0xA4;

    RegisterConnection& _connection;
    bool _valid = false;
    bool _err   = false;   ///< Sticky: set when a bus transfer failed twice since the last _clearErr().

    void    _clearErr() { _err = false; }
    uint8_t _readReg(uint8_t reg);
    void    _writeReg(uint8_t reg, uint8_t value);
    void    _rmw(uint8_t reg, uint8_t mask, uint8_t value);
    void    _command(uint8_t reg, uint8_t bit);
    uint16_t _adc14(uint8_t reg);
};

/** @brief AXP2101 full interface — extends AXP2101Minimal with the remaining ADC
 *  channels, charger, battery-temperature, power rail, power-key, sleep,
 *  watchdog, fuel-gauge and interrupt control.
 *
 *  Setters validate their argument and return false for a value the chip cannot
 *  represent (nothing is written) or after a failed bus transfer.
 *
 *  Dangerous operations: disabling or re-voltaging the rail that powers the host
 *  (commonly DCDC1 or DCDC3), powerOff(), the power-off thresholds, charge voltage
 *  / current beyond the cell's limits and setBatfetOffEnable() can power the board
 *  down or damage the battery.
 *
 *  @param connection RegisterConnection bound to I²C address 0x34.
 */
class AXP2101Full : public AXP2101Minimal {
public:
    // Interrupt sources: IRQ_STATUS0 in bits 7:0, STATUS1 in 15:8, STATUS2 in 23:16.
    static constexpr uint32_t SOURCE_BAT_UNDER_TEMP_WORK = 0x000001;
    static constexpr uint32_t SOURCE_BAT_OVER_TEMP_WORK  = 0x000002;
    static constexpr uint32_t SOURCE_BAT_UNDER_TEMP_CHG  = 0x000004;
    static constexpr uint32_t SOURCE_BAT_OVER_TEMP_CHG   = 0x000008;
    static constexpr uint32_t SOURCE_GAUGE_NEW_SOC       = 0x000010;
    static constexpr uint32_t SOURCE_GAUGE_WDT           = 0x000020;
    static constexpr uint32_t SOURCE_SOC_WARN_L1         = 0x000040;
    static constexpr uint32_t SOURCE_SOC_WARN_L2         = 0x000080;
    static constexpr uint32_t SOURCE_PWRON_POS_EDGE      = 0x000100;
    static constexpr uint32_t SOURCE_PWRON_NEG_EDGE      = 0x000200;
    static constexpr uint32_t SOURCE_PWRON_LONG          = 0x000400;
    static constexpr uint32_t SOURCE_PWRON_SHORT         = 0x000800;
    static constexpr uint32_t SOURCE_BAT_REMOVE          = 0x001000;
    static constexpr uint32_t SOURCE_BAT_INSERT          = 0x002000;
    static constexpr uint32_t SOURCE_VBUS_REMOVE         = 0x004000;
    static constexpr uint32_t SOURCE_VBUS_INSERT         = 0x008000;
    static constexpr uint32_t SOURCE_BAT_OVER_VOLTAGE    = 0x010000;
    static constexpr uint32_t SOURCE_CHARGER_TIMER       = 0x020000;
    static constexpr uint32_t SOURCE_DIE_OVER_TEMP_L1    = 0x040000;
    static constexpr uint32_t SOURCE_CHARGE_START        = 0x080000;
    static constexpr uint32_t SOURCE_CHARGE_DONE         = 0x100000;
    static constexpr uint32_t SOURCE_LDO_OVER_CURRENT    = 0x400000;
    static constexpr uint32_t SOURCE_WATCHDOG_EXPIRE     = 0x800000;

    explicit AXP2101Full(RegisterConnection& connection);

    /** @brief Read the VBUS voltage in V. */
    float vbusVoltage();
    /** @brief Read the system (VSYS) voltage in V. */
    float systemVoltage();
    /** @brief Read the TS pin voltage in V (0.5 mV / LSB). */
    float tsVoltage();
    /** @brief Read the die temperature in °C using the vendor XPowersLib formula (verify on hardware). */
    float dieTemperature();
    /** @brief Read every measurement into @p out. */
    void readAll(AXP2101Readings& out);
    /** @brief Decode STATUS1 and STATUS2 into @p out. */
    void status(AXP2101Status& out);

    /** @brief Select the enabled ADC channels (ADC_ENABLE). */
    bool setAdcChannels(bool vbat = true, bool ts = true, bool vbus = true, bool vsys = true,
                        bool tdie = true, bool gpadc = false);
    /** @brief Slow (64 s) ADC sampling when powered off on battery only (TS_H bit 7). */
    bool setAdcLowFrequency(bool enabled);

    /** @brief Enable or disable main-cell charging (MODULE_ENABLE bit 1). */
    bool setCharging(bool enabled);
    /** @brief Set the charge target voltage: 4.0, 4.1, 4.2, 4.35 or 4.4 V (the last two for high-voltage cells only). */
    bool setChargeVoltage(float volts);
    /** @brief Set the charge current: 0-0.2 A in 25 mA steps, 0.2-1.5 A in 100 mA steps, capped by the programmed charge voltage. */
    bool setChargeCurrent(float amps);
    /** @brief Set the pre-charge current: 0-0.2 A in 25 mA steps. */
    bool setPrechargeCurrent(float amps);
    /** @brief Set the termination current (0-0.2 A in 25 mA steps) and enable bit. */
    bool setTermination(float amps, bool enabled = true);
    /** @brief Set the input current limit: 0.1, 0.5, 0.9, 1.0, 1.5 or 2.0 A. */
    bool setInputCurrentLimit(float amps);
    /** @brief Set the input voltage limit (VINDPM): 3.88-5.08 V in 80 mV steps. */
    bool setInputVoltageLimit(float volts);
    /** @brief Set the minimum system voltage: 3.2-3.9 V in 100 mV steps. */
    bool setMinSystemVoltage(float volts);
    /** @brief Configure the charger safety timers: pre-charge 40/50/60/70 min, charge-done 5/8/12/20 h. */
    bool setChargeTimers(uint8_t prechargeMin = 60, uint8_t doneHours = 12, bool prechargeEnabled = true,
                         bool doneEnabled = true, bool slowInDpm = true);
    /** @brief Set the charger thermal-regulation threshold: 60, 80, 100 or 120 °C. */
    bool setThermalRegulation(uint8_t celsius);
    /** @brief Configure die over-temperature level 1 (115, 125 or 135 °C) and detection. */
    bool setDieProtection(uint8_t level1Celsius = 125, bool detect = true);
    /** @brief Enable or disable battery detection. */
    bool setBatteryDetection(bool enabled);
    /** @brief Configure backup (coin) cell charging; @p volts 2.6-3.3 V in 100 mV steps. */
    bool setBackupCharging(bool enabled, float volts = 2.9f);
    /** @brief Configure the CHGLED pin: mode "off"/"type_a"/"type_b"/"manual", output (manual) "hiz"/"blink_1hz"/"blink_4hz"/"low". */
    bool setChgled(const char* mode = "type_a", const char* output = "hiz");

    /** @brief Configure the TS pin: battery thermistor or external input, current 20/40/50/60 µA, source "off"/"when_adc"/"when_ts"/"always". */
    bool configureTs(bool batterySensor = true, uint8_t currentUa = 50, const char* source = "when_adc");
    /** @brief Set the battery-temperature limits as TS pin voltages in V (cold: 32 mV steps, hot: 2 mV steps). */
    bool setTsLimits(float chargeCold, float chargeHot, float workCold, float workHot);
    /** @brief Set the TS hysteresis in V (low to normal: 16 mV steps, high to normal: 4 mV steps). */
    bool setTsHysteresis(float lowToNormal = 0.032f, float highToNormal = 0.004f);
    /** @brief Configure the JEITA profile; negative @p coolVoltage / @p warmVoltage (16 / 8 mV steps) keep the thresholds. */
    bool configureJeita(bool enabled, float coolVoltage = -1.0f, float warmVoltage = -1.0f,
                        bool coolHalfCurrent = true, bool warmHalfCurrent = false,
                        uint8_t coolVoltageDrop = 0, uint8_t warmVoltageDrop = 1);

    /** @brief Read a rail: @p name "dcdc1".."dcdc4", "aldo1".."aldo4", "bldo1", "bldo2", "cpusldo", "dldo1", "dldo2". */
    bool getRail(const char* name, bool& enabled, float& volts);
    /** @brief Set a rail voltage (nearest step inside the rail's range; outside it returns false). */
    bool setRailVoltage(const char* name, float volts);
    /** @brief Enable or disable a rail. Disabling the rail that powers the host cuts the host's power. */
    bool setRailEnabled(const char* name, bool enabled);
    /** @brief Configure one DCDC: forced PWM and DVM (dcdc2/dcdc3 only; -1 keeps, 0 off, 1 on). */
    bool configureDcdc(const char* name, bool alwaysPwm = false, int8_t dvm = -1);
    /** @brief Configure settings shared by all DCDCs; @p uvpDebounceUs is 60/120/180/240 µs. */
    bool configureDcdcGlobal(bool forceCcm = false, bool slowRamp = false, bool frequencySpread = false,
                             bool spread100kHz = false, uint16_t uvpDebounceUs = 60);

    /** @brief Enable output discharge when a rail is off (COMMON_CONFIG bit 5). */
    bool setOffDischarge(bool enabled);
    /** @brief Soft power-off: every output except RTCLDO turns off. */
    bool powerOff();
    /** @brief Restart the system (power-off / power-on). */
    bool restart();
    /** @brief Enter sleep; the host disables / re-voltages rails first. */
    bool sleep();
    /** @brief Wake up from sleep. */
    bool wakeup();
    /** @brief Configure sleep / wake-up (SLEEP_CTRL bits 4, 3, 2). */
    bool configureSleep(bool wakeOnIrq = false, bool pwrokLow = true, bool restoreVoltages = false);
    /** @brief Read the PWRON_STATUS flags. */
    uint8_t powerOnSource();
    /** @brief Read the PWROFF_STATUS flags. */
    uint8_t powerOffSource();
    /** @brief Configure the power key: on 128/512/1000/2000 ms, off 4/6/8/10 s, IRQ 1/1.5/2/2.5 s. */
    bool configurePowerKey(uint16_t onMs = 512, float offS = 6.0f, float irqS = 1.5f, bool longPressOff = true,
                           bool restartInstead = false, bool forceOff16s = false);
    /** @brief Configure power-off sources: die over-temperature, VOFF 2.6-3.3 V, DCDC OVP, DCDC1-4 UVP mask. */
    bool configurePowerOff(bool dieOverTemp = true, float offVoltage = 2.6f, bool dcdcOvp = true, uint8_t dcdcUvp = 0x0F);
    /** @brief Configure PWROK: check, delay 8/16/32/64 ms, off delay, reverse sequence, restart on low. */
    bool configurePwrok(bool check = true, uint8_t delayMs = 8, bool offDelay = true,
                        bool reverseOffSequence = false, bool restartOnLow = false);
    /** @brief BATFET on when powered off on battery only (BATFET_CTRL bit 3). */
    bool setBatfetOffEnable(bool enabled);
    /** @brief Configure fast power-on / wake-up and optionally the per-rail start sequence (@p sequence may be null). */
    bool configureFastPoweron(bool poweron, bool wakeup, const AXP2101FastEntry* sequence = nullptr, size_t count = 0);
    /** @brief Configure the watchdog: timeout 1..128 s (powers of two), action "irq"/"reset"/"reset_pwrok"/"power_cycle". */
    bool configureWatchdog(bool enabled, uint8_t timeoutS = 64, const char* action = "irq");
    /** @brief Clear (feed) the watchdog. */
    bool feedWatchdog();

    /** @brief Set the low-battery warning levels: level 2 5-20 %, level 1 0-15 %. */
    bool configureBatteryWarning(uint8_t level2 = 10, uint8_t level1 = 1);
    /** @brief Enable or disable the fuel gauge. */
    bool setGaugeEnabled(bool enabled);
    /** @brief Reset the fuel gauge; @p full selects the deeper reset. */
    bool resetGauge(bool full = false);
    /** @brief Drive the open-drain GPIO1 output (true = low, false = Hi-Z). */
    bool setGpio1(bool low);
    /** @brief Read one of the four general-purpose bytes (index 0-3). */
    bool readDataBuffer(uint8_t index, uint8_t& value);
    /** @brief Write one of the four general-purpose bytes (survives soft restarts, not POR). */
    bool writeDataBuffer(uint8_t index, uint8_t value);

    /** @brief Enable interrupt sources (OR of SOURCE_*) in IRQ_EN0..2. */
    bool enableInterrupt(uint32_t source);
    /** @brief Disable interrupt sources in IRQ_EN0..2. */
    bool disableInterrupt(uint32_t source);
    /** @brief Read and clear IRQ_STATUS0..2 (writes back exactly the bits seen). Returns the 24-bit status. */
    uint32_t pollInterrupt();
    /** @brief Subscribe to the IRQ pin (falling edge) of the connection's InputPin. Without an InputPin nothing is armed — poll pollInterrupt(). */
    void onInterrupt(void (*callback)(uint32_t status));
    /** @brief Unsubscribe from the IRQ pin. */
    void offInterrupt();

private:
    void (*_callback)(uint32_t status) = nullptr;
    InputPin* _intPinUsed = nullptr;
    static AXP2101Full* _activeInstance;
    static void _edgeTrampoline();
    void _handleEdge();
};
