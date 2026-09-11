from periph.connection.i2c_auto import I2CConnection
from periph.chips.pressure.lps28dfw import LPS28DFWFull

connection = I2CConnection(0x5C)

# --- High-resolution depth/altitude logger: Mode 1, 64-sample average, 25 Hz ---
# 64× averaging achieves ~1.1 Pa rms noise; Mode 1 keeps full 0.244 Pa resolution.
lps = LPS28DFWFull(connection)                                # Create LPS28DFW driver, (connection)
lps.configure(odr=LPS28DFWFull.ODR_25_HZ, avg=LPS28DFWFull.AVG_64,
              fs_mode=LPS28DFWFull.FS_MODE_1, lpf_en=True, lpf_cfg=0)  # Configure chip, (odr=25 Hz, avg=64, fs_mode=1, lpf_en=True, lpf_cfg=ODR/4) → None

# --- Sample every 500 ms for 30 s; report pressure, temperature, altitude ---
# Sea-level reference uses the ISA standard (1013.25 hPa).
import machine
samples = 0
for n in range(60):
    vals = lps.read()                                         # Read both values, () → dict
    p = vals['pressure']
    t = vals['temperature']
    alt = 44330.0 * (1.0 - (p / 1013.25) ** (1.0 / 5.255))
    elapsed = (n + 1) * 0.5
    print('{:5.1f}s  {:7.2f} hPa  {:5.2f} °C  {:6.1f} m'.format(elapsed, p, t, alt))
    samples += 1
    machine.sleep(500)

print('Total samples: {}'.format(samples))
print('===DONE: 0 passed, 0 failed===')