from periph.connection.i2c_auto import I2CConnection
from periph.chips.pressure.bmp085 import BMP085Full

connection = I2CConnection(0x77)

bmp = BMP085Full(connection, oss=0)                     # Create BMP085 driver, (connection, oss=0 ULP)

# Use first reading as sea-level reference so altitude starts at 0.
t0 = bmp.temperature()                                 # Read temperature, () → float C
p0 = bmp.pressure()                                   # Read pressure, () → float Pa
alt_ref = bmp.altitude()                             # Compute altitude, (sea_level_pa=101325.0) → float m
print('Reference: {} C, {} Pa, alt={:.1f} m'.format(t0, p0, alt_ref))
prev_alt = 0.0

# --- Pocket altimeter / weather logger ---
# Reads temperature, pressure, and altitude once per second for 60 seconds.
# Demonstrates ~8 m altitude resolution per 1 hPa pressure change.
temps, pressures, alts = [], [], []
for n in range(60):
    t = bmp.temperature()                             # Read temperature, () → float C
    p = bmp.pressure()                               # Read pressure, () → float Pa
    a = bmp.altitude()                              # Compute altitude, (sea_level_pa=101325.0) → float m
    temps.append(t)
    pressures.append(p)
    alts.append(a)

    if n > 0:
        da = (a - prev_alt) * 100                    # altitude delta in cm
        print('{}s: {} C, {} Pa, alt={:.1f} m (delta={:+.0f} cm)'.format(n, t, p, a, da))
    else:
        print('{}s: {} C, {} Pa, alt={:.1f} m'.format(n, t, p, a))
    prev_alt = a
    machine.sleep(1000)

print('Summary: T={:.1f}/{:.1f}/{:.1f} C, P={:.1f}/{:.1f}/{:.1f} Pa, alt={:.1f}/{:.1f}/{:.1f} m'.format(
    min(temps), sum(temps)/len(temps), max(temps),
    min(pressures), sum(pressures)/len(pressures), max(pressures),
    min(alts), sum(alts)/len(alts), max(alts)))
print('===DONE: 0 passed, 0 failed===')