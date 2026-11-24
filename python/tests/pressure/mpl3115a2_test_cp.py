import busio
import time
import _testconfig as cfg
from periph.connection.i2c_circuitpython import I2CConnection
from periph.chips.pressure.mpl3115a2 import MPL3115A2Full

passed = 0
failed = 0

i2c = busio.I2C(cfg.SCL, cfg.SDA, frequency=cfg.FREQ)
connection = I2CConnection(i2c, cfg.ADDR)


def check_true(label, condition):
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1

sensor = MPL3115A2Full(connection)
sensor.init()
check_true('who_am_i', sensor.who_am_i() == 0xC4)
m = sensor.read()
check_true('read_pressure_range', 20000 <= m['pressure_pa'] <= 110000)
check_true('read_temperature_range', -40 < m['temperature_c'] < 85)
check_true('read_pressure_single', 20000 <= sensor.read_pressure() <= 110000)
check_true('read_temperature_single', -40 < sensor.read_temperature() < 85)
for ratio in (1, 8, 128):
    sensor.set_oversample(ratio)
    check_true('oversample_roundtrip_%d' % ratio, sensor.get_oversample() == ratio)
    check_true('read_at_osr_%d' % ratio, 20000 <= sensor.read_pressure() <= 110000)
sensor.set_oversample(16)
sensor.set_sea_level_pressure(101325)
alt = sensor.read_altitude()
check_true('read_altitude_range', -500 < alt < 9000)
sensor.set_mode(False)
sensor.start_continuous(0)
check_true('is_active', sensor.is_active())
c = sensor.read_continuous()
check_true('read_continuous', 20000 <= c['value'] <= 110000)
sensor.stop_continuous()
check_true('is_standby', not sensor.is_active())
sensor.fifo_setup(MPL3115A2Full.FIFO_CIRCULAR, 4)
sensor.start_continuous(0)
time.sleep(5)
ov, wm, cnt = sensor.fifo_status()
check_true('fifo_collects', cnt >= 2)
check_true('fifo_read_count', len(sensor.fifo_read()) >= 2)
sensor.stop_continuous()
sensor.fifo_setup(MPL3115A2Full.FIFO_OFF)
sensor.enable_interrupt(MPL3115A2Full.SOURCE_DRDY, 1)
sensor.disable_interrupt(MPL3115A2Full.SOURCE_DRDY)
sensor.reset()
check_true('read_after_reset', 20000 <= sensor.read_pressure() <= 110000)

print('===DONE: %d passed, %d failed===' % (passed, failed))
