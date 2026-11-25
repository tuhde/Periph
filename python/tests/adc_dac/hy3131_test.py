import _testconfig as cfg
from machine import Pin, SPI

from periph.chips.adc_dac.hy3131 import HY3131Full, HY3131Minimal
from periph.connection.spi_micropython import SPIConnection

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


spi = SPI(cfg.SPI_ID, baudrate=cfg.FREQ, polarity=0, phase=0, sck=Pin(cfg.SCK), mosi=Pin(cfg.MOSI), miso=Pin(cfg.MISO))
cs = Pin(cfg.CS, Pin.OUT, value=1)
conn = SPIConnection(spi, cs)

adc_min = HY3131Minimal(conn)
adc = HY3131Full(conn)

raw = adc_min.read_raw()
check_true('Minimal.read_raw returns int', isinstance(raw, int))
check_true('Minimal.read_raw in 24-bit range', -8388608 <= raw <= 8388607)
v = adc_min.read_voltage()
check_true('Minimal.read_voltage returns float', isinstance(v, float))
check_true('Minimal.data_ready returns bool', isinstance(adc_min.data_ready(), bool))

adc.configure_ad1(osr=4, chop=2, input_gain=0, ref_gain=0, offset=0, buffers=0)
check_true('configure_ad1 accepted', True)
check_true('Full.read_raw after configure_ad1', isinstance(adc.read_raw(), int))
adc.set_ad1_input(10, 3, filter_r=2)
adc.set_ad1_reference(1, 1)
check_true('set_ad1_input/reference accepted', True)

tcode = adc.read_temperature_code()
check_true('read_temperature_code returns int', isinstance(tcode, int))
check_true('read_temperature returns float', isinstance(adc.read_temperature(tcode), float))

adc.enable_ad2(osr=5, input_gain=1)
check_true('Full.read_ad2_raw in 19-bit range', -262144 <= adc.read_ad2_raw() <= 262143)
adc.enable_lpf(bw=0)
check_true('Full.read_lpf_raw returns int', isinstance(adc.read_lpf_raw(), int))
adc.enable_rms()
check_true('Full.read_rms >= 0', adc.read_rms() >= 0)
adc.enable_peak_hold(source=0)
lo, hi = adc.read_peak()
check_true('Full.read_peak min <= max', lo <= hi)
adc.enable_interrupts(0x04)
check_true('Full.read_flags returns int', isinstance(adc.read_flags(), int))
adc.reset()
check_true('reset accepted', True)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
