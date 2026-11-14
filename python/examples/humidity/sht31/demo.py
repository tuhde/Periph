from periph.connection.i2c_auto import I2CConnection
from periph.chips.humidity.sht31 import SHT31Full
import time

connection = I2CConnection(0x44)                                         # Create I²C connection, (addr=0x44) → Connection
sensor = SHT31Full(connection)                                           # Create SHT31 driver, (connection) → None

sensor.soft_reset()                                                      # Soft reset, () → None
print('status 0x%04X' % sensor.read_status())                            # Read status register, () → int

# --- Single shots at all three repeatability levels ---
for level in ('low', 'medium', 'high'):
    sensor.set_repeatability(level)                                      # Select repeatability, (level) → None
    start = time.time()
    t, rh = sensor.read()                                                # Read temperature and humidity, () → (float, float)
    print('%-6s %.2f C %.1f %%RH (%.1f ms)' % (level, t, rh, (time.time() - start) * 1000))
sensor.set_clock_stretching(False)                                       # Use clock-stretching commands, (on) → None
print('raw', sensor.read_raw())                                          # Read raw words, () → (int, int)
print('T', sensor.read_temperature(), 'RH', sensor.read_humidity())      # Read temperature only, () → float ; Read humidity only, () → float

# --- Periodic mode at 2 mps: ten samples ---
sensor.start_periodic(2, 'high')                                         # Start periodic mode, (rate, repeatability='high', art=False) → None
got = 0
while got < 10:
    res = sensor.fetch()                                                 # Fetch the latest periodic result, () → (float, float) or None
    if res is None:
        time.sleep(0.05)
        continue
    got += 1
    print('periodic %d: %.2f C %.1f %%RH' % (got, res[0], res[1]))
sensor.stop_periodic()                                                   # Stop periodic mode, () → None

# --- Heater test: 10 s ---
before = sensor.read_temperature()
sensor.set_heater(True)                                                  # Switch the heater, (on) → None
time.sleep(10)
print('heater status bit:', bool(sensor.read_status() & 0x2000))         # Read status register, () → int
after = sensor.read_temperature()
print('temperature rise: %.2f K' % (after - before))
sensor.set_heater(False)
sensor.clear_status()                                                    # Clear status flags, () → None
print('status 0x%04X' % sensor.read_status())
print('dew point %.2f C' % sensor.dew_point())                           # Compute dew point, () → float °C
