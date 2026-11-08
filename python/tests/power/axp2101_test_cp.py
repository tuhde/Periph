"""CircuitPython hardware test for the AXP2101 — runs against real hardware.

Same coverage as the MicroPython test, but using busio.I2C.
"""

import busio
import _testconfig as cfg
from periph.connection.i2c_circuitpython import I2CConnection
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


i2c = busio.I2C(cfg.SCL, cfg.SDA, frequency=cfg.FREQ)
i2c.try_lock()
i2c.unlock()
connection = I2CConnection(i2c, cfg.ADDR)

chip = AXP2101Minimal(connection)
check_true('construct_minimal', isinstance(chip, AXP2101Minimal))

v = chip.battery_voltage()
check_true('battery_voltage_plausible', 0.0 <= v <= 5.0)
pct = chip.battery_percent()
check_true('battery_percent_range', 0 <= pct <= 100)
state = chip.charge_state()
check_true('charge_state_range', 0 <= state <= 5)
check_true('vbus_present_is_bool', isinstance(chip.vbus_present(), bool))

full = AXP2101Full(connection)
check_true('construct_full', isinstance(full, AXP2101Full))
check_true('vbus_voltage_range', 0.0 <= full.vbus_voltage() <= 15.0)
check_true('system_voltage_range', 0.0 <= full.system_voltage() <= 6.0)
check_true('ts_voltage_range', 0.0 <= full.ts_voltage() <= 4.0)
check_true('die_temperature_range', -40.0 <= full.die_temperature() <= 150.0)
d = full.read_all()
check_true('read_all_keys', len(d) == 8)
s = full.status()
check_true('status_direction', s['battery_direction'] in ('standby', 'charge', 'discharge', 'reserved'))

enabled, volts = full.get_rail('dcdc1')
check_true('get_rail_dcdc1', isinstance(enabled, bool) and 1.4 <= volts <= 3.5)

# Round-trip a non-critical setting: the input voltage limit.
full.set_input_voltage_limit(4.36)
check_true('input_voltage_limit_roundtrip', (full._read_reg(0x15) & 0x0F) == 6)

# Data buffer round-trip.
full.write_data_buffer(0, 0x5A)
check_true('data_buffer_roundtrip', full.read_data_buffer(0) == 0x5A)

# Interrupt registers: enable, poll, restore.
full.enable_interrupt(AXP2101Full.SOURCE_VBUS_INSERT)
check_true('poll_interrupt_int', isinstance(full.poll_interrupt(), int))

print('===DONE: {} passed, {} failed==='.format(passed, failed))
