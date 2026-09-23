"""Unit test for the MCP9808 — runs without hardware using the I2C mock.

Verifies the identity check, TA temperature/status decoding (positive,
negative, flag bits masked), 0.25°C boundary encoding/decoding, resolution
and hysteresis codes, CONFIG read-modify-write behavior (Shutdown, locks,
Alert configuration, INT_CLEAR), and poll_interrupt's status mask.
"""

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.temperature.mcp9808 import (
    MCP9808Minimal, MCP9808Full, SOURCE_LOWER, SOURCE_UPPER, SOURCE_CRITICAL)

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

    MCP9808 registers are 16 bits wide at consecutive pointer values
    (MANUFACTURER_ID at 0x06, DEVICE_ID at 0x07), so the shared mock's
    byte-slot model would overlap them. Here each pointer owns one 16-bit
    word; 1-byte accesses (RESOLUTION) use the word's low byte.
    """

    def __init__(self):
        super().__init__()
        self.words = {0x06: 0x0054, 0x07: 0x0400, 0x08: 0x0003}

    def write(self, data):
        data = bytes(data)
        self.writes.append(data)
        if len(data) == 3:
            self.words[data[0]] = (data[1] << 8) | data[2]
        elif len(data) == 2:
            self.words[data[0]] = data[1]

    def write_read(self, data, n):
        self.writes.append(bytes(data))
        word = self.words.get(data[0], 0)
        if n == 1:
            return bytes([word & 0xFF])
        return bytes([(word >> 8) & 0xFF, word & 0xFF])


def writes_to(mock, reg):
    return [w for w in mock.writes if len(w) >= 2 and w[0] == reg]


REG_CONFIG = MCP9808Minimal._REG_CONFIG
REG_TA = MCP9808Minimal._REG_TA

# --- Identity check -------------------------------------------------------
mock = WordMock()
sensor = MCP9808Minimal(mock)
check_true('init_no_register_writes', len(writes_to(mock, REG_CONFIG)) == 0 and
           all(len(w) == 1 for w in mock.writes))

bad = WordMock()
bad.words[0x06] = 0x1234
try:
    MCP9808Minimal(bad)
    check_true('init_rejects_wrong_manufacturer', False)
except ValueError:
    check_true('init_rejects_wrong_manufacturer', True)

bad = WordMock()
bad.words[0x07] = 0x0500
try:
    MCP9808Minimal(bad)
    check_true('init_rejects_wrong_device', False)
except ValueError:
    check_true('init_rejects_wrong_device', True)

rev = WordMock()
rev.words[0x07] = 0x0401  # revision byte is ignored
try:
    MCP9808Minimal(rev)
    check_true('init_ignores_revision', True)
except ValueError:
    check_true('init_ignores_revision', False)

# --- Temperature decoding -------------------------------------------------
mock.words[REG_TA] = 0x0194                 # 25.25°C
check_true('temperature_positive', sensor.read_temperature() == 25.25)
mock.words[REG_TA] = 0xE194                 # same value, all 3 flag bits set
check_true('temperature_masks_flags', sensor.read_temperature() == 25.25)
mock.words[REG_TA] = 0x1FF0                 # -1.0°C
check_true('temperature_negative', sensor.read_temperature() == -1.0)
mock.words[REG_TA] = 0x1E6C                 # -25.25°C
check_true('temperature_negative_fraction', sensor.read_temperature() == -25.25)
mock.words[REG_TA] = 0x0001                 # 0.0625°C
check_true('temperature_lsb', sensor.read_temperature() == 0.0625)

# --- Full: boundaries -----------------------------------------------------
mock = WordMock()
full = MCP9808Full(mock)
full.set_upper_limit(80.0)
check_true('upper_limit_encode', mock.words[0x02] == 0x0500)
check_true('upper_limit_decode', full.get_upper_limit() == 80.0)
full.set_lower_limit(-25.0)
check_true('lower_limit_encode', mock.words[0x03] == 0x1E70)
check_true('lower_limit_decode', full.get_lower_limit() == -25.0)
full.set_critical_limit(-5.1)               # rounds to -5.0
check_true('critical_limit_rounds', full.get_critical_limit() == -5.0)
full.set_critical_limit(22.13)              # rounds to 22.25
check_true('critical_limit_rounds_up', full.get_critical_limit() == 22.25)
full.set_upper_limit(1000.0)                # clamps to 255.75
check_true('limit_clamps_high', full.get_upper_limit() == 255.75)
full.set_lower_limit(-1000.0)               # clamps to -256.0
check_true('limit_clamps_low', full.get_lower_limit() == -256.0)

# --- Full: resolution -----------------------------------------------------
full.set_resolution(0.25)
check_true('resolution_write', writes_to(mock, 0x08)[-1] == bytes([0x08, 0x01]))
check_true('resolution_read', full.get_resolution() == 0.25)
try:
    full.set_resolution(0.3)
    check_true('resolution_rejects_invalid', False)
except ValueError:
    check_true('resolution_rejects_invalid', True)

# --- Full: hysteresis -----------------------------------------------------
mock.words[REG_CONFIG] = 0x0000
full.set_hysteresis(3.0)
check_true('hysteresis_write', mock.words[REG_CONFIG] == 0x0400)
check_true('hysteresis_read', full.get_hysteresis() == 3.0)
try:
    full.set_hysteresis(2.0)
    check_true('hysteresis_rejects_invalid', False)
except ValueError:
    check_true('hysteresis_rejects_invalid', True)

# --- Full: shutdown / wake ------------------------------------------------
mock.words[REG_CONFIG] = 0x0400
full.shutdown()
check_true('shutdown_sets_shdn_keeps_thyst', mock.words[REG_CONFIG] == 0x0500)
check_true('is_shutdown_true', full.is_shutdown())
full.wake()
check_true('wake_clears_shdn', mock.words[REG_CONFIG] == 0x0400)
check_true('is_shutdown_false', not full.is_shutdown())
mock.words[REG_CONFIG] = 0x0080             # CRIT_LOCK set
n = len(writes_to(mock, REG_CONFIG))
full.shutdown()
check_true('shutdown_noop_when_locked', len(writes_to(mock, REG_CONFIG)) == n)

# --- Full: locks ----------------------------------------------------------
mock.words[REG_CONFIG] = 0x0000
full.lock_critical_limit()
check_true('lock_critical_sets_bit', mock.words[REG_CONFIG] == 0x0080)
check_true('is_critical_locked', full.is_critical_limit_locked())
check_true('is_window_unlocked', not full.is_window_limits_locked())
mock.words[REG_CONFIG] = 0x0000
full.lock_window_limits()
check_true('lock_window_sets_bit', mock.words[REG_CONFIG] == 0x0040)
check_true('is_window_locked', full.is_window_limits_locked())

# --- Full: Alert configuration --------------------------------------------
mock.words[REG_CONFIG] = 0x0000
full.configure_alert(mode='critical_only', output='interrupt', polarity='active_high')
check_true('configure_alert_bits', mock.words[REG_CONFIG] == 0x0007)
full.configure_alert()
check_true('configure_alert_defaults', mock.words[REG_CONFIG] == 0x0000)
mock.words[REG_CONFIG] = 0x0040
try:
    full.configure_alert(output='interrupt')
    check_true('configure_alert_rejects_locked', False)
except RuntimeError:
    check_true('configure_alert_rejects_locked', True)
try:
    full.configure_alert(mode='bogus')
    check_true('configure_alert_rejects_invalid', False)
except ValueError:
    check_true('configure_alert_rejects_invalid', True)

mock.words[REG_CONFIG] = 0x0000
full.enable_alert()
check_true('enable_alert', mock.words[REG_CONFIG] == 0x0008)
full.disable_alert()
check_true('disable_alert', mock.words[REG_CONFIG] == 0x0000)

mock.words[REG_CONFIG] = 0x0019             # ALERT_STAT | ALERT_CNT | ALERT_MOD
check_true('is_alert_asserted', full.is_alert_asserted())
full.clear_interrupt()
# Written value keeps ALERT_CNT/ALERT_MOD, sets INT_CLEAR, never writes ALERT_STAT.
check_true('clear_interrupt_write', writes_to(mock, REG_CONFIG)[-1] == bytes([REG_CONFIG, 0x00, 0x29]))
mock.words[REG_CONFIG] = 0x0009
check_true('is_alert_not_asserted', not full.is_alert_asserted())

# --- Full: poll_interrupt -------------------------------------------------
mock.words[REG_TA] = 0x0194
check_true('poll_interrupt_none', full.poll_interrupt() == 0)
mock.words[REG_TA] = 0x2194
check_true('poll_interrupt_lower', full.poll_interrupt() == SOURCE_LOWER)
mock.words[REG_TA] = 0xC194
check_true('poll_interrupt_upper_critical', full.poll_interrupt() == SOURCE_UPPER | SOURCE_CRITICAL)

print('===DONE: %d passed, %d failed===' % (passed, failed))
