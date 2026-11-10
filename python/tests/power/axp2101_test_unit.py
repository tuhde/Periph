"""Unit test for the AXP2101 — runs without hardware using the I2C mock.

Verifies the init sequence, 14-bit ADC conversion, status decoding, the
read-modify-write helpers (reserved / eFuse bits preserved), rail voltage
encoding, self-clearing command writes and the 24-bit interrupt registers.
"""

import sys
from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.power.axp2101 import AXP2101Minimal, AXP2101Full

passed = 0
failed = 0


def check_true(label, condition):
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1


def last_write(mock, reg):
    vals = [w[1] for w in mock.writes if len(w) == 2 and w[0] == reg]
    return vals[-1] if vals else None


def raises(fn, *args):
    try:
        fn(*args)
    except ValueError:
        return True
    return False


def make(**regs):
    m = I2CConnectionMock()
    m.set_register(0x30, 0x03)   # ADC_ENABLE reset
    m.set_register(0x18, 0x0A)   # MODULE_ENABLE reset
    for r in regs:
        m.set_register(int(r[1:], 16), regs[r])
    return m


# --- Minimal -------------------------------------------------------------

m = make()
m.set_register(0x34, 0x0F, 0xA0)         # VBAT = 0x0FA0 = 4000 mV
m.set_register(0xA4, 87)
m.set_register(0x00, 0x20)               # VBUS_GOOD
m.set_register(0x01, 0x22)               # charging, CC (010)
chip = AXP2101Minimal(m)
check_true('init_adc_enable_0x1F', last_write(m, 0x30) == 0x1F)
check_true('init_module_gauge_bit', last_write(m, 0x18) == 0x0A)
check_true('battery_voltage', abs(chip.battery_voltage() - 4.0) < 1e-9)
check_true('battery_percent', chip.battery_percent() == 87)
check_true('charge_state_cc', chip.charge_state() == AXP2101Minimal.CHARGE_CC)
check_true('vbus_present_true', chip.vbus_present() is True)
m.set_register(0x00, 0x00)
check_true('vbus_present_false', chip.vbus_present() is False)

m = make()
m.set_register(0x18, 0x02)               # gauge off, charging on
AXP2101Minimal(m)
check_true('init_gauge_set_preserves_bits', last_write(m, 0x18) == 0x0A)

# Debug bits 7:6 of VBAT_H are masked.
m = make()
m.set_register(0x34, 0xCF, 0xA0)
check_true('adc_masks_debug_bits', abs(AXP2101Minimal(m).battery_voltage() - 4.0) < 1e-9)

# Bus check failure raises OSError.
m = make()
m.fail_next_writes(0)
class Dead(I2CConnectionMock):
    def write_read(self, data, n):
        raise OSError(5, 'nack')
try:
    AXP2101Minimal(Dead())
    check_true('init_bus_failure_raises', False)
except OSError:
    check_true('init_bus_failure_raises', True)

# --- Full measurements ---------------------------------------------------

m = make()
m.set_register(0x34, 0x0F, 0xA0)
m.set_register(0x36, 0x03, 0xE8)         # TS 0x3E8 = 1000 -> 0.5 V
m.set_register(0x38, 0x13, 0x88)         # VBUS 5000 mV
m.set_register(0x3A, 0x10, 0x68)         # VSYS 4200 mV
m.set_register(0x3C, 0x1C, 0x6A)         # TDIE 7274 -> 22 C
m.set_register(0xA4, 50)
f = AXP2101Full(m)
check_true('vbus_voltage', abs(f.vbus_voltage() - 5.0) < 1e-9)
check_true('system_voltage', abs(f.system_voltage() - 4.2) < 1e-9)
check_true('ts_voltage', abs(f.ts_voltage() - 0.5) < 1e-9)
check_true('die_temperature', abs(f.die_temperature() - 22.0) < 1e-9)
d = f.read_all()
check_true('read_all_keys', len(d) == 8 and d['battery_percent'] == 50)

m.set_register(0x00, 0x3F)
m.set_register(0x01, 0x4B)               # discharge? bits 6:5=10 -> 0x40; bit4 sys; vindpm; charge 011
s = f.status()
check_true('status_flags', s['vbus_good'] and s['batfet_open'] and s['battery_present']
           and s['battery_active'] and s['thermal_regulation'] and s['current_limit'])
check_true('status_direction_discharge', s['battery_direction'] == 'discharge')
check_true('status_system_on_clear_vindpm', (not s['system_on']) and s['vindpm'] and s['charge_state'] == 3)

# --- ADC / charger -------------------------------------------------------

f.set_adc_channels(vbat=True, ts=False, vbus=True, vsys=False, tdie=False, gpadc=True)
check_true('set_adc_channels', last_write(m, 0x30) == 0x25)
m.set_register(0x36, 0x00)
f.set_adc_low_frequency(True)
check_true('adc_low_frequency_bit7', last_write(m, 0x36) == 0x80)

m.set_register(0x18, 0x0A)
f.set_charging(False)
check_true('set_charging_off_keeps_gauge', last_write(m, 0x18) == 0x08)
f.set_charging(True)
check_true('set_charging_on', last_write(m, 0x18) == 0x0A)

m.set_register(0x64, 0x00)
f.set_charge_voltage(4.2)
check_true('charge_voltage_4v2', last_write(m, 0x64) == 3)
check_true('charge_voltage_invalid', raises(f.set_charge_voltage, 4.25))

f.set_charge_current(0.5)
check_true('charge_current_0.5A', last_write(m, 0x62) == 11)
f.set_charge_current(0.1)
check_true('charge_current_0.1A', last_write(m, 0x62) == 4)
f.set_charge_current(1.4)
check_true('charge_current_1.4A', last_write(m, 0x62) == 20)
check_true('charge_current_over_cv_limit', raises(f.set_charge_current, 1.5))
f.set_charge_voltage(4.0)
f.set_charge_current(1.5)
check_true('charge_current_1.5A_at_4v0', last_write(m, 0x62) == 21)
check_true('charge_current_negative', raises(f.set_charge_current, -0.1))

m.set_register(0x61, 0xF0)
f.set_precharge_current(0.125)
check_true('precharge_current', last_write(m, 0x61) == 0xF5)
check_true('precharge_out_of_range', raises(f.set_precharge_current, 0.3))
m.set_register(0x63, 0xE0)
f.set_termination(0.05, True)
check_true('termination_enabled', last_write(m, 0x63) == 0xF2)
f.set_termination(0.05, False)
check_true('termination_disabled', last_write(m, 0x63) == 0xE2)

f.set_input_current_limit(2.0)
check_true('input_current_limit', last_write(m, 0x16) == 5)
check_true('input_current_limit_invalid', raises(f.set_input_current_limit, 1.2))
f.set_input_voltage_limit(4.36)
check_true('input_voltage_limit', last_write(m, 0x15) == 6)
f.set_min_system_voltage(3.7)
check_true('min_system_voltage', last_write(m, 0x14) == 5)

m.set_register(0x67, 0x08)
f.set_charge_timers()
check_true('charge_timers_default', last_write(m, 0x67) == 0xEE)
f.set_charge_timers(70, 20, False, False, False)
check_true('charge_timers_custom', last_write(m, 0x67) == 0x3B)

f.set_thermal_regulation(120)
check_true('thermal_regulation', last_write(m, 0x65) == 3)
f.set_die_protection(135, False)
check_true('die_protection', last_write(m, 0x13) == 0x04)
f.set_battery_detection(False)
check_true('battery_detection_off', last_write(m, 0x68) == 0x00)
m.set_register(0x6A, 0xF8)
f.set_backup_charging(True, 3.3)
check_true('backup_vterm', last_write(m, 0x6A) == 0xFF)
check_true('backup_enable_bit2', last_write(m, 0x18) == 0x0E)

m.set_register(0x69, 0xC0)
f.set_chgled('manual', 'blink_4hz')
check_true('chgled_manual_blink_4hz', last_write(m, 0x69) == 0xE5)
f.set_chgled('type_b')
check_true('chgled_type_b', last_write(m, 0x69) == 0xC3)
f.set_chgled('off')
check_true('chgled_off', last_write(m, 0x69) == 0xC0)

# --- TS / JEITA -----------------------------------------------------------

m.set_register(0x50, 0x20)
f.configure_ts(False, 60, 'always')
check_true('configure_ts', last_write(m, 0x50) == 0x3F)
f.set_ts_limits(1.312, 0.176, 1.984, 0.152)
check_true('ts_limits_charge_cold', last_write(m, 0x54) == 41)
check_true('ts_limits_charge_hot', last_write(m, 0x55) == 88)
check_true('ts_limits_work_cold', last_write(m, 0x56) == 62)
check_true('ts_limits_work_hot', last_write(m, 0x57) == 76)
f.set_ts_hysteresis(0.032, 0.004)
check_true('ts_hysteresis', last_write(m, 0x52) == 2 and last_write(m, 0x53) == 1)
m.set_register(0x59, 0x80)
f.configure_jeita(True, 0.8, 0.24)
check_true('jeita_cv_defaults', last_write(m, 0x59) == 0x94)
check_true('jeita_cool_warm', last_write(m, 0x5A) == 50 and last_write(m, 0x5B) == 30)
check_true('jeita_enable', last_write(m, 0x58) == 1)
check_true('jeita_bad_drop', raises(f.configure_jeita, True, None, None, True, False, 3, 0))

# --- Rails ---------------------------------------------------------------

m.set_register(0x80, 0x0F)
m.set_register(0x83, 0x80 | 18)          # DVM on, 0.68 V
en, v = f.get_rail('dcdc2')
check_true('get_rail_dcdc2', en and abs(v - 0.68) < 1e-9)
f.set_rail_voltage('dcdc2', 1.5)
check_true('rail_dcdc2_high_range', last_write(m, 0x83) == (0x80 | 71 + 14))
f.set_rail_voltage('dcdc2', 1.2)
check_true('rail_dcdc2_1v2', last_write(m, 0x83) == (0x80 | 70))
f.set_rail_voltage('dcdc1', 3.3)
check_true('rail_dcdc1_3v3', last_write(m, 0x82) == 18)
f.set_rail_voltage('dcdc4', 1.84)
check_true('rail_dcdc4_max', last_write(m, 0x85) == 102)
check_true('rail_dcdc3_over_max', raises(f.set_rail_voltage, 'dcdc3', 1.84))
f.set_rail_voltage('aldo2', 3.3)
check_true('rail_aldo2_3v3', last_write(m, 0x93) == 28)
f.set_rail_voltage('cpusldo', 1.0)
check_true('rail_cpusldo_1v0', last_write(m, 0x98) == 10)
f.set_rail_voltage('dldo1', 3.3)
check_true('rail_dldo1_3v3', last_write(m, 0x99) == 28)
check_true('rail_dldo1_over', raises(f.set_rail_voltage, 'dldo1', 3.5))
f.set_rail_voltage('dldo2', 1.4)
check_true('rail_dldo2_1v4', last_write(m, 0x9A) == 18)
check_true('rail_unknown', raises(f.set_rail_voltage, 'foo', 1.0))
check_true('rail_below_range', raises(f.set_rail_voltage, 'aldo1', 0.3))

m.set_register(0x90, 0x00)
f.set_rail_enabled('bldo2', True)
check_true('rail_enable_bldo2', last_write(m, 0x90) == 0x20)
f.set_rail_enabled('dldo1', True)
check_true('rail_enable_dldo1_keeps_bldo2', last_write(m, 0x90) == 0xA0)
f.set_rail_enabled('bldo2', False)
check_true('rail_disable_bldo2', last_write(m, 0x90) == 0x80)
m.set_register(0x91, 0x00)
f.set_rail_enabled('dldo2', True)
check_true('rail_enable_dldo2', last_write(m, 0x91) == 0x01)

m.set_register(0x81, 0x00)
f.configure_dcdc('dcdc3', True, True)
check_true('dcdc3_always_pwm', last_write(m, 0x81) == 0x10)
check_true('dcdc3_dvm', last_write(m, 0x84) & 0x80 == 0x80)
check_true('dcdc1_dvm_rejected', raises(f.configure_dcdc, 'dcdc1', False, True))
m.set_register(0x80, 0x0F)
f.configure_dcdc_global(True, True, True, True, 240)
check_true('dcdc_global_en', last_write(m, 0x80) == 0x6F)
check_true('dcdc_global_pwm', last_write(m, 0x81) == (0x10 | 0xC3))

# --- System ---------------------------------------------------------------

m.set_register(0x10, 0x30)
f.set_off_discharge(False)
check_true('off_discharge_off_keeps_reserved', last_write(m, 0x10) == 0x10)
f.power_off()
check_true('power_off_sets_bit0', last_write(m, 0x10) == 0x11)
m.set_register(0x10, 0x30)
f.restart()
check_true('restart_sets_bit1', last_write(m, 0x10) == 0x32)
m.set_register(0x26, 0x08)
f.sleep()
check_true('sleep_bit0', last_write(m, 0x26) == 0x09)
m.set_register(0x26, 0x08)
f.wakeup()
check_true('wakeup_bit1', last_write(m, 0x26) == 0x0A)
m.set_register(0x26, 0x03)
f.configure_sleep(True, False, True)
check_true('configure_sleep', last_write(m, 0x26) == 0x17)

m.set_register(0x20, 0x21)
m.set_register(0x21, 0x81)
check_true('power_on_source', f.power_on_source() == 0x21)
check_true('power_off_source', f.power_off_source() == 0x81)

m.set_register(0x27, 0xC0)
m.set_register(0x22, 0x04)
m.set_register(0x10, 0x30)
f.configure_power_key(128, 10, 2.5, True, True, True)
check_true('power_key_level', last_write(m, 0x27) == (0xC0 | 0x30 | 0x0C | 0x00))
check_true('power_key_pwroff_en', last_write(m, 0x22) == 0x07)
check_true('power_key_16s', last_write(m, 0x10) == 0x34)
check_true('power_key_bad_value', raises(f.configure_power_key, 100))

m.set_register(0x22, 0x03)
m.set_register(0x23, 0x00)
f.configure_power_off(False, 3.3, True, 0x05)
check_true('power_off_die_clear', last_write(m, 0x22) == 0x03)
check_true('power_off_voff', last_write(m, 0x24) == 7)
check_true('power_off_dcdc_prot', last_write(m, 0x23) == 0x25)

m.set_register(0x25, 0x20)
m.set_register(0x10, 0x30)
f.configure_pwrok(True, 64, True, True, True)
check_true('pwrok_ctrl', last_write(m, 0x25) == (0x20 | 0x1F))
check_true('pwrok_restart_bit3', last_write(m, 0x10) == 0x38)

m.set_register(0x12, 0x00)
f.set_batfet_off_enable(True)
check_true('batfet_off_enable', last_write(m, 0x12) == 0x08)

m.set_register(0x28, 0xFF)
m.set_register(0x2B, 0x0F)
f.configure_fast_poweron(True, False, {'dcdc1': 1, 'dcdc4': None, 'dldo2': 2})
check_true('fast_poweron0', last_write(m, 0x28) == 0xFD)
check_true('fast_poweron3', last_write(m, 0x2B) == 0x8B)
check_true('fast_poweron_unknown_rail', raises(f.configure_fast_poweron, True, True, {'x': 1}))

m.set_register(0x19, 0x00)
m.set_register(0x18, 0x0A)
f.configure_watchdog(True, 128, 'power_cycle')
check_true('watchdog_ctrl', last_write(m, 0x19) == 0x37)
check_true('watchdog_enable_bit', last_write(m, 0x18) == 0x0B)
check_true('watchdog_bad_timeout', raises(f.configure_watchdog, True, 3))
m.set_register(0x19, 0x06)
f.feed_watchdog()
check_true('feed_watchdog_bit3', last_write(m, 0x19) == 0x0E)

f.configure_battery_warning(15, 5)
check_true('battery_warning', last_write(m, 0x1A) == 0xA5)
check_true('battery_warning_range', raises(f.configure_battery_warning, 25, 1))
m.set_register(0x18, 0x02)
f.set_gauge_enabled(True)
check_true('gauge_enable', last_write(m, 0x18) == 0x0A)
m.set_register(0x17, 0x00)
f.reset_gauge()
check_true('reset_gauge_bit3', last_write(m, 0x17) == 0x08)
m.set_register(0x17, 0x00)   # command bit self-clears on real hardware
f.reset_gauge(True)
check_true('reset_gauge_full_bit2', last_write(m, 0x17) == 0x04)

m.set_register(0x1B, 0x00)
f.set_gpio1(True)
check_true('gpio1_low', last_write(m, 0x1B) == 0x04)
f.set_gpio1(False)
check_true('gpio1_hiz', last_write(m, 0x1B) == 0x00)

f.write_data_buffer(2, 0xAB)
check_true('data_buffer_write', last_write(m, 0x06) == 0xAB)
check_true('data_buffer_read', f.read_data_buffer(2) == 0xAB)
check_true('data_buffer_index', raises(f.read_data_buffer, 4))

# --- Interrupts -----------------------------------------------------------

m.set_register(0x40, 0xFF, 0xFC, 0x5F)
f.disable_interrupt(AXP2101Full.SOURCE_VBUS_INSERT | AXP2101Full.SOURCE_CHARGE_DONE)
check_true('irq_disable_en1', last_write(m, 0x41) == 0x7C)
check_true('irq_disable_en2', last_write(m, 0x42) == 0x4F)
f.enable_interrupt(AXP2101Full.SOURCE_VBUS_INSERT | AXP2101Full.SOURCE_PWRON_SHORT)
check_true('irq_enable_en1', last_write(m, 0x41) == 0xFC)
m.set_register(0x40, 0x00, 0x00, 0x00)
f.enable_interrupt(AXP2101Full.SOURCE_SOC_WARN_L2)
check_true('irq_enable_en0_only', last_write(m, 0x40) == 0x80)

del m.writes[:]
m.set_register(0x48, 0x10, 0x88, 0x01)
st = f.poll_interrupt()
check_true('poll_interrupt_value', st == 0x018810)
check_true('poll_interrupt_clears_seen_bits',
           m.writes.count(bytes([0x48, 0x10])) == 1 and m.writes.count(bytes([0x49, 0x88])) == 1
           and m.writes.count(bytes([0x4A, 0x01])) == 1)
del m.writes[:]
m.set_register(0x48, 0x00, 0x00, 0x00)
check_true('poll_interrupt_idle', f.poll_interrupt() == 0
           and not [w for w in m.writes if len(w) == 2 and 0x48 <= w[0] <= 0x4A])

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
