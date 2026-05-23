import time
import os
from periph.transport.i2c_linux import I2CTransport
from periph.chips.magnetometer.as5600 import AS5600Full

I2C_BUS  = int(os.environ.get('I2C_BUS', '1'))
I2C_ADDR = int(os.environ.get('I2C_ADDR', '0x36'), 16)

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


transport = I2CTransport(I2C_BUS, I2C_ADDR)

# Poll magnet status for up to 60 s at 5 Hz before running tests.
print('--- magnet status (60 s max) ---')
for _ in range(300):
    s   = transport.write_read(bytes([0x0B]), 1)[0]
    agc = transport.write_read(bytes([0x1A]), 1)[0]
    md = bool(s & 0x08); ml = bool(s & 0x10); mh = bool(s & 0x20)
    print('MD={} ML={} MH={} AGC={}'.format(int(md), int(ml), int(mh), agc))
    if md:
        break
    time.sleep(0.2)
print('--- end magnet status ---')

# Construct driver without the MD guard so tests run regardless.
as5600 = AS5600Full.__new__(AS5600Full)
as5600._transport = transport

# --- Magnet detection ---
check_true('magnet_detected', as5600.is_magnet_detected())

# --- Angle readings ---
a = as5600.angle()
check_true('angle in range 0-360', a >= 0.0 and a < 360.0)

r = as5600.angle_raw()
check_true('angle_raw in range 0-4095', r >= 0 and r <= 4095)

ra = as5600.raw_angle()
check_true('raw_angle in range 0-4095', ra >= 0 and ra <= 4095)

rad = as5600.raw_angle_degrees()
check_true('raw_angle_degrees in range 0-360', rad >= 0.0 and rad < 360.0)

# --- Diagnostics ---
check_true('agc non-negative', as5600.agc() >= 0)
check_true('magnitude non-negative', as5600.magnitude() >= 0)

# --- Status ---
sb = as5600.status_byte()
check_true('status_byte valid', sb >= 0 and sb <= 255)

# --- Position configuration (volatile) ---
as5600.set_zero_position(100)
check_eq('zero_position after set', as5600.zero_position(), 100)

as5600.set_max_position(2000)
check_eq('max_position after set', as5600.max_position(), 2000)

as5600.set_max_angle(2048)
check_eq('max_angle after set', as5600.max_angle(), 2048)

# --- Configure ---
as5600.configure(pm=0, hyst=0, outs=0, pwmf=0, sf=0, fth=0, wd=False)
check_true('configure accepted', as5600.is_magnet_detected())

# --- Burn count ---
bc = as5600.burn_count()
check_true('burn_count in range 0-3', bc >= 0 and bc <= 3)

transport.close()

print('===DONE: {} passed, {} failed==='.format(passed, failed))
