import busio
import _testconfig as cfg
from periph.connection.i2c_circuitpython import I2CConnection
from periph.chips.led.pca9685 import PCA9685Minimal, PCA9685Full

passed = 0
failed = 0

i2c = busio.I2C(cfg.SCL, cfg.SDA, frequency=cfg.FREQ)
connection = I2CConnection(i2c, cfg.ADDR)


def check(label, ok, extra=''):
    global passed, failed
    if ok:
        print('PASS ' + label)
        passed += 1
    else:
        print('FAIL ' + label + (': ' + extra if extra else ''))
        failed += 1


def attempt(label, fn):
    try:
        fn()
        check(label, True)
    except Exception as e:
        check(label, False, str(e))


pwm = PCA9685Minimal(connection)
check('constants', PCA9685Minimal.CHANNELS == 16)
attempt('set_duty', lambda: (pwm.set_duty(0, 0.5), pwm.set_duty(15, 0.0), pwm.set_duty(1, 1.0)))

full = PCA9685Full(connection)
full.set_pwm(2, 409, 1228)
check('get_pwm', full.get_pwm(2) == (409, 1228))
attempt('full_on_off', lambda: (full.set_full_on(3), full.set_full_off(3)))
attempt('all', lambda: (full.set_all_duty(0.1), full.set_all_pwm(0, 2048), full.set_all_duty(0.0)))
full.set_frequency(50)
check('frequency', abs(full.get_frequency() - 50.0) < 1.0)
attempt('pulse_width', lambda: full.set_pulse_width(15, 0.0015))
attempt('sleep_wake', lambda: (full.sleep(), full.wake(), full.restart()))
attempt('output_mode', lambda: (full.set_output_mode(False, False, 0), full.set_update_on_ack(False)))
attempt('allcall', lambda: full.set_allcall(False))
attempt('subaddress', lambda: full.set_subaddress(1, 0x71, False))
mode1, mode2 = full.read_mode()
check('read_mode', (mode1 & 0x10) == 0 and (mode2 & 0x04) == 0x04)
attempt('reset', full.reset)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
