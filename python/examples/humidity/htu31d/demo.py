from periph.connection.i2c_auto import I2CConnection
from periph.chips.humidity.htu31d import HTU31DFull
import time

connection = I2CConnection(0x40)                                         # Create I²C connection, (addr=0x40) → Connection
sensor = HTU31DFull(connection)                                          # Create HTU31D driver, (connection) → None
sensor.init()                                                            # Soft reset, () → None

# --- Identify the chip and check its health ---
# The serial number is unique per device; the diagnostic flags report NVM
# and out-of-range errors.
print('serial 0x%06X' % sensor.serial_number())                          # Read the serial number, () → int
print('diagnostic', sensor.diagnostic_flags())                           # Named diagnostic flags, () → dict

# --- Condensation-aware hygrometer: 15 readings, 2 s apart ---
# The dew point shows how close the air is to condensing on a surface.
for _ in range(15):
    m = sensor.read()                                                    # Read temperature and humidity, () → dict {temperature_c °C, humidity_pct %RH}
    print('%.2f C  %.1f %%RH  dew %.2f C' % (m['temperature_c'], m['humidity_pct'], sensor.dew_point()))  # Compute dew point, () → float °C
    print('h %.1f t %.2f raw %s' % (sensor.read_humidity(), sensor.read_temperature(), sensor.read_raw()))  # Read single values and raw codes, () → float / (int, int)
    time.sleep(2)

# --- Compare the four oversampling settings ---
# Higher OSR gives finer resolution at the cost of a longer conversion.
for osr in range(4):
    sensor.set_resolution(osr, osr)                                      # Set oversampling, (osr_rh 0-3, osr_t 0-3) → None
    start = time.time()
    m = sensor.read()                                                    # Read temperature and humidity, () → dict
    print('OSR', sensor.get_resolution(), 'typ %.2f ms' % (sensor.conversion_time() * 1000.0),   # Get oversampling and typical conversion time, () → (int, int) / float s
          'read %.1f ms' % ((time.time() - start) * 1000.0), m)
sensor.set_resolution(3, 3)

# --- Heater: drive off condensation for 10 s ---
# Humidity falls and temperature rises while the heater runs; measure
# before and after, never during, then let the sensor cool.
before = sensor.read()                                                   # Read temperature and humidity, () → dict
sensor.set_heater(True)                                                  # Switch the heater, (on) → None
print('heater on:', sensor.is_heater_on(), hex(sensor.diagnostic()))     # Heater state and diagnostic byte, () → bool / int
time.sleep(10)
sensor.set_heater(False)                                                 # Switch the heater, (on) → None
after = sensor.read()                                                    # Read temperature and humidity, () → dict
print('before', before, 'after', after)

# --- Non-blocking conversion ---
# Start the conversion, do other work, then collect the result.
sensor.start_conversion()                                                # Start a conversion, () → None
time.sleep(sensor.conversion_time() * 1.25)
print('result', sensor.read_result())                                    # Read last conversion result, () → dict

# --- Reset ---
sensor.software_reset()                                                  # Soft reset, () → None
print('done: %.2f C' % sensor.read()['temperature_c'])
