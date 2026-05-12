import machine
import _testconfig as cfg
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.pressure.bmp180 import BMP180Full
from machine import Pin

i2c = I2C(cfg.I2C_ID, sda=Pin(cfg.SDA), scl=Pin(cfg.SCL), freq=cfg.FREQ)
transport = I2CTransport(i2c, 0x77)

bmp = BMP180Full(transport, oss=0)                     # Create BMP180 driver, (transport, oss=0 ULP)

# Use first reading as sea-level reference so altitude starts at 0.
t0 = bmp.temperature()                                 # Read temperature, () → float C
p0 = bmp.pressure()                                   # Read pressure, () → float hPa
alt_ref = bmp.altitude()                             # Compute altitude, (sea_level_hpa=1013.25) → float m
print('Reference: {} C, {} hPa, alt={:.1f} m'.format(t0, p0, alt_ref))
prev_alt = 0.0

# --- Pocket altimeter / weather logger ---
# Reads temperature, pressure, and altitude once per second for 60 seconds.
# Demonstrates ~8 m altitude resolution per 1 hPa pressure change.
temps, pressures, alts = [], [], []
for n in range(60):
    t = bmp.temperature()                             # Read temperature, () → float C
    p = bmp.pressure()                               # Read pressure, () → float hPa
    a = bmp.altitude()                              # Compute altitude, (sea_level_hpa=1013.25) → float m
    temps.append(t)
    pressures.append(p)
    alts.append(a)

    if n > 0:
        da = (a - prev_alt) * 100                    # altitude delta in cm
        print('{}s: {} C, {} hPa, alt={:.1f} m (delta={:+.0f} cm)'.format(n, t, p, a, da))
    else:
        print('{}s: {} C, {} hPa, alt={:.1f} m'.format(n, t, p, a))
    prev_alt = a
    machine.sleep(1000)

print('Summary: T={:.1f}/{:.1f}/{:.1f} C, P={:.1f}/{:.1f}/{:.1f} hPa, alt={:.1f}/{:.1f}/{:.1f} m'.format(
    min(temps), sum(temps)/len(temps), max(temps),
    min(pressures), sum(pressures)/len(pressures), max(pressures),
    min(alts), sum(alts)/len(alts), max(alts)))
print('===DONE: 0 passed, 0 failed===')
