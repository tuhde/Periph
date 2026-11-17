from periph.connection.i2c_auto import I2CConnection
from periph.chips.humidity.hdc1008 import HDC1008Full
import math
import time

connection = I2CConnection(0x40)                                         # Create I²C connection, (addr=0x40) → Connection
sensor = HDC1008Full(connection)                                         # Create HDC1008 driver, (connection) → None
sensor.init()                                                            # Write default configuration, () → None

print('serial id 0x%010X' % sensor.serial_id())                          # Read 40-bit serial ID, () → int

# --- Condensation-aware hygrometer: 15 readings, 2 s apart ---
for _ in range(15):
    t, rh = sensor.read()                                                # Read temperature and humidity, () → (float, float)
    g = math.log(max(rh, 0.01) / 100.0) + 17.62 * t / (243.12 + t)
    dew = 243.12 * g / (17.62 - g)
    print('%.2f C %.1f %%RH dew point %.2f C' % (t, rh, dew))
    time.sleep(2)

# --- Heater test: 10 s ---
rh0 = sensor.read_humidity()                                             # () → float %RH
t0 = sensor.read_temperature()                                           # () → float °C
sensor.set_heater(True)                                                  # Switch the heater, (on) → None
end = time.time() + 10
while time.time() < end:
    sensor.read()
    time.sleep(0.2)
rh1, t1 = sensor.read_humidity(), sensor.read_temperature()
print('RH drop %.1f %%RH, temperature rise %.2f K' % (rh0 - rh1, t1 - t0))
sensor.set_heater(False)

# --- Resolution comparison ---
sensor.set_resolution(11, 8)                                             # Set resolution, (temperature_bits, humidity_bits) → None
start = time.time()
t, rh = sensor.read()
print('11/8 bit: %.2f C %.1f %%RH in %.1f ms' % (t, rh, (time.time() - start) * 1000))
sensor.set_resolution(14, 14)
print('battery low:', sensor.battery_low())                              # VDD below 2.8 V, () → bool
