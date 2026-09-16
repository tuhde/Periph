import time
import _testconfig_cp as cfg
from periph.connection.i2c_circuitpython import I2CConnection
from periph.chips.magnetometer.hmc5883l import HMC5883LFull

import board
import busio

passed = 0
failed = 0


def check_eq(label, got, expected):
    global passed, failed
    if got == expected:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL {}: got {}, expected {}'.format(label, got, expected))
        failed += 1


def check_true(label, condition):
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1


i2c = busio.I2C(board.SCL, board.SDA, frequency=cfg.FREQ)
connection = I2CConnection(i2c, cfg.ADDR)

hmc5883l = HMC5883LFull.__new__(HMC5883LFull)
hmc5883l._connection = connection

# --- Identification ---
id_a, id_b, id_c = hmc5883l.identify()
check_eq('identify', (id_a, id_b, id_c), (0x48, 0x34, 0x33))

# --- Status ---
sb = hmc5883l.status()
check_true('status_byte valid', sb >= 0 and sb <= 255)

# --- Data ready ---
check_true('data_ready returns bool', isinstance(hmc5883l.data_ready(), bool))

# --- Magnetic field reading ---
x, y, z = hmc5883l.magnetic_field()
check_true('x is float or None', isinstance(x, (float, type(None))))
check_true('y is float or None', isinstance(y, (float, type(None))))
check_true('z is float or None', isinstance(z, (float, type(None))))

# --- Configuration ---
hmc5883l.configure(odr=15, averaging=8, gain=1)
check_true('configure accepted', True)

hmc5883l.set_gain(2)
check_true('set_gain accepted', True)

hmc5883l.set_mode('continuous')
check_true('set_mode continuous accepted', True)

# --- Single-shot measurement ---
x, y, z = hmc5883l.single_measurement()
check_true('single_measurement x is float or None', isinstance(x, (float, type(None))))
check_true('single_measurement y is float or None', isinstance(y, (float, type(None))))
check_true('single_measurement z is float or None', isinstance(z, (float, type(None))))

hmc5883l.set_mode('idle')
check_true('set_mode idle accepted', True)

# --- Self-test ---
x, y, z = hmc5883l.self_test(positive=True)
check_true('self_test x is float or None', isinstance(x, (float, type(None))))
check_true('self_test y is float or None', isinstance(y, (float, type(None))))
check_true('self_test z is float or None', isinstance(z, (float, type(None))))

print('===DONE: {} passed, {} failed==='.format(passed, failed))