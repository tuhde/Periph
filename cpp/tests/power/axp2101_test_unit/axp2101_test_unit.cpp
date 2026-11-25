#include <stdio.h>
#include <stdint.h>
#include <cmath>
#include <functional>
#include <utility>
#include <vector>
#include "I2CConnectionMock.h"
#include "AXP2101.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

typedef std::vector<std::pair<int, int>> Pairs;   // (register, value)

// Mock writes accumulate; checks look at the most recent write to the register.
static int last_write(const I2CConnectionMock& m, int reg) {
    const auto& w = m.writes();
    for (size_t i = w.size(); i-- > 0;) {
        if (w[i].size() == 2 && w[i][0] == reg) return w[i][1];
    }
    return -1;
}

static void preset(I2CConnectionMock& m, const Pairs& regs) {
    m.setRegister(0x30, {0x03});   // ADC_ENABLE reset
    m.setRegister(0x18, {0x0A});   // MODULE_ENABLE reset
    for (const auto& r : regs) m.setRegister(static_cast<uint16_t>(r.first), {static_cast<uint8_t>(r.second)});
}

// Run `action` on a fresh Full driver and check the last write per expected register.
static void expect(const char* label, const Pairs& regs, const std::function<void(AXP2101Full&)>& action,
                   const Pairs& expected) {
    I2CConnectionMock m;
    preset(m, regs);
    AXP2101Full f(m);
    action(f);
    bool ok = true;
    for (const auto& e : expected) {
        int got = last_write(m, e.first);
        if (got != e.second) { printf("  register 0x%02X: got 0x%02X want 0x%02X\n", e.first, got, e.second); ok = false; }
    }
    check_true(ok, label);
}

static void rejects(const char* label, const std::function<bool(AXP2101Full&)>& action) {
    I2CConnectionMock m;
    preset(m, {{0x64, 3}});
    AXP2101Full f(m);
    check_true(!action(f), label);
}

static int g_irq_calls = 0;
static uint32_t g_irq_status = 0;
static void on_irq(uint32_t status) { g_irq_calls++; g_irq_status = status; }

int main() {
    // --- Minimal ---
    {
        I2CConnectionMock m;
        preset(m, {{0x34, 0x0F}, {0x35, 0xA0}, {0xA4, 87}, {0x00, 0x20}, {0x01, 0x22}});
        AXP2101Minimal c(m);
        check_true(c.valid(), "construct_valid");
        check_true(last_write(m, 0x30) == 0x1F, "init_adc_enable_0x1F");
        check_true(last_write(m, 0x18) == 0x0A, "init_module_gauge_bit");
        check_true(std::fabs(c.batteryVoltage() - 4.0f) < 1e-3f, "battery_voltage");
        check_true(c.batteryPercent() == 87, "battery_percent");
        check_true(c.chargeState() == AXP2101Minimal::CHARGE_CC, "charge_state_cc");
        check_true(c.vbusPresent(), "vbus_present_true");
        m.setRegister(0x00, {0x00});
        check_true(!c.vbusPresent(), "vbus_present_false");
    }
    {
        I2CConnectionMock m;
        preset(m, {{0x18, 0x02}, {0x34, 0xCF}, {0x35, 0xA0}});
        AXP2101Minimal c(m);
        check_true(last_write(m, 0x18) == 0x0A, "init_gauge_set_preserves_bits");
        check_true(std::fabs(c.batteryVoltage() - 4.0f) < 1e-3f, "adc_masks_debug_bits");
    }
    {
        I2CConnectionMock m;
        preset(m, {});
        m.failNextWrites(2);   // both attempts of the bus check fail
        AXP2101Minimal c(m);
        check_true(!c.valid(), "bus_failure_not_valid");
    }
    {
        I2CConnectionMock m;
        preset(m, {});
        m.failNextWrites(1);   // the retry succeeds
        AXP2101Minimal c(m);
        check_true(c.valid(), "single_nack_is_retried");
    }

    // --- Full measurements ---
    {
        I2CConnectionMock m;
        preset(m, {{0x34, 0x0F}, {0x35, 0xA0}, {0x36, 0x03}, {0x37, 0xE8}, {0x38, 0x13}, {0x39, 0x88},
                   {0x3A, 0x10}, {0x3B, 0x68}, {0x3C, 0x1C}, {0x3D, 0x6A}, {0xA4, 50}, {0x00, 0x3F}, {0x01, 0x4B}});
        AXP2101Full f(m);
        check_true(std::fabs(f.vbusVoltage() - 5.0f) < 1e-3f, "vbus_voltage");
        check_true(std::fabs(f.systemVoltage() - 4.2f) < 1e-3f, "system_voltage");
        check_true(std::fabs(f.tsVoltage() - 0.5f) < 1e-3f, "ts_voltage");
        check_true(std::fabs(f.dieTemperature() - 22.0f) < 1e-3f, "die_temperature");
        AXP2101Readings r;
        f.readAll(r);
        check_true(r.batteryPercent == 50 && std::fabs(r.batteryVoltage - 4.0f) < 1e-3f, "read_all");
        AXP2101Status s;
        f.status(s);
        check_true(s.vbusGood && s.batfetOpen && s.batteryPresent && s.batteryActive && s.thermalRegulation &&
                   s.currentLimit, "status_flags");
        check_true(s.batteryDirection[0] == 'd' && !s.systemOn && s.vindpm && s.chargeState == 3, "status_discharge");
    }
    {
        I2CConnectionMock m;
        preset(m, {{0x80, 0x0F}, {0x83, 0x80 | 18}});
        AXP2101Full f(m);
        bool en = false; float v = 0;
        check_true(f.getRail("dcdc2", en, v) && en && std::fabs(v - 0.68f) < 1e-3f, "get_rail_dcdc2");
        check_true(!f.getRail("nope", en, v), "get_rail_unknown");
    }

    // --- Setters ---
    expect("set_adc_channels", {}, [](AXP2101Full& f) { f.setAdcChannels(true, false, true, false, false, true); }, {{0x30, 0x25}});
    expect("adc_low_frequency", {{0x36, 0}}, [](AXP2101Full& f) { f.setAdcLowFrequency(true); }, {{0x36, 0x80}});
    expect("set_charging_off", {{0x18, 0x0A}}, [](AXP2101Full& f) { f.setCharging(false); }, {{0x18, 0x08}});
    expect("charge_voltage_4v2", {}, [](AXP2101Full& f) { f.setChargeVoltage(4.2f); }, {{0x64, 3}});
    expect("charge_voltage_4v4", {}, [](AXP2101Full& f) { f.setChargeVoltage(4.4f); }, {{0x64, 5}});
    expect("charge_current_0.5A", {{0x64, 3}}, [](AXP2101Full& f) { f.setChargeCurrent(0.5f); }, {{0x62, 11}});
    expect("charge_current_0.1A", {{0x64, 3}}, [](AXP2101Full& f) { f.setChargeCurrent(0.1f); }, {{0x62, 4}});
    expect("charge_current_1.4A", {{0x64, 3}}, [](AXP2101Full& f) { f.setChargeCurrent(1.4f); }, {{0x62, 20}});
    expect("charge_current_1.5A_at_4v0", {{0x64, 1}}, [](AXP2101Full& f) { f.setChargeCurrent(1.5f); }, {{0x62, 21}});
    expect("precharge_current", {{0x61, 0xF0}}, [](AXP2101Full& f) { f.setPrechargeCurrent(0.125f); }, {{0x61, 0xF5}});
    expect("termination_enabled", {{0x63, 0xE0}}, [](AXP2101Full& f) { f.setTermination(0.05f, true); }, {{0x63, 0xF2}});
    expect("termination_disabled", {{0x63, 0xE0}}, [](AXP2101Full& f) { f.setTermination(0.05f, false); }, {{0x63, 0xE2}});
    expect("input_current_limit", {}, [](AXP2101Full& f) { f.setInputCurrentLimit(2.0f); }, {{0x16, 5}});
    expect("input_voltage_limit", {}, [](AXP2101Full& f) { f.setInputVoltageLimit(4.36f); }, {{0x15, 6}});
    expect("min_system_voltage", {}, [](AXP2101Full& f) { f.setMinSystemVoltage(3.7f); }, {{0x14, 5}});
    expect("charge_timers_default", {{0x67, 0x08}}, [](AXP2101Full& f) { f.setChargeTimers(); }, {{0x67, 0xEE}});
    expect("charge_timers_custom", {{0x67, 0x08}}, [](AXP2101Full& f) { f.setChargeTimers(70, 20, false, false, false); }, {{0x67, 0x3B}});
    expect("thermal_regulation", {}, [](AXP2101Full& f) { f.setThermalRegulation(120); }, {{0x65, 3}});
    expect("die_protection", {}, [](AXP2101Full& f) { f.setDieProtection(135, false); }, {{0x13, 0x04}});
    expect("battery_detection_off", {{0x68, 1}}, [](AXP2101Full& f) { f.setBatteryDetection(false); }, {{0x68, 0}});
    expect("backup_charging", {{0x6A, 0xF8}}, [](AXP2101Full& f) { f.setBackupCharging(true, 3.3f); }, {{0x6A, 0xFF}, {0x18, 0x0E}});
    expect("chgled_manual_blink_4hz", {{0x69, 0xC0}}, [](AXP2101Full& f) { f.setChgled("manual", "blink_4hz"); }, {{0x69, 0xE5}});
    expect("chgled_type_b", {{0x69, 0xC0}}, [](AXP2101Full& f) { f.setChgled("type_b"); }, {{0x69, 0xC3}});
    expect("chgled_off", {{0x69, 0xC1}}, [](AXP2101Full& f) { f.setChgled("off"); }, {{0x69, 0xC0}});
    expect("configure_ts", {{0x50, 0x20}}, [](AXP2101Full& f) { f.configureTs(false, 60, "always"); }, {{0x50, 0x3F}});
    expect("ts_limits", {}, [](AXP2101Full& f) { f.setTsLimits(1.312f, 0.176f, 1.984f, 0.152f); },
           {{0x54, 41}, {0x55, 88}, {0x56, 62}, {0x57, 76}});
    expect("ts_hysteresis", {}, [](AXP2101Full& f) { f.setTsHysteresis(0.032f, 0.004f); }, {{0x52, 2}, {0x53, 1}});
    expect("jeita", {{0x59, 0x80}}, [](AXP2101Full& f) { f.configureJeita(true, 0.8f, 0.24f); },
           {{0x59, 0x94}, {0x5A, 50}, {0x5B, 30}, {0x58, 1}});
    expect("rail_dcdc2_high_range", {{0x83, 0x80}}, [](AXP2101Full& f) { f.setRailVoltage("dcdc2", 1.5f); }, {{0x83, 0x80 | 85}});
    expect("rail_dcdc2_1v2", {{0x83, 0x80}}, [](AXP2101Full& f) { f.setRailVoltage("dcdc2", 1.2f); }, {{0x83, 0x80 | 70}});
    expect("rail_dcdc1_3v3", {}, [](AXP2101Full& f) { f.setRailVoltage("dcdc1", 3.3f); }, {{0x82, 18}});
    expect("rail_dcdc4_max", {}, [](AXP2101Full& f) { f.setRailVoltage("dcdc4", 1.84f); }, {{0x85, 102}});
    expect("rail_aldo2_3v3", {}, [](AXP2101Full& f) { f.setRailVoltage("aldo2", 3.3f); }, {{0x93, 28}});
    expect("rail_cpusldo_1v0", {}, [](AXP2101Full& f) { f.setRailVoltage("cpusldo", 1.0f); }, {{0x98, 10}});
    expect("rail_dldo1_3v3", {}, [](AXP2101Full& f) { f.setRailVoltage("dldo1", 3.3f); }, {{0x99, 28}});
    expect("rail_dldo2_1v4", {}, [](AXP2101Full& f) { f.setRailVoltage("dldo2", 1.4f); }, {{0x9A, 18}});
    expect("rail_enable_bldo2", {{0x90, 0}}, [](AXP2101Full& f) { f.setRailEnabled("bldo2", true); }, {{0x90, 0x20}});
    expect("rail_enable_dldo1_keeps_bldo2", {{0x90, 0x20}}, [](AXP2101Full& f) { f.setRailEnabled("dldo1", true); }, {{0x90, 0xA0}});
    expect("rail_disable_bldo2", {{0x90, 0xA0}}, [](AXP2101Full& f) { f.setRailEnabled("bldo2", false); }, {{0x90, 0x80}});
    expect("rail_enable_dldo2", {{0x91, 0}}, [](AXP2101Full& f) { f.setRailEnabled("dldo2", true); }, {{0x91, 0x01}});
    expect("dcdc3_always_pwm_dvm", {{0x81, 0}}, [](AXP2101Full& f) { f.configureDcdc("dcdc3", true, 1); }, {{0x81, 0x10}, {0x84, 0x80}});
    expect("dcdc_global", {{0x80, 0x0F}, {0x81, 0x10}}, [](AXP2101Full& f) { f.configureDcdcGlobal(true, true, true, true, 240); },
           {{0x80, 0x6F}, {0x81, 0xD3}});
    expect("off_discharge_off_keeps_reserved", {{0x10, 0x30}}, [](AXP2101Full& f) { f.setOffDischarge(false); }, {{0x10, 0x10}});
    expect("power_off_sets_bit0", {{0x10, 0x30}}, [](AXP2101Full& f) { f.powerOff(); }, {{0x10, 0x31}});
    expect("restart_sets_bit1", {{0x10, 0x30}}, [](AXP2101Full& f) { f.restart(); }, {{0x10, 0x32}});
    expect("sleep_bit0", {{0x26, 0x08}}, [](AXP2101Full& f) { f.sleep(); }, {{0x26, 0x09}});
    expect("wakeup_bit1", {{0x26, 0x08}}, [](AXP2101Full& f) { f.wakeup(); }, {{0x26, 0x0A}});
    expect("configure_sleep", {{0x26, 0x03}}, [](AXP2101Full& f) { f.configureSleep(true, false, true); }, {{0x26, 0x17}});
    expect("power_key", {{0x27, 0xC0}, {0x22, 0x04}, {0x10, 0x30}},
           [](AXP2101Full& f) { f.configurePowerKey(128, 10.0f, 2.5f, true, true, true); }, {{0x27, 0xFC}, {0x22, 0x07}, {0x10, 0x34}});
    expect("power_off_config", {{0x22, 0x03}, {0x23, 0}}, [](AXP2101Full& f) { f.configurePowerOff(false, 3.3f, true, 0x05); },
           {{0x22, 0x03}, {0x24, 7}, {0x23, 0x25}});
    expect("pwrok", {{0x25, 0x20}, {0x10, 0x30}}, [](AXP2101Full& f) { f.configurePwrok(true, 64, true, true, true); },
           {{0x25, 0x3F}, {0x10, 0x38}});
    expect("batfet_off_enable", {{0x12, 0}}, [](AXP2101Full& f) { f.setBatfetOffEnable(true); }, {{0x12, 0x08}});
    expect("fast_poweron", {{0x28, 0xFF}, {0x2B, 0x0F}}, [](AXP2101Full& f) {
        AXP2101FastEntry seq[] = {{"dcdc1", 1}, {"dcdc4", -1}, {"dldo2", 2}};
        f.configureFastPoweron(true, false, seq, 3);
    }, {{0x28, 0xFD}, {0x2B, 0x8B}});
    expect("watchdog", {{0x19, 0}, {0x18, 0x0A}}, [](AXP2101Full& f) { f.configureWatchdog(true, 128, "power_cycle"); },
           {{0x19, 0x37}, {0x18, 0x0B}});
    expect("feed_watchdog", {{0x19, 0x06}}, [](AXP2101Full& f) { f.feedWatchdog(); }, {{0x19, 0x0E}});
    expect("battery_warning", {}, [](AXP2101Full& f) { f.configureBatteryWarning(15, 5); }, {{0x1A, 0xA5}});
    expect("gauge_enable", {{0x18, 0x02}}, [](AXP2101Full& f) { f.setGaugeEnabled(true); }, {{0x18, 0x0A}});
    expect("reset_gauge", {{0x17, 0}}, [](AXP2101Full& f) { f.resetGauge(false); }, {{0x17, 0x08}});
    expect("reset_gauge_full", {{0x17, 0}}, [](AXP2101Full& f) { f.resetGauge(true); }, {{0x17, 0x04}});
    expect("gpio1_low", {{0x1B, 0}}, [](AXP2101Full& f) { f.setGpio1(true); }, {{0x1B, 0x04}});
    expect("gpio1_hiz", {{0x1B, 0x04}}, [](AXP2101Full& f) { f.setGpio1(false); }, {{0x1B, 0x00}});
    expect("data_buffer_write", {}, [](AXP2101Full& f) { f.writeDataBuffer(2, 0xAB); }, {{0x06, 0xAB}});
    expect("irq_disable", {{0x40, 0xFF}, {0x41, 0xFC}, {0x42, 0x5F}},
           [](AXP2101Full& f) { f.disableInterrupt(AXP2101Full::SOURCE_VBUS_INSERT | AXP2101Full::SOURCE_CHARGE_DONE); },
           {{0x41, 0x7C}, {0x42, 0x4F}});
    expect("irq_enable", {{0x40, 0}, {0x41, 0x7C}, {0x42, 0x4F}},
           [](AXP2101Full& f) { f.enableInterrupt(AXP2101Full::SOURCE_VBUS_INSERT | AXP2101Full::SOURCE_PWRON_SHORT); }, {{0x41, 0xFC}});
    expect("irq_enable_en0_only", {{0x40, 0}, {0x41, 0}, {0x42, 0}},
           [](AXP2101Full& f) { f.enableInterrupt(AXP2101Full::SOURCE_SOC_WARN_L2); }, {{0x40, 0x80}});

    // --- Validation ---
    rejects("charge_voltage_invalid", [](AXP2101Full& f) { return f.setChargeVoltage(4.25f); });
    rejects("charge_current_over_cv_limit", [](AXP2101Full& f) { return f.setChargeCurrent(1.5f); });
    rejects("charge_current_negative", [](AXP2101Full& f) { return f.setChargeCurrent(-0.1f); });
    rejects("precharge_out_of_range", [](AXP2101Full& f) { return f.setPrechargeCurrent(0.3f); });
    rejects("input_current_limit_invalid", [](AXP2101Full& f) { return f.setInputCurrentLimit(1.2f); });
    rejects("jeita_bad_drop", [](AXP2101Full& f) { return f.configureJeita(true, -1.0f, -1.0f, true, false, 3, 0); });
    rejects("rail_dcdc3_over_max", [](AXP2101Full& f) { return f.setRailVoltage("dcdc3", 1.84f); });
    rejects("rail_dldo1_over", [](AXP2101Full& f) { return f.setRailVoltage("dldo1", 3.5f); });
    rejects("rail_unknown", [](AXP2101Full& f) { return f.setRailVoltage("foo", 1.0f); });
    rejects("rail_below_range", [](AXP2101Full& f) { return f.setRailVoltage("aldo1", 0.3f); });
    rejects("dcdc1_dvm_rejected", [](AXP2101Full& f) { return f.configureDcdc("dcdc1", false, 1); });
    rejects("power_key_bad_value", [](AXP2101Full& f) { return f.configurePowerKey(100); });
    rejects("fast_poweron_unknown_rail", [](AXP2101Full& f) {
        AXP2101FastEntry seq[] = {{"x", 1}};
        return f.configureFastPoweron(true, true, seq, 1);
    });
    rejects("watchdog_bad_timeout", [](AXP2101Full& f) { return f.configureWatchdog(true, 3); });
    rejects("battery_warning_range", [](AXP2101Full& f) { return f.configureBatteryWarning(25, 1); });
    rejects("data_buffer_index", [](AXP2101Full& f) { uint8_t v; return f.readDataBuffer(4, v); });

    // --- Data buffer, power sources, interrupts ---
    {
        I2CConnectionMock m;
        preset(m, {{0x20, 0x21}, {0x21, 0x81}});
        AXP2101Full f(m);
        f.writeDataBuffer(2, 0xAB);
        uint8_t v = 0;
        check_true(f.readDataBuffer(2, v) && v == 0xAB, "data_buffer_read");
        check_true(f.powerOnSource() == 0x21, "power_on_source");
        check_true(f.powerOffSource() == 0x81, "power_off_source");
    }
    {
        I2CConnectionMock m;
        preset(m, {{0x48, 0x10}, {0x49, 0x88}, {0x4A, 0x01}});
        AXP2101Full f(m);
        uint32_t st = f.pollInterrupt();
        check_true(st == 0x018810, "poll_interrupt_value");
        check_true(last_write(m, 0x48) == 0x10 && last_write(m, 0x49) == 0x88 && last_write(m, 0x4A) == 0x01,
                   "poll_interrupt_clears_seen_bits");
        m.setRegister(0x48, {0, 0, 0});
        size_t n = 0;
        for (const auto& w : m.writes()) if (w.size() == 2 && w[0] >= 0x48 && w[0] <= 0x4A) n++;
        size_t before = n;
        check_true(f.pollInterrupt() == 0, "poll_interrupt_idle");
        n = 0;
        for (const auto& w : m.writes()) if (w.size() == 2 && w[0] >= 0x48 && w[0] <= 0x4A) n++;
        check_true(n == before, "poll_interrupt_idle_no_writes");
    }
    {
        I2CConnectionMock m;
        preset(m, {{0x49, 0x80}});
        AXP2101Full f(m);
        f.onInterrupt(on_irq);   // no InputPin wired: nothing armed, must not crash
        f.offInterrupt();
        check_true(g_irq_calls == 0 && g_irq_status == 0, "on_interrupt_without_pin_is_inert");
    }

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
