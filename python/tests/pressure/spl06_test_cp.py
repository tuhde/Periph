import busio
import time
import _testconfig as cfg
from periph.connection.i2c_circuitpython import I2CConnection
from periph.chips.pressure.spl06 import SPL06Full

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

sensor = SPL06Full(connection)
sensor.init()
co = sensor.coefficients()
check_true('coefficients_decoded', len(co) == 9 and any(v != 0 for v in co.values()))
m = sensor.read()
check_true('read_pressure_range', 30000 <= m['pressure_pa'] <= 110000)
check_true('read_temperature_range', -40 < m['temperature_c'] < 85)
check_true('read_pressure_single', 30000 <= sensor.read_pressure() <= 110000)
check_true('read_temperature_single', -40 < sensor.read_temperature() < 85)
praw, traw = sensor.read_raw()
r = sensor.compensate(praw, traw, 16, 1)
check_true('compensate_raw', 30000 <= r['pressure_pa'] <= 110000)
check_true('altitude_range', -500 < sensor.altitude() < 9000)
for osr in (1, 8, 128):
    sensor.set_pressure_config(osr, 1)
    check_true('read_at_osr_%d' % osr, 30000 <= sensor.read_pressure() <= 110000)
sensor.set_pressure_config(16, 8)
sensor.set_temperature_config(1, 8)
sensor.start_continuous()
c = sensor.read_continuous()
check_true('read_continuous', 30000 <= c['pressure_pa'] <= 110000)
time.sleep(0.3)
check_true('data_ready', sensor.data_ready()[0])
sensor.stop()
sensor.flush_fifo()
sensor.enable_fifo()
sensor.start_continuous()
time.sleep(1)
sensor.stop()
check_true('fifo_not_empty', not sensor.fifo_status()[0])
entries = sensor.read_fifo()
check_true('fifo_read', len(entries) >= 4)
check_true('fifo_kinds', all(k in ('pressure', 'temperature') for k, _ in entries))
check_true('fifo_values', all(30000 <= v <= 110000 for k, v in entries if k == 'pressure'))
check_true('fifo_empty_after_read', sensor.fifo_status()[0])
sensor.disable_fifo()
sensor.enable_interrupt(SPL06Full.SOURCE_PRESSURE)
sensor.poll_interrupt()
sensor.read()
check_true('poll_interrupt_pressure', sensor.poll_interrupt() & SPL06Full.SOURCE_PRESSURE)
sensor.disable_interrupt(SPL06Full.SOURCE_PRESSURE)
sensor.reset()
check_true('read_after_reset', 30000 <= sensor.read_pressure() <= 110000)

print('===DONE: %d passed, %d failed===' % (passed, failed))
