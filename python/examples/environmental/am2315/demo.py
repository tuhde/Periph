import math
import time
from periph.connection.i2c_auto import I2CConnection
from periph.chips.environmental.am2315 import AM2315Full

connection = I2CConnection(0x5C)                                         # Create I²C connection, (addr=0x5C) → Connection
sensor = AM2315Full(connection)                                          # Create AM2315 driver, (connection) → None

info = sensor.read_device_info()                                         # Read device information, () → dict
print('model %d, version %d, device id 0x%08X' % (info['model'], info['version'], info['device_id']))


def dew_point(t, rh):
    # Magnus formula
    g = math.log(max(rh, 0.1) / 100.0) + 17.62 * t / (243.12 + t)
    return 243.12 * g / (17.62 - g)


# --- Weather-station logger: a sample every 5 s, min/max tracking ---
t_min = h_min = 1000.0
t_max = h_max = -1000.0
for i in range(1, 31):
    # The sensor measures after a communication and returns that result on the
    # next one, so every sample is the previous measurement; the driver enforces
    # the >= 2 s spacing and the CRC-16 check.
    if i % 10 == 0:
        r = sensor.read_fresh()                                          # Real-time value, () → dict
    else:
        r = sensor.read()                                                # Read temperature and humidity, () → dict
    t, rh = r['temperature_c'], r['humidity_pct']
    t_min, t_max = min(t_min, t), max(t_max, t)
    h_min, h_max = min(h_min, rh), max(h_max, rh)
    print('%s  %.1f C  %.1f %%RH  dew point %.1f C  (T %.1f..%.1f, RH %.1f..%.1f)' % (
        time.strftime('%H:%M:%S'), t, rh, dew_point(t, rh), t_min, t_max, h_min, h_max))
    time.sleep(5)

# --- User registers as a persistent counter ---
sensor.write_user_registers(30, 0xBEEF)                                  # Write user registers, (user1, user2) → None
print('user registers:', sensor.read_user_registers())                   # Read user registers, () → (int, int)
