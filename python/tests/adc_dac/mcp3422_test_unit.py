"""Unit test for the MCP3422 — runs without hardware using the I2C mock.

Verifies configuration bytes, result decoding and sign extension for all four
resolutions, one-shot and continuous conversion, RDY polling and timeouts,
gain / resolution handling, scans, general calls and the derived values by
observing what the driver writes to the mock.
"""

import sys

from periph.connection.i2c_mock import I2CConnectionMock
import periph.chips.adc_dac.mcp3422 as m
from periph.chips.adc_dac.mcp3422 import MCP3422Minimal, MCP3422Full

m._wait = lambda seconds: None   # skip the conversion-time waits

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


def frame(raw, bits, rdy=0, cfg=0x00):
    n = 3 if bits == 18 else 2
    return (raw & ((1 << (8 * n)) - 1)).to_bytes(n, 'big') + bytes([cfg | (0x80 if rdy else 0)])


def mock_with(*frames):
    mock = I2CConnectionMock()
    for f in frames:
        mock.queue_read(f)
    return mock


def written(mock):
    return [w[0] for w in mock.writes]


def near(a, b, tol=1e-9):
    return abs(a - b) < tol


# --- init writes the default config: one-shot, 16 bit, x1, CH1, RDY = 0
mock = I2CConnectionMock()
chip = MCP3422Full(mock)
check_true('init_writes_default_config', mock.writes == [bytes([0x08])])

# --- one-shot config bytes (RDY = 1, O/C = 0)
mock = mock_with(frame(0, 16), frame(0, 16))
chip = MCP3422Minimal(mock)
chip.read_raw(0)
chip.read_raw(1)
check_true('one_shot_config_ch1', written(mock)[1] == 0x88)
check_true('one_shot_config_ch2', written(mock)[2] == 0xA8)
mock = mock_with(frame(0, 16))
chip = MCP3422Minimal(mock)
chip.read_raw(7)
check_true('channel_clamped_high', written(mock)[1] == 0xA8)
mock = mock_with(frame(0, 16))
chip = MCP3422Minimal(mock)
chip.read_raw(-3)
check_true('channel_clamped_low', written(mock)[1] == 0x88)

# --- decoding and sign extension at every resolution
def decode_case(bits, raw):
    mock = mock_with(frame(raw, bits))
    chip = MCP3422Full(mock)
    chip.set_resolution(bits)
    return chip.read_raw(0)

check_true('decode_16_positive', decode_case(16, 12345) == 12345)
check_true('decode_16_negative', decode_case(16, -12345) == -12345)
check_true('decode_16_min', decode_case(16, -32768) == -32768)
check_true('decode_12_negative', decode_case(12, -100) == -100)
check_true('decode_12_max', decode_case(12, 2047) == 2047)
check_true('decode_14_negative', decode_case(14, -8192) == -8192)
check_true('decode_14_positive', decode_case(14, 8191) == 8191)
check_true('decode_18_positive', decode_case(18, 131071) == 131071)
check_true('decode_18_negative', decode_case(18, -131072) == -131072)
check_true('decode_18_small_negative', decode_case(18, -1) == -1)
# the sign bits repeat above the resolution (datasheet result layout)
mock = mock_with(bytes([0xFF, 0x9C, 0x00]))   # -100 at 12 bit with the sign repeated in byte0's upper nibble
chip = MCP3422Full(mock)
chip.set_resolution(12)
check_true('decode_12_ignores_repeated_sign_bits', chip.read_raw(0) == -100)
mock = mock_with(bytes([0xFC, 0x00, 0x05, 0x00]))   # byte0's upper six bits are not data: only D17..D0 count
chip = MCP3422Full(mock)
chip.set_resolution(18)
check_true('decode_18_masks_upper_bits', chip.read_raw(0) == 5)

# --- read_voltage = raw * lsb / gain
mock = mock_with(frame(16384, 16))
chip = MCP3422Minimal(mock)
check_true('voltage_x1', near(chip.read_voltage(0), 16384 * 2 * 2.048 / 65536))
mock = mock_with(frame(-32768, 16))
chip = MCP3422Full(mock)
chip.set_gain(8)
check_true('voltage_full_scale_x8', near(chip.read_voltage(0), -0.256))

# --- read_all converts CH1 then CH2
mock = mock_with(frame(100, 16), frame(200, 16))
chip = MCP3422Minimal(mock)
vals = chip.read_all()
check_true('read_all_two_values', len(vals) == 2 and near(vals[0], 100 * 2 * 2.048 / 65536) and near(vals[1], 200 * 2 * 2.048 / 65536))
check_true('read_all_channel_order', written(mock)[1:] == [0x88, 0xA8])

# --- RDY polling: a stale frame (RDY = 1) is re-read; timeout raises
mock = mock_with(frame(1, 16, rdy=1), frame(1, 16, rdy=1), frame(777, 16))
chip = MCP3422Minimal(mock)
check_true('polls_until_ready', chip.read_raw(0) == 777)
mock = I2CConnectionMock()
for _ in range(200):
    mock.queue_read(frame(1, 16, rdy=1))
chip = MCP3422Minimal(mock)
try:
    chip.read_raw(0)
    check_true('timeout_raises', False)
except TimeoutError:
    check_true('timeout_raises', True)

# --- Full configuration
chip = MCP3422Full(mock_with())
try:
    chip.set_resolution(10)
    check_true('bad_resolution_raises', False)
except ValueError:
    check_true('bad_resolution_raises', True)
try:
    chip.set_gain(3)
    check_true('bad_gain_raises', False)
except ValueError:
    check_true('bad_gain_raises', True)
chip.set_resolution(18)
chip.set_gain(8)
check_true('getters', chip.get_resolution() == 18 and chip.get_gain() == 8)
mock = mock_with(frame(0, 18))
chip = MCP3422Full(mock)
chip.set_resolution(18)
chip.set_gain(8)
chip.read_raw(0)
check_true('one_shot_config_18bit_x8', written(mock)[-1] == 0x8F)

# --- derived values
chip = MCP3422Full(mock_with())
check_true('lsb_16', near(chip.lsb(), 62.5e-6))
chip.set_resolution(12)
check_true('lsb_12', near(chip.lsb(), 1e-3))
chip.set_resolution(18)
check_true('lsb_18', near(chip.lsb(), 15.625e-6))
chip.set_gain(4)
check_true('full_scale_x4', near(chip.full_scale(), 0.512))

# --- continuous mode
mock = mock_with(frame(5, 12), frame(300, 12), frame(301, 12))
chip = MCP3422Full(mock)
chip.set_resolution(12)
chip.start_continuous()
check_true('start_continuous_config', written(mock)[-1] == 0x10 and chip.is_continuous())
check_true('read_continuous_discards_first_sample', chip.read_continuous_raw() == 300)
check_true('read_continuous_next', chip.read_continuous_raw() == 301)
vmock = mock_with(frame(5, 16), frame(16384, 16))
vchip = MCP3422Full(vmock)
vchip.start_continuous()
check_true('read_continuous_voltage', near(vchip.read_continuous(), 16384 * 2 * 2.048 / 65536))
chip.start_continuous(1)
check_true('start_continuous_channel', written(mock)[-1] == 0x30)
chip.stop_continuous()
check_true('stop_continuous_config', written(mock)[-1] == 0x20 and not chip.is_continuous())
try:
    chip.read_continuous_raw()
    check_true('read_continuous_requires_mode', False)
except RuntimeError:
    check_true('read_continuous_requires_mode', True)

# changing gain in continuous mode rewrites the config and discards one sample
mock = mock_with(frame(1, 12), frame(2, 12), frame(3, 12), frame(4, 12))
chip = MCP3422Full(mock)
chip.set_resolution(12)
chip.start_continuous()
chip.read_continuous_raw()
n = len(mock.writes)
chip.set_gain(2)
check_true('gain_change_rewrites_config', len(mock.writes) == n + 1 and written(mock)[-1] == 0x11)
check_true('gain_change_discards_sample', chip.read_continuous_raw() == 4)

# --- read_config / is_ready
mock = mock_with(frame(0, 16, rdy=1, cfg=0x08), frame(0, 16, rdy=0, cfg=0x08))
chip = MCP3422Full(mock)
check_true('read_config', chip.read_config() == 0x88)
check_true('is_ready', chip.is_ready() is True)

# --- read_scan
mock = mock_with(frame(10, 16), frame(20, 16), frame(30, 16))
chip = MCP3422Full(mock)
vals = chip.read_scan([1, 0, 1])
check_true('read_scan_order', written(mock)[1:] == [0xA8, 0x88, 0xA8] and len(vals) == 3)

# --- general calls
mock = mock_with()
chip = MCP3422Full(mock)
chip.set_resolution(18)
chip.set_gain(8)
chip.general_call_reset()
check_true('general_call_reset_bytes', mock.writes[-1] == bytes([0x00, 0x06]))
check_true('general_call_reset_state', chip.get_resolution() == 12 and chip.get_gain() == 1 and chip.is_continuous())
mock = mock_with(frame(321, 16))
chip = MCP3422Full(mock)
chip.general_call_convert()
check_true('general_call_convert_bytes', mock.writes[-1] == bytes([0x00, 0x08]))
check_true('read_pending_raw', chip.read_pending_raw() == 321)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
