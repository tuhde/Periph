from periph.connection.i2c_auto import I2CConnection
from periph.chips.pressure.lps22df import LPS22DFFull

connection = I2CConnection(0x5C)

# --- Indoor altimeter preset: 25 Hz, 4-sample average, low-pass filter ---
# Low-pass at ODR/9 smooths short-term pressure noise (door slams, fans);
# 4-sample averaging trims noise without adding visible lag. The sensor's
# built-in filters eliminate the need for downstream software smoothing.
lps = LPS22DFFull(connection)                              # Create LPS22DF driver, (connection, bus_type='i2c')
lps.configure(odr=4, avg=0, en_lpfp=True, lfpf_cfg=1, bdu=True)  # Configure chip, (odr=25 Hz, avg=4, en_lpfp=True, lfpf_cfg=ODR/9, bdu=True) → None

# --- Baseline capture: 2-second stabilization then zero the altimeter ---
# LPS22DF reports absolute pressure; relative altitude is what matters indoors.
# Taking a baseline after power-up (while stationary) removes weather drift.
machine.sleep(2000)
baseline_p = lps.pressure()                               # Read pressure, () → float Pa
print('Baseline: {} Pa'.format(int(baseline_p)))

pressures, temps, deltas = [], [], []
for n in range(30):
    p = lps.pressure()                                     # Read pressure, () → float Pa
    t = lps.temperature()                                  # Read temperature, () → float °C
    d = lps.altitude(baseline_p)                           # Compute altitude, (sea_level_pa=baseline_p) → float m
                                                          # delta altitude in metres from the baseline
    pressures.append(p)
    temps.append(t)
    deltas.append(d)
    print('{}s: {} Pa, T={:.2f} C, Δalt={:.3f} m'.format(n, int(p), t, d))
    machine.sleep(1000)

mean_p = sum(pressures) / len(pressures)
mean_t = sum(temps) / len(temps)
print('P min={} max={} mean={:.1f} Pa'.format(int(min(pressures)), int(max(pressures)), mean_p))
print('T min={:.2f} max={:.2f} mean={:.2f} C'.format(min(temps), max(temps), mean_t))
print('Δalt min={:.3f} max={:.3f} mean={:.3f} m'.format(min(deltas), max(deltas), sum(deltas) / len(deltas)))

# --- FIFO note: uncomment to log 128 samples at the end of the run ---
# The chip holds 128 pressure samples in its FIFO; useful for batch logging
# without interrupting the foreground loop. Set the mode with set_fifo_mode()
# and watermark with set_fifo_watermark() before draining via read_fifo().
# lps.set_fifo_mode(LPS22DFFull.FIFO_FIFO)
# lps.set_fifo_watermark(128)
# samples = lps.read_fifo()
print('===DONE: 0 passed, 0 failed===')