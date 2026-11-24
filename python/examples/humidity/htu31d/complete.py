from periph.connection.i2c_auto import I2CConnection
from periph.chips.humidity.htu31d import HTU31DFull
import time

connection = I2CConnection(0x40)                                         # Create I²C connection, (addr=0x40) → Connection
sensor = HTU31DFull(connection)                                          # Create HTU31D driver, (connection) → None
sensor.init()                                                            # Soft reset, () → None

m = sensor.read()                                                        # Read temperature and humidity, () → dict {temperature_c °C, humidity_pct %RH}
                                                                         # OSR 3 / 3 by default, both CRCs verified, humidity clamped 0-100
print(m)
print(sensor.read_humidity())                                            # Read humidity only, () → float %RH
print(sensor.read_temperature())                                         # Read temperature only, () → float °C
print(sensor.read_raw())                                                 # Read raw codes, () → (int, int)
                                                                         # (t_raw, rh_raw), 16-bit

sensor.set_resolution(1, 2)                                              # Set oversampling, (osr_rh 0-3, osr_t 0-3) → None
                                                                         # higher OSR = finer resolution and longer conversion
print(sensor.get_resolution())                                           # Get oversampling, () → (int, int)
print(sensor.conversion_time())                                          # Typical conversion time, () → float s
sensor.set_resolution(3, 3)

sensor.start_conversion()                                                # Start a conversion, () → None
                                                                         # non-blocking; wait conversion_time() before reading
time.sleep(0.03)
print(sensor.read_result())                                              # Read last conversion result, () → dict

sensor.set_heater(True)                                                  # Switch the heater, (on) → None
                                                                         # 0x04; readings drift while the heater runs
print(sensor.is_heater_on())                                             # Heater state, () → bool
sensor.set_heater(False)
print(hex(sensor.diagnostic()))                                          # Read the diagnostic byte, () → int
print(sensor.diagnostic_flags())                                         # Named diagnostic flags, () → dict
print(hex(sensor.serial_number()))                                       # Read the serial number, () → int
                                                                         # 24-bit, CRC verified
print(sensor.dew_point())                                                # Compute dew point, () → float °C
                                                                         # one measurement, Magnus formula
sensor.software_reset()                                                  # Soft reset, () → None
                                                                         # 0x1E, waits 15 ms; heater off, OSR 3 / 3
