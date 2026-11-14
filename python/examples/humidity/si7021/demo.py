from periph.connection.i2c_auto import I2CConnection
from periph.chips.humidity.si7021 import Si7021Full
import time

connection = I2CConnection(0x40)                                         # Create I²C connection, (addr=0x40) → Connection
sensor = Si7021Full(connection)                                          # Create Si7021 driver, (connection) → None

sensor.software_reset()                                                  # Software reset, () → None
print('device type 0x%02X' % sensor.device_type())                       # Device type, () → int
print('serial number 0x%016X' % sensor.serial_number())                  # Electronic serial number, () → int
print('firmware 0x%02X' % sensor.firmware_revision())                    # Firmware revision, () → int
print('VDD low:', sensor.vdd_low())                                      # Supply low flag, () → bool

# --- Condensation-aware hygrometer: 15 readings, 2 s apart ---
for _ in range(15):
    t, rh = sensor.read()                                                # Read temperature and humidity, () → (float, float)
    print('%.2f C %.1f %%RH dew point %.2f C' % (t, rh, sensor.dew_point()))  # Compute dew point, () → float °C
    time.sleep(2)

# --- Value accessors and raw codes ---
print('RH', sensor.read_humidity(), 'T', sensor.read_temperature())      # () → float ; () → float
print('T of last RH', sensor.read_temperature_previous())                # () → float
print('raw', sensor.read_raw_humidity(), sensor.read_raw_temperature())  # () → int ; () → int
sensor.set_clock_stretching(False)                                       # Use hold-master commands, (on) → None
reg = sensor.read_user_register()                                        # Read USER_REG_1, () → int
sensor.write_user_register(reg)                                          # Write USER_REG_1, (value) → None

# --- Resolution comparison ---
for rh_bits, t_bits in ((12, 14), (10, 13), (11, 11), (8, 12)):
    sensor.set_resolution(rh_bits, t_bits)                               # Set resolution, (rh_bits, temperature_bits) → None
    start = time.time()
    t, rh = sensor.read()
    print('%d/%d bit: %.2f C %.1f %%RH, %.1f ms (typ. %.1f ms), resolution %s' % (
        rh_bits, t_bits, t, rh, (time.time() - start) * 1000,
        sensor.conversion_time() * 1000, sensor.get_resolution()))       # () → float s ; () → (int, int)
sensor.set_resolution(12, 14)

# --- Heater test: 10 s ---
rh_before = sensor.read_humidity()
t_before = sensor.read_temperature()
sensor.set_heater(True)                                                  # Switch the heater, (on) → None
time.sleep(10)
print('heater on:', sensor.is_heater_on())                               # Heater state, () → bool
print('RH drop %.1f %%RH, temperature rise %.2f K' % (
    rh_before - sensor.read_humidity(), sensor.read_temperature() - t_before))
sensor.set_heater(False)
