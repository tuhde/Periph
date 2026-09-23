"""Unit test for the TMP117 — runs without hardware using the I2C mock.

Verifies the DEVICE_ID identity check, 0.0078125°C two's-complement
decoding/encoding (TEMP_RESULT, limits, offset), CONFIGURATION
read-modify-write behavior (mode/averaging/cycle, one-shot, Alert
configuration, soft reset), EEPROM unlock/busy/scratch access, and
poll_interrupt's status mask.
"""

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.temperature.tmp117 import (
    TMP117Minimal, TMP117Full, SOURCE_HIGH, SOURCE_LOW)

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


class WordMock(I2CConnectionMock):
    """Word-addressed variant of the byte-slot mock.

    TMP117 registers are 16 bits wide at consecutive pointer values, so the
    shared mock's byte-slot model would overlap them. Here each pointer owns
    one 16-bit word.
    """

    def __init__(self):
        super().__init__()
        self.words = {0x00: 0x8000, 0x01: 0x0220, 0x02: 0x6000, 0x03: 0x8000, 0x0F: 0x1117}

    def write(self, data):
        data = bytes(data)
        self.writes.append(data)
        if len(data) == 3:
            self.words[data[0]] = (data[1] << 8) | data[2]

    def write_read(self, data, n):
        self.writes.append(bytes(data))
        word = self.words.get(data[0], 0)
        return bytes([(word >> 8) & 0xFF, word & 0xFF])


def writes_to(mock, reg):
    return [w for w in mock.writes if len(w) >= 2 and w[0] == reg]


REG_TEMP = 0x00
REG_CONFIG = 0x01
REG_THIGH = 0x02
REG_TLOW = 0x03
REG_EEPROM_UL = 0x04
REG_EEPROM1 = 0x05
REG_EEPROM2 = 0x06
REG_OFFSET = 0x07
REG_EEPROM3 = 0x08

# --- Identity check -------------------------------------------------------
mock = WordMock()
sensor = TMP117Minimal(mock)
check_true('init_no_register_writes', all(len(w) == 1 for w in mock.writes))
check_true('init_reads_device_id', mock.writes == [bytes([0x0F])])

bad = WordMock()
bad.words[0x0F] = 0x0118
try:
    TMP117Minimal(bad)
    check_true('init_rejects_wrong_device_id', False)
except ValueError:
    check_true('init_rejects_wrong_device_id', True)

rev = WordMock()
rev.words[0x0F] = 0x2117                    # different silicon revision, same DID
TMP117Minimal(rev)
check_true('init_ignores_revision', True)

# --- Minimal: temperature decoding ---------------------------------------
mock.words[REG_TEMP] = 0x0C80               # 3200 * 0.0078125 = 25.0
check_true('temperature_positive', sensor.read_temperature() == 25.0)
mock.words[REG_TEMP] = 0xFFFF
check_true('temperature_minus_lsb', sensor.read_temperature() == -0.0078125)
mock.words[REG_TEMP] = 0xF380               # -3200
check_true('temperature_negative', sensor.read_temperature() == -25.0)
mock.words[REG_TEMP] = 0x8000
check_true('temperature_power_up_sentinel', sensor.read_temperature() == -256.0)
mock.words[REG_TEMP] = 0x7FFF
check_true('temperature_max', sensor.read_temperature() == 255.9921875)

# --- Full: limits and offset ---------------------------------------------
full = TMP117Full(mock)
full.set_high_limit(30.0)
check_true('set_high_limit_raw', mock.words[REG_THIGH] == 0x0F00)
check_true('get_high_limit', full.get_high_limit() == 30.0)
full.set_low_limit(-10.25)
check_true('set_low_limit_raw', mock.words[REG_TLOW] == 0xFAE0)
check_true('get_low_limit', full.get_low_limit() == -10.25)
full.set_low_limit(0.004)                   # rounds to the nearest LSB
check_true('limit_rounding', mock.words[REG_TLOW] == 0x0001)
full.set_high_limit(1000.0)
check_true('limit_clamp_high', mock.words[REG_THIGH] == 0x7FFF)
full.set_low_limit(-1000.0)
check_true('limit_clamp_low', mock.words[REG_TLOW] == 0x8000)
full.set_temperature_offset(-0.5)
check_true('set_offset_raw', mock.words[REG_OFFSET] == 0xFFC0)
check_true('get_offset', full.get_temperature_offset() == -0.5)

# --- Full: conversion configuration --------------------------------------
mock.words[REG_CONFIG] = 0x0220
check_true('get_config_default', full.get_config() == ('continuous', 8, 1.0))
full.configure(mode='shutdown', averaging=64, cycle_seconds=16.0)
check_true('configure_shutdown_raw', mock.words[REG_CONFIG] == 0x07E0)
check_true('get_config_shutdown', full.get_config() == ('shutdown', 64, 16.0))
check_true('is_shutdown', full.is_shutdown())
full.configure(mode='continuous', averaging=0, cycle_seconds=0.01)
check_true('configure_fastest_raw', mock.words[REG_CONFIG] == 0x0000)
check_true('not_shutdown', not full.is_shutdown())
full.configure(cycle_seconds=0.3)           # nearest step is 250 ms
check_true('configure_nearest_cycle', mock.words[REG_CONFIG] == 0x0120)
full.configure(mode='one_shot', averaging=32, cycle_seconds=2.0)  # nearest: 1 s
check_true('configure_one_shot_raw', mock.words[REG_CONFIG] == 0x0E40)
check_true('get_config_one_shot', full.get_config() == ('one_shot', 32, 1.0))
mock.words[REG_CONFIG] = 0x0800             # MOD=10 reads as continuous
check_true('get_config_mod_10', full.get_config()[0] == 'continuous')

# Configure preserves the Alert bits and never writes the read-only flags.
mock.words[REG_CONFIG] = 0xF01C             # all flags + T/nA|POL|DR/Alert
full.configure()
check_true('configure_preserves_alert_bits', mock.words[REG_CONFIG] == 0x023C)
try:
    full.configure(mode='bogus')
    check_true('configure_rejects_mode', False)
except ValueError:
    check_true('configure_rejects_mode', True)
try:
    full.configure(averaging=16)
    check_true('configure_rejects_averaging', False)
except ValueError:
    check_true('configure_rejects_averaging', True)

mock.words[REG_CONFIG] = 0xE660             # flags + MOD=01 CONV=100 AVG=11
full.trigger_one_shot()
check_true('trigger_one_shot', mock.words[REG_CONFIG] == 0x0E60)

mock.words[REG_CONFIG] = 0x2220
check_true('is_data_ready', full.is_data_ready())
mock.words[REG_CONFIG] = 0x0220
check_true('is_not_data_ready', not full.is_data_ready())

# --- Full: soft reset -----------------------------------------------------
mock.writes.clear()
full.reset()
check_true('reset_write', writes_to(mock, REG_CONFIG)[-1] == bytes([REG_CONFIG, 0x00, 0x02]))

# --- Full: EEPROM ---------------------------------------------------------
full.unlock_eeprom()
check_true('unlock_eeprom', mock.words[REG_EEPROM_UL] == 0x8000)
full.lock_eeprom()
check_true('lock_eeprom', mock.words[REG_EEPROM_UL] == 0x0000)
mock.words[REG_EEPROM_UL] = 0x4000
check_true('is_eeprom_busy', full.is_eeprom_busy())
mock.words[REG_EEPROM_UL] = 0x8000
check_true('is_eeprom_not_busy', not full.is_eeprom_busy())

mock.words[REG_EEPROM1] = 0x1111
mock.words[REG_EEPROM2] = 0x2222
mock.words[REG_EEPROM3] = 0x3333
check_true('read_scratch_1', full.read_eeprom_scratch(1) == 0x1111)
check_true('read_scratch_2', full.read_eeprom_scratch(2) == 0x2222)
check_true('read_scratch_3', full.read_eeprom_scratch(3) == 0x3333)
try:
    full.read_eeprom_scratch(4)
    check_true('read_scratch_rejects_slot', False)
except ValueError:
    check_true('read_scratch_rejects_slot', True)
full.write_eeprom_scratch(2, 0xBEEF)
check_true('write_scratch_2', mock.words[REG_EEPROM2] == 0xBEEF)
for slot in (1, 3):
    try:
        full.write_eeprom_scratch(slot, 0)
        check_true('write_scratch_rejects_%d' % slot, False)
    except ValueError:
        check_true('write_scratch_rejects_%d' % slot, mock.words[REG_EEPROM1] == 0x1111 and
                   mock.words[REG_EEPROM3] == 0x3333)

# --- Full: Alert configuration -------------------------------------------
mock.words[REG_CONFIG] = 0x0220
full.configure_alert(mode='therm', polarity='active_high', pin_function='data_ready')
check_true('configure_alert_bits', mock.words[REG_CONFIG] == 0x023C)
full.configure_alert()
check_true('configure_alert_defaults', mock.words[REG_CONFIG] == 0x0220)
try:
    full.configure_alert(pin_function='bogus')
    check_true('configure_alert_rejects_invalid', False)
except ValueError:
    check_true('configure_alert_rejects_invalid', True)

# --- Full: poll_interrupt -------------------------------------------------
mock.words[REG_CONFIG] = 0x2220
check_true('poll_interrupt_none', full.poll_interrupt() == 0)
mock.words[REG_CONFIG] = 0x8220
check_true('poll_interrupt_high', full.poll_interrupt() == SOURCE_HIGH)
mock.words[REG_CONFIG] = 0x4220
check_true('poll_interrupt_low', full.poll_interrupt() == SOURCE_LOW)
mock.words[REG_CONFIG] = 0xC220
check_true('poll_interrupt_both', full.poll_interrupt() == SOURCE_HIGH | SOURCE_LOW)

print('===DONE: %d passed, %d failed===' % (passed, failed))
