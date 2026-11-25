from periph.connection.i2c_auto import I2CConnection
from periph.chips.humidity.hdc1008 import HDC1008Full
import time

connection = I2CConnection(0x40)                                         # Create I²C connection, (addr=0x40) → Connection
sensor = HDC1008Full(connection)                                         # Create HDC1008 driver, (connection) → None

sensor.init()                                                            # Write default configuration, () → None
print('serial id 0x%010X' % sensor.serial_id())                          # Read 40-bit serial ID, () → int
print('config 0x%04X' % sensor.read_config())                            # Read configuration register, () → int
t, rh = sensor.read()                                                    # Read temperature and humidity, () → (float, float)
print('%.2f C %.1f %%RH' % (t, rh))
print('temperature %.2f C' % sensor.read_temperature())                  # Read temperature only, () → float °C
print('humidity %.1f %%RH' % sensor.read_humidity())                     # Read humidity only, () → float %RH
print('raw', sensor.read_raw())                                          # Read raw words, () → (int, int)
sensor.set_resolution(11, 8)                                             # Set resolution, (temperature_bits, humidity_bits) → None
print('conversion time %.2f ms' % (sensor.conversion_time() * 1000))     # Typical conversion time, () → float s
sensor.set_resolution(14, 14)
sensor.set_heater(True)                                                  # Switch the heater, (on) → None
print('heater on:', sensor.is_heater_on())                               # Heater state, () → bool
sensor.set_heater(False)
print('battery low:', sensor.battery_low())                              # VDD below 2.8 V, () → bool
sensor.software_reset()                                                  # Software reset, () → None


def on_ready(result):
    print('data ready', result)


sensor.on_interrupt(on_ready)                                            # Subscribe to data ready, (callback, int_pin=None) → None
sensor._connection.write(bytes([0x00]))                                  # trigger one measurement
time.sleep(0.1)
print('ready:', sensor.poll_interrupt())                                 # Data ready check, () → bool
sensor.off_interrupt()                                                   # Unsubscribe, () → None
