from periph.connection.i2c_auto import I2CConnection
from periph.chips.pressure.ms5611 import MS5611Full
import time

connection = I2CConnection(0x77)                                         # Create I²C connection, (addr=0x77) → Connection
sensor = MS5611Full(connection)                                          # Create MS5611 driver, (connection) → None
sensor.init()                                                            # Reset, read PROM, verify CRC-4, () → None

m = sensor.read()                                                        # Read temperature and pressure, () → dict {temperature_c °C, pressure_pa Pa}
                                                                         # OSR 4096, second-order compensation applied
print(m)
print(sensor.read_pressure())                                            # Read pressure only, () → float Pa
print(sensor.read_temperature())                                         # Read temperature only, () → float °C
                                                                         # converts D2 only
d1, d2 = sensor.read_raw()                                               # Read raw ADC codes, () → (int, int)
                                                                         # (d1 pressure, d2 temperature), 24-bit
print(d1, d2)
print(sensor.compensate(d1, d2))                                         # Compensate raw codes, (d1, d2, second_order=True) → dict
                                                                         # pure computation, no bus access
print(sensor.compensate(d1, d2, second_order=False))
sensor.set_oversampling(1024)                                            # Select oversampling ratio, (osr) → None
                                                                         # 256, 512, 1024, 2048 or 4096
print(sensor.get_oversampling())                                         # Current oversampling ratio, () → int
print(sensor.conversion_time())                                          # Max conversion time, (osr=None) → float s
sensor.start_conversion(MS5611Full.TEMPERATURE)                          # Start a conversion, (kind, osr=None) → None
                                                                         # PRESSURE = D1, TEMPERATURE = D2; non-blocking
time.sleep(sensor.conversion_time() + 0.001)
print(sensor.read_adc())                                                 # Read the ADC result, () → int
                                                                         # only after the conversion time, 24-bit
sensor.set_oversampling(4096)
print(sensor.read_prom())                                                # Read the PROM words, () → list of 8 int
print(sensor.coefficients())                                             # Calibration coefficients, () → (c1 .. c6)
print(sensor.prom_crc_ok())                                              # Verify the PROM CRC-4, () → bool
print(sensor.serial_code())                                              # Serial code, () → int
                                                                         # PROM word 7 bits 15:4
print(sensor.altitude())                                                 # Altitude from pressure, (sea_level_pa=101325.0) → float m
                                                                         # international barometric formula
sensor.reset()                                                           # Reset and reload PROM, () → None
                                                                         # 0x1E, 3 ms wait
