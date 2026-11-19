from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.led.pca9685 import PCA9685Minimal, PCA9685Full

passed = 0
failed = 0


def check(label, condition):
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1


def raises(fn):
    try:
        fn()
    except ValueError:
        return True
    return False


class Mock(I2CConnectionMock):
    # Register-pointer writes of read_reg() are not data writes; hide them from comparisons.
    @property
    def data(self):
        return [w for w in self.writes if len(w) > 1]


c = Mock()
d = PCA9685Full(c)
check('init_sequence', c.data == [bytes([0x00, 0x30]), bytes([0xFE, 5]), bytes([0x00, 0x20])])

c.writes.clear()
d.set_duty(0, 0.5)
check('duty_half', c.data == [bytes([0x06, 0, 0, 0x00, 0x08])])
d.set_duty(15, 0.0)
check('duty_zero_full_off', c.data[-1] == bytes([0x42, 0, 0, 0, 0x10]))
d.set_duty(1, 1.0)
check('duty_one_full_on', c.data[-1] == bytes([0x0A, 0, 0x10, 0, 0]))
check('duty_range', raises(lambda: d.set_duty(16, 0.5)) and raises(lambda: d.set_duty(0, 1.1))
      and raises(lambda: d.set_duty(-1, 0.5)))

d.set_pwm(2, 409, 1228)
check('set_pwm', c.data[-1] == bytes([0x0E, 0x99, 0x01, 0xCC, 0x04]))
check('get_pwm', d.get_pwm(2) == (409, 1228))
check('pwm_range', raises(lambda: d.set_pwm(0, 4096, 1)) and raises(lambda: d.set_pwm(0, 5, 5)))

d.set_full_on(3)
check('full_on', c.data[-1] == bytes([0x12, 0, 0x10, 0, 0]))
d.set_full_off(3)
check('full_off', c.data[-1] == bytes([0x12, 0, 0, 0, 0x10]))
d.set_all_duty(0.25)
check('all_duty', c.data[-1] == bytes([0xFA, 0, 0, 0x00, 0x04]))
d.set_all_pwm(0, 100)
check('all_pwm', c.data[-1] == bytes([0xFA, 0, 0, 100, 0]))

c.set_register(0x00, 0x20)
c.writes.clear()
d.set_frequency(50)
check('set_frequency', c.data == [bytes([0x00, 0x30]), bytes([0xFE, 121]), bytes([0x00, 0x20])])
check('get_frequency', abs(d.get_frequency() - 25e6 / (4096 * 122)) < 1e-6)
d.set_frequency(1e6)
check('freq_clamp_min', c.data[-2] == bytes([0xFE, 3]))
d.set_frequency(1)
check('freq_clamp_max', c.data[-2] == bytes([0xFE, 255]))
c.set_register(0x00, 0xA0)
c.writes.clear()
d.set_frequency(200)
check('set_frequency_restart', c.data[-1] == bytes([0x00, 0xA0]) and c.data[1] == bytes([0xFE, 30]))
check('freq_range', raises(lambda: d.set_frequency(0)))

c.set_register(0xFE, 121)
d.set_pulse_width(15, 0.0015)
check('pulse_width', c.data[-1] == bytes([0x42, 0, 0, 307 & 0xFF, 307 >> 8]))

c.set_register(0x00, 0x20)
c.writes.clear()
d.sleep()
check('sleep', c.data[-1] == bytes([0x00, 0x30]))
c.set_register(0x00, 0xB0)
c.writes.clear()
d.wake()
check('wake_restart', c.data == [bytes([0x00, 0x20]), bytes([0x00, 0xA0])])
c.writes.clear()
d.restart()
check('restart', c.data[-1] == bytes([0x00, 0xA0]))

c.set_register(0x01, 0x04)
d.set_output_mode(True, True, 2)
check('output_mode', c.data[-1] == bytes([0x01, 0x12]))
d.set_output_mode()
check('output_mode_default', c.data[-1] == bytes([0x01, 0x04]))
check('output_mode_range', raises(lambda: d.set_output_mode(off_state=3)))
d.set_update_on_ack(True)
check('och_on', c.data[-1] == bytes([0x01, 0x0C]))
d.set_update_on_ack(False)
check('och_off', c.data[-1] == bytes([0x01, 0x04]))

c.set_register(0x00, 0x20)
c.writes.clear()
d.use_external_clock()
check('extclk', c.data == [bytes([0x00, 0x30]), bytes([0x00, 0x70]), bytes([0x00, 0x60])])

c.set_register(0x00, 0x20)
c.writes.clear()
d.set_allcall(True, 0x70)
check('allcall', c.data == [bytes([0x05, 0xE0]), bytes([0x00, 0x21])])
d.set_subaddress(2, 0x71, True)
check('subaddr', c.data[-2] == bytes([0x03, 0xE2]) and c.data[-1] == bytes([0x00, 0x25]))
check('subaddr_range', raises(lambda: d.set_subaddress(4, 0x71)) and raises(lambda: d.set_allcall(True, 0x80)))

c.writes.clear()
d.reset()
check('reset', c.data[0] == bytes([0x00, 0x10]) and c.data[-1] == bytes([0x00, 0x11]) and bytes([0xFE, 0x1E]) in c.writes)

g = I2CConnectionMock()
PCA9685Full.software_reset(g)
check('software_reset', g.writes == [bytes([0x06])])

c.set_register(0x00, 0x11, 0x04)
check('read_mode', d.read_mode() == (0x11, 0x04))
print('===DONE: %d passed, %d failed===' % (passed, failed))
