from periph.connection.i2c_auto import I2CConnection
from periph.chips.pressure.mpl3115a2 import MPL3115A2Full
import time

connection = I2CConnection(0x60)                                         # Create I²C connection, (addr=0x60) → Connection
sensor = MPL3115A2Full(connection)                                       # Create MPL3115A2 driver, (connection) → None
sensor.init()                                                            # Reset and apply defaults, () → None

# --- Weather station: barometer readings against the local sea-level pressure ---
# BAR_IN holds the local QNH so altitude readings are meaningful.
sensor.set_sea_level_pressure(101325)                                    # Set sea-level pressure, (pa float) → None
for _ in range(3):
    m = sensor.read()                                                    # Read pressure and temperature, () → dict {pressure_pa Pa, temperature_c °C}
    print('%.2f Pa  %.2f C' % (m['pressure_pa'], m['temperature_c']))
    time.sleep(5)

# --- Altimeter mode: a few altitude readings ---
for _ in range(3):
    print('altitude %.2f m' % sensor.read_altitude())                    # Read altitude, () → float m
sensor.set_mode(False)                                                   # Back to barometer, (altimeter bool) → None

# --- FIFO: collect samples continuously, then summarise ---
sensor.set_oversample(16)                                                # Faster conversions, (ratio) → None
sensor.fifo_setup(MPL3115A2Full.FIFO_CIRCULAR, 16)                       # Circular FIFO with watermark 16, (mode, watermark) → None
sensor.start_continuous(0)                                               # One sample per second, (step_exp 0-15) → None
while not sensor.fifo_status()[1]:                                       # Wait for the watermark flag, () → (overflow, watermark, count)
    time.sleep(1)
samples = sensor.fifo_read()                                             # Drain the FIFO, () → list of (value Pa, temperature_c °C)
values = [s[0] for s in samples]
print('n=%d min=%.2f max=%.2f mean=%.2f Pa' % (len(values), min(values), max(values), sum(values) / len(values)))

# --- Temperature threshold on INT1 ---
sensor.set_target(0, 0, int(samples[-1][1]) + 1, 0)                      # Temperature target, (value, window, temperature_c, temperature_window) → None
sensor.enable_interrupt(MPL3115A2Full.SOURCE_TTH, 1)                     # Route temperature threshold to INT1, (source, pin) → None
print('INT_SOURCE 0x%02X' % sensor.poll_interrupt())                     # Read INT_SOURCE, () → int

# --- Wrap up ---
sensor.stop_continuous()                                                 # Back to STANDBY, () → None
sensor.fifo_setup(MPL3115A2Full.FIFO_OFF)                                # FIFO off, (mode) → None
print(sensor.read_min_max())                                             # Captured extremes, () → dict {p_min, p_max, t_min, t_max}
