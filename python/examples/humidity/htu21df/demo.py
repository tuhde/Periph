from periph.connection.i2c_auto import I2CConnection
from periph.chips.humidity.htu21df import HTU21DFFull
import time

connection = I2CConnection(0x40)                                         # Create I²C connection, (addr=0x40) → Connection
sensor = HTU21DFFull(connection)                                         # Create HTU21D-F driver, (connection) → None

sensor.init()                                                            # Soft reset, () → None
sensor.soft_reset()                                                      # Soft reset, () → None
print('user register 0x%02X' % sensor.read_user_register())              # Read the user register, () → int
print('resolution', sensor.get_resolution())                             # Get resolution, () → int

# --- Five readings at 12/14 bit ---
for _ in range(5):
    t, rh = sensor.read()                                                # Read temperature and humidity, () → (float, float)
    print('%.2f C %.1f %%RH (compensated %.1f) dew point %.2f C' % (
        t, rh, sensor.read_humidity_compensated(), sensor.dew_point()))  # () → float %RH ; () → float °C
    time.sleep(2)
print('raw', sensor.read_raw())                                          # Read raw codes, () → (int, int)

# --- 8/12 bit: shorter conversion, coarser resolution ---
sensor.set_resolution(1)                                                 # Set resolution, (res) → None
start = time.time()
t, rh = sensor.read_temperature(), sensor.read_humidity()                # () → float °C ; () → float %RH
print('8/12 bit: %.2f C %.1f %%RH in %.1f ms' % (t, rh, (time.time() - start) * 1000))
sensor.set_resolution(0)

# --- Heater self-check: 10 s ---
rh_before = sensor.read_humidity()
t_before = sensor.read_temperature()
sensor.set_heater(True)                                                  # Switch the heater, (on) → None
time.sleep(10)
print('heater on:', sensor.heater())                                     # Heater state, () → bool
print('RH drop %.1f %%RH, temperature rise %.2f K' % (
    rh_before - sensor.read_humidity(), sensor.read_temperature() - t_before))
sensor.set_heater(False)
print('battery low:', sensor.battery_low())                              # End-of-battery flag, () → bool
