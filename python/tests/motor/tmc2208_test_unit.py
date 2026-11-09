"""Unit test for the TMC2208 — runs without hardware using the UART mock.

Verifies the datagram format and CRC, echo handling, initialisation writes,
current and VACTUAL conversion, Full-class register fields and status decoding.
"""

from periph.connection.uart_mock import UARTConnectionMock
from periph.chips.motor.tmc2208 import TMC2208Minimal, TMC2208Full, _crc8

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


class Pin:
    def __init__(self):
        self.levels = []

    def set(self, high):
        self.levels.append(bool(high))


# CRC test vectors from the spec.
check_true('crc_write_gconf', _crc8(bytes([5, 0, 0x80, 0, 0, 1, 0xC1])) == 0x7F)
check_true('crc_read_ioin', _crc8(bytes([5, 0, 6])) == 0x6F)

mock = UARTConnectionMock()
motor = TMC2208Minimal(mock)
check_true('init_gconf', mock.last_write(0x00) == 0x1C1)
check_true('init_gstat_clear', mock.last_write(0x01) == 0x07)
check_true('init_tpowerdown', mock.last_write(0x11) == 20)
check_true('init_pwmconf', mock.last_write(0x70) == 0xC10D0024)
check_true('init_wrote_gconf_datagram',
           bytes([5, 0, 0x80, 0, 0, 1, 0xC1, 0x7F]) in mock.datagrams)
check_true('init_read_request_ioin', bytes([5, 0, 6, 0x6F]) in mock.datagrams)
chop = mock.last_write(0x6C)
check_true('init_chopconf_fields',
           (chop & 0xF) == 3 and ((chop >> 15) & 3) == 2
           and ((chop >> 4) & 7) == 5 and ((chop >> 24) & 0xF) == 4
           and (chop >> 28) & 1 == 1)
ih = mock.last_write(0x10)
check_true('init_current_0_5a', ((ih >> 8) & 0x1F) == 9 and (ih & 0x1F) == 2)
check_true('init_ihold_delay', ((ih >> 16) & 0xF) == 6)

# set_current(1.0): CS = round(1.0*sqrt2*0.14*32/0.325)-1 = 18
motor.set_current(1.0)
check_true('set_current_1a', ((mock.last_write(0x10) >> 8) & 0x1F) == 18)
motor.set_current(5.0)
check_true('set_current_clamped', ((mock.last_write(0x10) >> 8) & 0x1F) == 31)

# run(): 16 microsteps, 200 steps/s -> 3200 * 2^24/12e6 = 4474.
motor.run(200)
check_true('run_forward', mock.last_write(0x22) == 4474)
motor.run(-200)
check_true('run_reverse', mock.last_write(0x22) == ((-4474) & 0xFFFFFF))
motor.stop()
check_true('stop', mock.last_write(0x22) == 0)

# disable/enable without ENN pin toggles TOFF.
motor.disable()
check_true('disable_toff0', mock.last_write(0x6C) & 0xF == 0)
motor.enable()
check_true('enable_toff3', mock.last_write(0x6C) & 0xF == 3)

mock.regs[0x6F] = (1 << 31) | 0x01 | 0x08 | 0x20
check_true('standstill', motor.is_standstill())
fault = motor.read_fault()
check_true('fault_flags', fault == (TMC2208Minimal.FAULT_OVERTEMP_WARNING
                                    | TMC2208Minimal.FAULT_SHORT_GND
                                    | TMC2208Minimal.FAULT_SHORT_SUPPLY))

# ENN pin: held high after init, low on enable.
pin = Pin()
m = TMC2208Minimal(UARTConnectionMock(), en_pin=pin)
check_true('en_pin_high_at_init', pin.levels == [True])
m.enable()
check_true('en_pin_low_on_enable', pin.levels[-1] is False)

# Without echo the driver must not read echo bytes.
mock2 = UARTConnectionMock(echo=False)
TMC2208Minimal(mock2, echo=False)
check_true('no_echo_init', mock2.last_write(0x00) == 0x1C1)

# Wrong chip identity.
bad = UARTConnectionMock()
bad.regs[0x06] = 0
try:
    TMC2208Minimal(bad)
    check_true('bad_version_raises', False)
except OSError:
    check_true('bad_version_raises', True)

# One CRC failure is retried.
flaky = UARTConnectionMock()
flaky.corrupt_next_reply = True
TMC2208Minimal(flaky)
check_true('crc_retry', flaky.last_write(0x00) == 0x1C1)

# ---- Full ----
mock = UARTConnectionMock()
step, dirp = Pin(), Pin()
full = TMC2208Full(mock, step_pin=step, dir_pin=dirp)
full.set_microsteps(256)
check_true('microsteps_256', (mock.last_write(0x6C) >> 24) & 0xF == 0)
full.set_microsteps(1)
check_true('microsteps_1', (mock.last_write(0x6C) >> 24) & 0xF == 8)
try:
    full.set_microsteps(3)
    check_true('microsteps_invalid', False)
except ValueError:
    check_true('microsteps_invalid', True)
full.set_microsteps(32)
full.run(100)
check_true('run_uses_microsteps',
           mock.last_write(0x22) == int(round(100 * 32 * 16777216.0 / 12e6)))
full.set_interpolation(False)
check_true('intpol_off', (mock.last_write(0x6C) >> 28) & 1 == 0)
full.set_chopper_mode(True)
check_true('spreadcycle', mock.last_write(0x00) & 0x4 == 0x4)
full.set_direction_inverted(True)
check_true('shaft', mock.last_write(0x00) & 0x8 == 0x8)
full.set_index_output(1)
check_true('index_otpw', mock.last_write(0x00) & 0x30 == 0x10)
full.set_double_edge(True)
check_true('dedge', (mock.last_write(0x6C) >> 29) & 1 == 1)
full.set_driver_protection(False, True)
check_true('diss2vs', (mock.last_write(0x6C) >> 31) & 1 == 1 and
           (mock.last_write(0x6C) >> 30) & 1 == 0)
full.set_vsense(True)
check_true('vsense', (mock.last_write(0x6C) >> 17) & 1 == 1)
full.set_hold_current(0.2)
check_true('hold_current', mock.last_write(0x10) & 0x1F == 6)
full.set_current_delay(2, 100)
check_true('current_delay', ((mock.last_write(0x10) >> 16) & 0xF) == 2
           and mock.last_write(0x11) == 100)
full.set_stealthchop_threshold(300)
check_true('tpwmthrs', mock.last_write(0x13) == 300)
full.configure_chopper(4, 1, 3, 2)
c = mock.last_write(0x6C)
check_true('configure_chopper', (c & 0xF) == 4 and ((c >> 15) & 3) == 1
           and ((c >> 4) & 7) == 3 and ((c >> 7) & 0xF) == 2)
full.configure_stealthchop(True, False, 40, 5, 1, 4, 12)
p = mock.last_write(0x70)
check_true('configure_stealthchop',
           (p & 0xFF) == 40 and ((p >> 8) & 0xFF) == 5 and ((p >> 16) & 3) == 1
           and (p >> 18) & 1 == 1 and (p >> 19) & 1 == 0
           and ((p >> 24) & 0xF) == 4 and (p >> 28) == 12)
full.set_freewheel(1)
check_true('freewheel', (mock.last_write(0x70) >> 20) & 3 == 1)
full.set_send_delay(4)
check_true('send_delay', mock.last_write(0x03) == 4 << 8)
try:
    full.set_send_delay(16)
    check_true('send_delay_range', False)
except ValueError:
    check_true('send_delay_range', True)
full.set_internal_rsense(True)
try:
    full.set_current(0.5)
    check_true('internal_rsense_current', False)
except ValueError:
    check_true('internal_rsense_current', True)
full.set_internal_rsense(False)

mock.regs[0x6A] = 513
mock.regs[0x6B] = (0x1FF << 16) | 0x100
mock.regs[0x12] = 1234
mock.regs[0x6F] = (1 << 31) | (1 << 30) | (17 << 16) | (0x5 << 8) | 0x02
mock.regs[0x71] = (0x1FE << 16) | 77
mock.regs[0x72] = (9 << 16) | 33
mock.regs[0x01] = 0x03
mock.regs[0x05] = 0x123456
check_true('microstep_position', full.microstep_position() == 513)
check_true('coil_currents', full.read_coil_currents() == (-256, -1))
check_true('tstep', full.read_tstep() == 1234)
check_true('current_scale', full.read_current_scale() == 17)
check_true('temp_flags', full.read_temperature_flags() == 5)
st = full.read_status()
check_true('status', st['stst'] and st['stealth'] and st['ot']
           and st['cs_actual'] == 17 and st['t120'] and st['t150']
           and not st['t143'])
check_true('global_status', full.read_global_status() ==
           {'reset': True, 'drv_err': True, 'uv_cp': False})
full.read_global_status(clear=True)
check_true('global_status_clear', mock.last_write(0x01) == 0x07)
check_true('pwm_auto', full.read_pwm_auto() == {
    'pwm_scale_sum': 77, 'pwm_scale_auto': -2, 'pwm_ofs_auto': 33,
    'pwm_grad_auto': 9})
ioin = full.read_ioin()
check_true('ioin', ioin['sel_a'] and ioin['version'] == 0x21)
check_true('otp', full.read_otp() == 0x123456)
check_true('ifcnt', full.read_ifcnt() == mock.regs[0x02])
check_true('read_register', full.read_register(0x6A) == 513)
try:
    full.read_register(0x10)
    check_true('read_register_writeonly', False)
except ValueError:
    check_true('read_register_writeonly', True)
full.write_register(0x13, 5)
check_true('write_register', mock.last_write(0x13) == 5)
try:
    full.write_register(0x04, 1)
    check_true('write_register_otp_prog', False)
except ValueError:
    check_true('write_register_otp_prog', True)

# GPIO stepping
full.step(3, 0)
check_true('step_pulses', step.levels.count(True) == 3 and dirp.levels[-1] is True)
full.step(-2, 0)
check_true('step_reverse', dirp.levels[-1] is False)
try:
    TMC2208Full(UARTConnectionMock()).step(1, 0)
    check_true('step_requires_pin', False)
except RuntimeError:
    check_true('step_requires_pin', True)

# TMC2224 identity (SEL_A = 0)
m24 = UARTConnectionMock()
m24.regs[0x06] = 0x21000000
TMC2208Full(m24, tmc2224=True)
check_true('tmc2224_sel_a0', True)

print('===DONE=== passed=%d failed=%d' % (passed, failed))
