import sys

from periph.connection.spi_mock import SPIConnectionMock
from periph.connection.output_pin import OutputPin
from periph.chips.adc_dac.ad7706 import AD7706Minimal, AD7706Full

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


# --- AD7706Minimal.__init__: Clock + Setup Register writes, self-calibrate ---
# mclk_hz=4_915_200 -> CLKDIV=1, CLK=1, FS1:FS0=00 (50 Hz) -> Clock reg = 0x0C.
# Setup reg = MODE_SELF_CAL|GAIN_1|BIPOLAR|UNBUFFERED|FSYNC_RUN = 0x40.
connection = SPIConnectionMock()
sensor = AD7706Minimal(connection, vref=2.5, mclk_hz=4_915_200)
check_true('init_clock_write', connection.writes[0] == bytes([0x20, 0x0C]))
check_true('init_setup_write', connection.writes[1] == bytes([0x10, 0x40]))
check_true('init_waits_drdy', connection.writes[2] == bytes([0x08]))

try:
    AD7706Minimal(connection, vref=2.5, mclk_hz=123)
    check_true('init_rejects_bad_mclk', False)
except ValueError:
    check_true('init_rejects_bad_mclk', True)

# --- AD7706Minimal.read_raw / read_voltage: Channel 1, gain 1, bipolar ---
connection.set_register(0x38, [0xC0, 0x00])
check_true('read_raw', sensor.read_raw() == 0xC000)
check_true('read_voltage', abs(sensor.read_voltage() - 1.25) < 1e-9)

# --- AD7706Full: three independent channels ---
# Regression tests for driver bugs found while writing this test: configure()
# only updated the shared gain/bipolar/buffered fields for channel 1, and
# read_voltage(channel) skipped the DRDY wait entirely (read the Data
# Register directly instead of delegating to read_raw(channel)).
connection2 = SPIConnectionMock()
full = AD7706Full(connection2, vref=2.5, mclk_hz=4_915_200)
del connection2.writes[:]

# configure(channel=2, gain=4, bipolar=False, buffered=True, output_rate_hz=250):
# Clock reg CH2 (comm=0x21) -> 0x0E, Setup reg CH2 (comm=0x11) -> 0x16.
full.configure(channel=2, gain=4, bipolar=False, buffered=True, output_rate_hz=250)
check_true('configure_ch2_clock', connection2.writes[0] == bytes([0x21, 0x0E]))
check_true('configure_ch2_setup', connection2.writes[1] == bytes([0x11, 0x16]))

# configure(channel=3, gain=8, bipolar=True, buffered=False, output_rate_hz=500):
# Channel 3 select = CH1:CH0=11 -> ch3=0x03.
# Clock reg CH3 (comm=0x23): CLKDIV=1,CLK=1,FS=index(500)=3 -> 0x0F
# Setup reg CH3 (comm=0x13): MODE_NORMAL|GAIN_8(0x18)|BIPOLAR|UNBUFFERED = 0x18
full.configure(channel=3, gain=8, bipolar=True, buffered=False, output_rate_hz=500)
check_true('configure_ch3_clock', connection2.writes[2] == bytes([0x23, 0x0F]))
check_true('configure_ch3_setup', connection2.writes[3] == bytes([0x13, 0x18]))

# Data Register reads: CH2 comm=0x39, CH3 comm=0x3B.
connection2.set_register(0x39, [0x80, 0x00])  # code=0x8000, gain=4, unipolar -> 0.3125 V
check_true('read_voltage_ch2_uses_own_gain', abs(full.read_voltage(channel=2) - 0.3125) < 1e-9)

connection2.set_register(0x3B, [0xE0, 0x00])  # code=0xE000, gain=8, bipolar -> 0.234375 V
check_true('read_voltage_ch3_uses_own_gain', abs(full.read_voltage(channel=3) - 0.234375) < 1e-9)

# Channel 1 was never configured, so it must still use the __init__ default
# (gain 1, bipolar) -- unaffected by channel 2/3's configure() above.
connection2.set_register(0x38, [0xC0, 0x00])
check_true('read_voltage_ch1_unaffected_by_others', abs(full.read_voltage(channel=1) - 1.25) < 1e-9)

try:
    full.configure(channel=4, gain=1, bipolar=True, buffered=False, output_rate_hz=50)
    check_true('configure_rejects_bad_channel', False)
except ValueError:
    check_true('configure_rejects_bad_channel', True)

# --- self_calibrate: also regression-checks per-channel gain/bipolar use ---
# setup = MODE_SELF_CAL(0x40)|GAIN_8(0x18)|BIPOLAR|UNBUFFERED = 0x58
# -- channel 3's configured state, not channel 1's defaults.
del connection2.writes[:]
full.self_calibrate(channel=3)
check_true('self_calibrate_ch3_uses_own_state', connection2.writes[0] == bytes([0x13, 0x58]))

# --- offset / gain calibration: 24-bit read/write, channel 3 ---
# Zero-Scale reg CH3 read comm = REG_OFFSET|RW_READ|CH3(0x03) = 0x6B.
connection2.set_register(0x6B, [0x12, 0x34, 0x56])
check_true('get_offset_calibration_ch3', full.get_offset_calibration(channel=3) == 0x123456)

del connection2.writes[:]
full.set_offset_calibration(0xABCDEF, channel=3)
check_true('set_offset_calibration_ch3', connection2.writes[0] == bytes([0x63, 0xAB, 0xCD, 0xEF]))

# --- standby / wakeup (channel-1-only) ---
del connection2.writes[:]
full.standby()
check_true('standby', connection2.writes[0] == bytes([0x04]))

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
with_reset = AD7706Full(connection3, vref=2.5, mclk_hz=4_915_200, reset_pin=reset_pin)
check_true('init_with_reset_pin_pulses', reset_pin.calls == [False, True])
reset_pin.calls.clear()
with_reset.reset()
check_true('reset_pulses_pin', reset_pin.calls == [False, True])

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
