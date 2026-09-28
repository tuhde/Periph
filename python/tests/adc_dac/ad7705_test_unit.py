import sys

from periph.connection.spi_mock import SPIConnectionMock
from periph.connection.output_pin import OutputPin
from periph.chips.adc_dac.ad7705 import AD7705Minimal, AD7705Full

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


class FakeOutputPin(OutputPin):
    def __init__(self):
        self.calls = []

    def set(self, high):
        self.calls.append(high)


# --- AD7705Minimal.__init__: Clock + Setup Register writes, self-calibrate ---
# mclk_hz=4_915_200 -> CLKDIV=1, CLK=1, FS1:FS0=00 (50 Hz) -> Clock reg = 0x0C
# (matches the spec's own worked example). Setup reg = MODE_SELF_CAL|GAIN_1|
# BIPOLAR|UNBUFFERED|FSYNC_RUN = 0x40.
connection = SPIConnectionMock()
sensor = AD7705Minimal(connection, vref=2.5, mclk_hz=4_915_200)
check_true('init_clock_write', connection.writes[0] == bytes([0x20, 0x0C]))
check_true('init_setup_write', connection.writes[1] == bytes([0x10, 0x40]))
check_true('init_waits_drdy', connection.writes[2] == bytes([0x08]))

try:
    AD7705Minimal(connection, vref=2.5, mclk_hz=123)
    check_true('init_rejects_bad_mclk', False)
except ValueError:
    check_true('init_rejects_bad_mclk', True)

# --- AD7705Minimal.read_raw / read_voltage: Channel 1, gain 1, bipolar ---
# Data Register CH1 read comm byte = REG_DATA|RW_READ|CH1 = 0x38.
# code=0xC000 (49152) -> bipolar: ((49152-32768)/32768)*(2.5/1) = 1.25 V
connection.set_register(0x38, [0xC0, 0x00])
check_true('read_raw', sensor.read_raw() == 0xC000)
check_true('read_voltage', abs(sensor.read_voltage() - 1.25) < 1e-9)

# --- AD7705Full.configure + read_voltage: per-channel independence ---
# Regression test for a driver bug found while writing this test: configure()
# only updated the shared _gain/_bipolar when channel==1, so read_voltage(2)
# silently converted using channel 1's gain/bipolar instead of channel 2's.
connection2 = SPIConnectionMock()
full = AD7705Full(connection2, vref=2.5, mclk_hz=4_915_200)
del connection2.writes[:]  # drop the init sequence, only assert on configure() below

# configure(channel=2, gain=4, bipolar=False, buffered=True, output_rate_hz=250):
# Clock reg CH2 (comm=0x21): CLKDIV=1,CLK=1,FS=index(250)=2 -> 0x0E
# Setup reg CH2 (comm=0x11): MODE_NORMAL|GAIN_4(0x10)|UNIPOLAR(0x04)|BUFFERED(0x02) = 0x16
full.configure(channel=2, gain=4, bipolar=False, buffered=True, output_rate_hz=250)
check_true('configure_ch2_clock', connection2.writes[0] == bytes([0x21, 0x0E]))
check_true('configure_ch2_setup', connection2.writes[1] == bytes([0x11, 0x16]))

# Data Register CH2 read comm = REG_DATA|RW_READ|CH2 = 0x39.
# code=0x8000 (32768), gain=4, unipolar -> (32768/65536)*(2.5/4) = 0.3125 V
connection2.set_register(0x39, [0x80, 0x00])
check_true('read_voltage_ch2_uses_own_gain', abs(full.read_voltage(channel=2) - 0.3125) < 1e-9)

# Channel 1 was never configured, so it must still use the __init__ default
# (gain 1, bipolar) — unaffected by channel 2's configure() above.
connection2.set_register(0x38, [0xC0, 0x00])
check_true('read_voltage_ch1_unaffected_by_ch2_configure', abs(full.read_voltage(channel=1) - 1.25) < 1e-9)

try:
    full.configure(channel=3, gain=1, bipolar=True, buffered=False, output_rate_hz=50)
    check_true('configure_rejects_bad_channel', False)
except ValueError:
    check_true('configure_rejects_bad_channel', True)

try:
    full.configure(channel=1, gain=3, bipolar=True, buffered=False, output_rate_hz=50)
    check_true('configure_rejects_bad_gain', False)
except ValueError:
    check_true('configure_rejects_bad_gain', True)

# --- self_calibrate: also regression-checks per-channel gain/bipolar use ---
# setup = MODE_SELF_CAL(0x40)|GAIN_4(0x10)|UNIPOLAR(0x04)|BUFFERED(0x02) = 0x56
# (channel 2's configured state from above, not channel 1's defaults)
del connection2.writes[:]
full.self_calibrate(channel=2)
check_true('self_calibrate_ch2_uses_own_state', connection2.writes[0] == bytes([0x11, 0x56]))

# --- system_calibrate_zero / system_calibrate_full: mode bits, channel 1 ---
del connection2.writes[:]
full.system_calibrate_zero(channel=1)
check_true('system_calibrate_zero', connection2.writes[0] == bytes([0x10, 0x80]))  # MODE_ZERO_SYS|GAIN_1|BIPOLAR
del connection2.writes[:]
full.system_calibrate_full(channel=1)
check_true('system_calibrate_full', connection2.writes[0] == bytes([0x10, 0xC0]))  # MODE_FULL_SYS|GAIN_1|BIPOLAR

# --- offset / gain calibration: 24-bit read/write ---
# Zero-Scale reg CH1 read comm = REG_OFFSET|RW_READ|CH1 = 0x68.
connection2.set_register(0x68, [0x12, 0x34, 0x56])
check_true('get_offset_calibration', full.get_offset_calibration(channel=1) == 0x123456)

del connection2.writes[:]
full.set_offset_calibration(0xABCDEF, channel=1)
check_true('set_offset_calibration', connection2.writes[0] == bytes([0x60, 0xAB, 0xCD, 0xEF]))

# Full-Scale reg CH1 read comm = REG_GAIN|RW_READ|CH1 = 0x78.
connection2.set_register(0x78, [0x01, 0x02, 0x03])
check_true('get_gain_calibration', full.get_gain_calibration(channel=1) == 0x010203)

del connection2.writes[:]
full.set_gain_calibration(0x040506, channel=1)
check_true('set_gain_calibration', connection2.writes[0] == bytes([0x70, 0x04, 0x05, 0x06]))

# --- standby / wakeup ---
del connection2.writes[:]
full.standby()
check_true('standby', connection2.writes[0] == bytes([0x04]))  # comm(COMM,WRITE,CH1)|STBY_SLEEP

del connection2.writes[:]
full.wakeup()
check_true('wakeup_clears_stby', connection2.writes[0] == bytes([0x00]))
check_true('wakeup_waits_drdy', connection2.writes[1] == bytes([0x08]))

# --- reset: requires a reset_pin, pulses it low then high ---
try:
    full.reset()
    check_true('reset_without_pin_raises', False)
except RuntimeError:
    check_true('reset_without_pin_raises', True)

reset_pin = FakeOutputPin()
connection3 = SPIConnectionMock()
with_reset = AD7705Full(connection3, vref=2.5, mclk_hz=4_915_200, reset_pin=reset_pin)
check_true('init_with_reset_pin_pulses', reset_pin.calls == [False, True])
reset_pin.calls.clear()
with_reset.reset()
check_true('reset_pulses_pin', reset_pin.calls == [False, True])

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
