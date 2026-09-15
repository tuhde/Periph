from periph.connection.i2c_auto import I2CConnection
from periph.chips.gyroscope.l3g4200d import L3G4200DFull

connection = I2CConnection(0x68)

# --- Rotation detector: 200 Hz, ±500 dps, FIFO stream with watermark 10 ---
# 200 Hz ODR gives 5 ms per sample — fast enough to catch hand motion but
# not so noisy that the FIFO drains before the watermark is reached.
gyro = L3G4200DFull(connection)                            # Create L3G4200D driver, (connection, bus_type='i2c')
gyro.configure(odr=200, bandwidth=0, full_scale=500)       # Configure chip, (odr=200Hz, bandwidth=0, full_scale=500dps) → None
gyro.enable_highpass(mode=0, cutoff=4)                     # Enable high-pass, (mode=0, cutoff=4) → None
                                                            # cutoff index 4 at 200 Hz ODR ≈ 1 Hz; strips DC drift
gyro.enable_fifo(mode=L3G4200DFull.FIFO_STREAM, watermark=10)  # Enable FIFO, (mode=2=stream, watermark=10) → None

threshold_rad_s = 90.0 * (3.141592653589793 / 180.0)
alerts = 0

# --- Loop: wait for FIFO watermark, drain, compute mean, alert on threshold ---
# Stream mode keeps the oldest samples; the FIFO never blocks but the host
# only acts once per watermark crossing to amortise I²C overhead.
for _ in range(50):
    while gyro.fifo_samples() < 10:                       # Read FIFO count, () → int
        time.sleep_ms(5)
    burst = gyro.read_fifo()                              # Drain FIFO, () → list[(x, y, z) rad/s]
    if not burst:
        continue
    mx = sum(s[0] for s in burst) / len(burst)
    my = sum(s[1] for s in burst) / len(burst)
    mz = sum(s[2] for s in burst) / len(burst)
    if abs(mx) > threshold_rad_s or abs(my) > threshold_rad_s or abs(mz) > threshold_rad_s:
        alerts += 1
        print('ALERT  X={:.2f} Y={:.2f} Z={:.2f} rad/s'.format(mx, my, mz))
    else:
        print('       X={:.2f} Y={:.2f} Z={:.2f} rad/s'.format(mx, my, mz))
    time.sleep_ms(20)

print('Total alerts: {} / 50'.format(alerts))
print('===DONE: 0 passed, 0 failed===')
