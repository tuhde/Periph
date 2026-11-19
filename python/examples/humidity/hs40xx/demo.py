from periph.connection.i2c_auto import I2CConnection
from periph.chips.humidity.hs40xx import HS40xxFull
import time

connection = I2CConnection(0x54)                                         # Create I²C connection, (addr=0x54) → Connection
sensor = HS40xxFull(connection)                                          # Create HS40xx driver, (connection) → None

# --- Humidity watchdog ---
# Single readings for 10 s, then periodic mode with an RH-high alert (breathe on the sensor).
print('ID 0x%08X' % sensor.read_id())                                    # Read the 32-bit sensor ID, () → int

for n in range(5):
    d = sensor.read()                                                    # Read temperature and humidity, () → dict {temperature_c, humidity_pct}
    print('%.2f C, %.1f %%RH' % (d['temperature_c'], d['humidity_pct']))
    time.sleep(2)

sensor.set_alert_thresholds(rh_high=60.0, rh_high_reset=55.0)            # Set alert thresholds, (rh_high, rh_high_reset, ...) → None
sensor.enable_interrupt(sensor.SOURCE_RH_HIGH)                           # Enable alert sources, (source) → None
sensor.start_periodic(1)                                                 # Start periodic mode, (period_s) → None

for n in range(15):
    time.sleep(1)
    p = sensor.read_periodic()                                           # Fetch the latest periodic result, () → dict {temperature_c, humidity_pct, status}
    alert = 'ALERT' if p['status'] & sensor.SOURCE_RH_HIGH else 'ok'
    print('%.2f C, %.1f %%RH  %s' % (p['temperature_c'], p['humidity_pct'], alert))

sensor.stop_periodic()                                                   # Stop periodic mode, () → None
sensor.soft_reset()                                                      # Soft reset, () → None
