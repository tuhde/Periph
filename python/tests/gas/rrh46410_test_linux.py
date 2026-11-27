import time
import os
from periph.connection.i2c_linux import I2CConnection
from periph.chips.gas.rrh46410 import RRH46410Full

passed = 0
failed = 0

I2C_BUS = int(os.environ.get('LINUX_I2C_BUS', '1'))
I2C_ADDR = int(os.environ.get('I2C_ADDR', '0x38'), 16)

connection = I2CConnection(I2C_BUS, I2C_ADDR)


def check_true(label, condition):
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1

sensor = RRH46410Full(connection)
sensor.init()
pid, fw = sensor.product_id()
check_true('product_id', pid == 0x2310)
check_true('firmware_version', len(fw) == 3)
check_true('tracking_number', len(sensor.tracking_number()) == 6)
check_true('mode_iaq', sensor.get_mode() == RRH46410Full.MODE_IAQ_2ND_GEN)
sensor.set_humidity(50)
check_true('status_ok', sensor.status()['raw'] == 0)
r = sensor.read()
check_true('read_iaq_range', 0.0 <= r['iaq'] <= 25.5)
check_true('read_tvoc_range', 0.0 <= r['tvoc_mg_m3'] <= 655.35)
check_true('read_eco2_range', 0 <= r['eco2_ppm'] <= 0xFFFF)
check_true('read_not_warmed_up', r['warmed_up'] is False)
check_true('wait_for_sample', sensor.wait_for_sample(5.0))
check_true('read_results', sensor.read_results() is not None)
check_true('poll_interrupt_false', sensor.poll_interrupt() is False)
hits = []
sensor.on_interrupt(hits.append)
time.sleep(7)
sensor.off_interrupt()
check_true('on_interrupt', len(hits) >= 1)
sensor.set_mode(RRH46410Full.MODE_PBAQ)
check_true('mode_pbaq', sensor.get_mode() == RRH46410Full.MODE_PBAQ)
r = sensor.read()
check_true('read_pbaq', 'tvoc_mg_m3' in r and 'iaq' not in r)
sensor.config_gpio(0x03)
sensor.set_gpio(0x01)
check_true('set_gpio', sensor.get_gpio() & 0x01)
sensor.clear_gpio(0x01)
check_true('clear_gpio', not sensor.get_gpio() & 0x01)
check_true('send_command', sensor.send_command(0x10, b'', 1)[0] == RRH46410Full.MODE_PBAQ)
sensor.suspend()
check_true('suspend', sensor.get_mode() == RRH46410Full.MODE_SUSPEND)
sensor.reset()
check_true('reset_suspend', sensor.get_mode() == RRH46410Full.MODE_SUSPEND)
print('===DONE: %d passed, %d failed===' % (passed, failed))
connection.close()
