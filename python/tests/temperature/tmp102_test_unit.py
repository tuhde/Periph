"""Unit test for the TMP102 — runs without hardware using the I2C mock.

Verifies 12/13-bit temperature decoding, limit encoding/decoding,
CONFIGURATION read-modify-write behavior (conversion rate, fault queue,
polarity, thermostat mode, extended mode, shutdown/wake), one-shot,
alert_state, poll_interrupt and the general-call reset.
"""

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.temperature.tmp102 import TMP102Minimal, TMP102Full

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
    """Word-addressed variant of the byte-slot mock (16-bit registers)."""

    def __init__(self):
        super().__init__()
        self.words = {0x00: 0x0000, 0x01: 0x60A0, 0x02: 0x4B00, 0x03: 0x5000}
        self.os_done = True

    def write(self, data):
        data = bytes(data)
        self.writes.append(data)
        if len(data) == 3:
            self.words[data[0]] = (data[1] << 8) | data[2]

    def write_read(self, data, n):
        self.writes.append(bytes(data))
        word = self.words.get(data[0], 0)
        if data[0] == 0x01:
            word = (word | 0x8000) if self.os_done else (word & 0x7FFF)
        return bytes([(word >> 8) & 0xFF, word & 0xFF])


def writes_to(mock, reg):
    return [w for w in mock.writes if len(w) == 3 and w[0] == reg]


mock = WordMock()
sensor = TMP102Minimal(mock)
check_true('init_no_bus_access', mock.writes == [])

mock.words[0x00] = 0x1900
check_true('temp_25c', sensor.read_temperature() == 25.0)
mock.words[0x00] = 0xE700
check_true('temp_minus25c', sensor.read_temperature() == -25.0)
mock.words[0x00] = 0x7FF0
check_true('temp_max', sensor.read_temperature() == 127.9375)
mock.words[0x00] = 0xC900
check_true('temp_min', sensor.read_temperature() == -55.0)
mock.words[0x00] = ((int(150 / 0.0625)) << 3) | 1
check_true('temp_extended_150c', sensor.read_temperature() == 150.0)
mock.words[0x00] = ((-int(100 / 0.0625)) << 3 & 0xFFFF) | 1
check_true('temp_extended_negative', sensor.read_temperature() == -100.0)

mock = WordMock()
full = TMP102Full(mock)
mock.words[0x00] = 0x1900
check_true('raw_count', full.read_raw() == 400)
check_true('read_config_default', full.read_config() == 0x60A0 | 0x8000)

full.set_conversion_rate(8)
cfg = mock.words[0x01]
check_true('rate_8hz_bits', (cfg & 0x00C0) == 0x00C0)
check_true('rate_preserves_reserved', (cfg & 0x6000) == 0x6000)
full.set_conversion_rate(0.25)
check_true('rate_025hz_bits', (mock.words[0x01] & 0x00C0) == 0x0000)
try:
    full.set_conversion_rate(2)
    check_true('rate_invalid_raises', False)
except ValueError:
    check_true('rate_invalid_raises', True)

full.set_fault_queue(6)
check_true('fault_queue_6', (mock.words[0x01] & 0x1800) == 0x1800)
full.set_fault_queue(2)
check_true('fault_queue_2', (mock.words[0x01] & 0x1800) == 0x0800)
try:
    full.set_fault_queue(3)
    check_true('fault_queue_invalid_raises', False)
except ValueError:
    check_true('fault_queue_invalid_raises', True)

full.set_polarity(True)
check_true('polarity_high', mock.words[0x01] & 0x0400)
full.set_polarity(False)
check_true('polarity_low', not (mock.words[0x01] & 0x0400))
full.set_thermostat_mode(True)
check_true('tm_interrupt', mock.words[0x01] & 0x0200)
full.set_thermostat_mode(False)
check_true('tm_comparator', not (mock.words[0x01] & 0x0200))
full.set_extended_mode(True)
check_true('em_on', mock.words[0x01] & 0x0010)
full.set_extended_mode(False)
check_true('em_off', not (mock.words[0x01] & 0x0010))
full.shutdown()
check_true('shutdown_sd', mock.words[0x01] & 0x0100)
full.wake()
check_true('wake_sd_clear', not (mock.words[0x01] & 0x0100))

full.write_config(0xFFFF)
check_true('write_config_masks_readonly', (mock.words[0x01] & 0x602F) == 0x6000)

mock = WordMock()
full = TMP102Full(mock)
full.set_t_high(30.0)
check_true('t_high_encoding', mock.words[0x03] == 0x1E00)
check_true('t_high_roundtrip', full.get_t_high() == 30.0)
full.set_t_low(-10.5)
check_true('t_low_negative_roundtrip', full.get_t_low() == -10.5)
full.set_t_high(500.0)
full.set_t_high(100.0)
check_true('t_high_12bit_100c', full.get_t_high() == 100.0)
full.set_extended_mode(True)
full.set_t_high(140.0)
check_true('t_high_extended_encoding', mock.words[0x03] == (2240 << 3))
check_true('t_high_extended_roundtrip', full.get_t_high() == 140.0)
full.set_t_high(1000.0)
check_true('t_high_clamped_150', full.get_t_high() == 150.0)

mock = WordMock()
full = TMP102Full(mock)
mock.words[0x01] = 0x60A0
mock.os_done = False
mock.words[0x00] = 0x1900
try:
    full.one_shot()
    check_true('one_shot_timeout', False)
except OSError:
    check_true('one_shot_timeout', True)
mock = WordMock()
full = TMP102Full(mock)
mock.words[0x00] = 0x1900
mock.writes = []
check_true('one_shot_value', full.one_shot() == 25.0)
cw = [w[1] for w in writes_to(mock, 0x01)]
check_true('one_shot_sets_os', any(b & 0x80 for b in cw))
check_true('one_shot_restores_running', not (mock.words[0x01] & 0x0100))

mock = WordMock()
full = TMP102Full(mock)
mock.words[0x01] = 0x6180
full.one_shot()
check_true('one_shot_stays_shutdown', mock.words[0x01] & 0x0100)

mock = WordMock()
full = TMP102Full(mock)
mock.words[0x01] = 0x6000 | 0x0020
check_true('alert_state_inactive_pol0', full.alert_state() is False)
mock.words[0x01] = 0x6000
check_true('alert_state_active_pol0', full.alert_state() is True)
mock.words[0x01] = 0x6400 | 0x0020
check_true('alert_state_active_pol1', full.alert_state() is True)
mock.words[0x01] = 0x6400
check_true('alert_state_inactive_pol1', full.alert_state() is False)
mock.writes = []
mock.words[0x01] = 0x6000
check_true('poll_interrupt_true', full.poll_interrupt() is True)
check_true('poll_interrupt_reads_temperature', bytes([0x00]) in mock.writes)

mock.writes = []
full.reset()
check_true('reset_general_call', mock.writes == [bytes([0x00, 0x06])])

print('===DONE: %d passed, %d failed===' % (passed, failed))
