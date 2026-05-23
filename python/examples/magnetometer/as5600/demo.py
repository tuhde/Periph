from machine import I2C
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.magnetometer.as5600 import AS5600Full
import time

i2c = I2C(2, freq=400000)
transport = I2CTransport(i2c, 0x36)
as5600 = AS5600Full(transport)

# --- Motor feedback monitor: read angle 10 times per second ---
# AGC monitoring detects magnet distance drift; status changes alert to magnet removal.
# In 5 V mode, target AGC ≈ 128; in 3.3 V mode, target AGC ≈ 64.

prev_status = as5600.status_byte()

for n in range(10):
    a = as5600.angle()                                     # Read absolute angle, () → float degrees
    r = as5600.raw_angle()                                 # Read raw unscaled angle, () → int 0-4095
    g = as5600.agc()                                       # Read AGC value, () → int

    # --- Check for status changes (magnet inserted/removed) ---
    status = as5600.status_byte()
    if status != prev_status:
        if not as5600.is_magnet_detected():
            print('[MAGNET REMOVED] MD=0')
        else:
            mh = as5600.is_magnet_too_strong()
            ml = as5600.is_magnet_too_weak()
            print('[MAGNET DETECTED] MD=1  MH=%d  ML=%d' % (mh, ml))
        prev_status = status

    # --- AGC health check ---
    if as5600.is_magnet_detected():
        tag = '[OK]'
        if g < 64 or g > 192:
            tag = '[AGC low — magnet weak or too far]'
        print('angle=%.2f°  raw=%d  agc=%d  %s' % (a, r, g, tag))

    time.sleep_ms(100)
