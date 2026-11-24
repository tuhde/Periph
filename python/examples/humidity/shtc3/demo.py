from periph.connection.i2c_auto import I2CConnection
from periph.chips.humidity.shtc3 import SHTC3Full
import time

connection = I2CConnection(0x70)                                         # Create I²C connection, (addr=0x70) → Connection
sensor = SHTC3Full(connection)                                           # Create SHTC3 driver, (connection) → None
sensor.init()                                                            # Soft reset and ID check, () → None

# --- Identify the chip ---
# The ID word carries the SHTC3 product code in bits 11 and 5:0.
print('id 0x%04X' % sensor.read_id())                                    # Read the ID word, () → int

# --- Battery-friendly climate logger: 6 readings, 10 s apart ---
# The sensor sleeps (0.3 µA) between the readings; every read wakes it.
for _ in range(6):
    m = sensor.read()                                                    # Read temperature and humidity, () → dict {temperature_c °C, humidity_pct %RH}
    print('%.2f C  %.1f %%RH' % (m['temperature_c'], m['humidity_pct']))
    time.sleep(10)

# --- Compare normal and low-power mode ---
# Low power measures in 0.7 ms instead of 10.8 ms at slightly higher noise.
for low in (False, True):
    sensor.set_low_power(low)                                            # Select low-power mode, (on) → None
    start = time.time()
    m = sensor.read()                                                    # Read temperature and humidity, () → dict
    print('low power' if low else 'normal   ', '%.1f ms' % ((time.time() - start) * 1000.0), m)
sensor.set_low_power(False)

# --- Dew point and raw words ---
print('dew point %.2f C' % sensor.dew_point())                           # Compute dew point, () → float °C
print('raw', sensor.read_raw())                                          # Read raw codes, () → (int, int)

# --- Finish asleep ---
sensor.sleep()                                                           # Sleep command, () → None
