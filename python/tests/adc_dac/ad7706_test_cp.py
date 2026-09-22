import _testconfig as cfg
import busio

from periph.chips.adc_dac.ad7706 import AD7706Full, AD7706Minimal
from periph.connection.spi_circuitpython import SPIConnection

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


spi = busio.SPI(clock=cfg.SCK, MOSI=cfg.MOSI, MISO=cfg.MISO)
while not spi.try_lock():
    pass
spi.configure(baudrate=cfg.FREQ, polarity=1, phase=1)
spi.unlock()

from digitalio import DigitalInOut
cs = DigitalInOut(cfg.CS)
cs.switch_to_output(value=True)
conn = SPIConnection(spi, cs)

adc_min = AD7706Minimal(conn, cfg.VREF, cfg.MCLK_HZ)
adc = AD7706Full(conn, cfg.VREF, cfg.MCLK_HZ, reset_pin=None)

raw = adc_min.read_raw()
check_true('Minimal.read_raw returns int', isinstance(raw, int))
check_true('Minimal.read_raw in [0, 65535]', 0 <= raw <= 65535)

v = adc_min.read_voltage()
check_true('Minimal.read_voltage returns float', isinstance(v, float))
check_true('Minimal.read_voltage in [-VREF, +VREF]', -cfg.VREF <= v <= cfg.VREF)

raw1 = adc.read_raw(channel=1)
check_true('Full.read_raw(1) returns int', isinstance(raw1, int))
check_true('Full.read_raw(1) in [0, 65535]', 0 <= raw1 <= 65535)

v1 = adc.read_voltage(channel=1)
check_true('Full.read_voltage(1) returns float', isinstance(v1, float))

raw2 = adc.read_raw(channel=2)
check_true('Full.read_raw(2) returns int', isinstance(raw2, int))
check_true('Full.read_raw(2) in [0, 65535]', 0 <= raw2 <= 65535)

v2 = adc.read_voltage(channel=2)
check_true('Full.read_voltage(2) returns float', isinstance(v2, float))

raw3 = adc.read_raw(channel=3)
check_true('Full.read_raw(3) returns int', isinstance(raw3, int))
check_true('Full.read_raw(3) in [0, 65535]', 0 <= raw3 <= 65535)

v3 = adc.read_voltage(channel=3)
check_true('Full.read_voltage(3) returns float', isinstance(v3, float))

adc.configure(channel=1, gain=2, bipolar=True, buffered=False, output_rate_hz=60)
check_true('configure(1, gain=2, ...) accepted', True)
adc.configure(channel=2, gain=4, bipolar=False, buffered=True, output_rate_hz=60)
check_true('configure(2, gain=4, ...) accepted', True)
adc.configure(channel=3, gain=4, bipolar=False, buffered=True, output_rate_hz=60)
check_true('configure(3, gain=4, ...) accepted', True)
adc.configure(channel=1, gain=128, bipolar=True, buffered=True, output_rate_hz=50)
check_true('configure(1, gain=128, ...) accepted', True)

adc.self_calibrate(channel=1)
check_true('self_calibrate(1) accepted', True)
adc.self_calibrate(channel=2)
check_true('self_calibrate(2) accepted', True)
adc.self_calibrate(channel=3)
check_true('self_calibrate(3) accepted', True)

adc.system_calibrate_zero(channel=1)
check_true('system_calibrate_zero(1) accepted', True)
adc.system_calibrate_full(channel=1)
check_true('system_calibrate_full(1) accepted', True)

off1 = adc.get_offset_calibration(channel=1)
check_true('get_offset_calibration(1) returns int', isinstance(off1, int))
check_true('get_offset_calibration(1) in [0, 2**24-1]', 0 <= off1 <= 0xFFFFFF)
adc.set_offset_calibration(off1, channel=1)
check_true('set_offset_calibration(1) accepted', True)

gain1 = adc.get_gain_calibration(channel=1)
check_true('get_gain_calibration(1) returns int', isinstance(gain1, int))
check_true('get_gain_calibration(1) in [0, 2**24-1]', 0 <= gain1 <= 0xFFFFFF)
adc.set_gain_calibration(gain1, channel=1)
check_true('set_gain_calibration(1) accepted', True)

off2 = adc.get_offset_calibration(channel=2)
check_true('get_offset_calibration(2) returns int', isinstance(off2, int))
gain2 = adc.get_gain_calibration(channel=2)
check_true('get_gain_calibration(2) returns int', isinstance(gain2, int))

off3 = adc.get_offset_calibration(channel=3)
check_true('get_offset_calibration(3) returns int', isinstance(off3, int))
gain3 = adc.get_gain_calibration(channel=3)
check_true('get_gain_calibration(3) returns int', isinstance(gain3, int))

adc.standby()
check_true('standby accepted', True)
adc.wakeup()
check_true('wakeup accepted', True)

spi.deinit()

print('===DONE: {} passed, {} failed==='.format(passed, failed))
