from periph.connection.i2c_auto import I2CConnection
from periph.chips.humidity.si7021 import Si7021Full
import time

connection = I2CConnection(0x40)                                         # Create I²C connection, (addr=0x40) → Connection
sensor = Si7021Full(connection)                                          # Create Si7021 driver, (connection) → None

t, rh = sensor.read()                                                    # Read temperature and humidity, () → (float °C, float %RH)
                                                                         # RH without hold master, temperature of the same conversion
print(t, rh)
print(sensor.read_humidity())                                            # Read humidity only, () → float %RH
print(sensor.read_temperature())                                         # Read temperature (new conversion), () → float °C
print(sensor.read_temperature_previous())                                # Temperature of the last RH measurement, () → float °C
                                                                         # command 0xE0, no new conversion
print(sensor.read_raw_humidity(), sensor.read_raw_temperature())         # Read raw codes, () → int ; () → int
                                                                         # 16-bit codes, status bits included

sensor.set_resolution(11, 11)                                            # Set resolution, (rh_bits, temperature_bits) → None
                                                                         # (12, 14) / (8, 12) / (10, 13) / (11, 11)
print(sensor.get_resolution())                                           # Get resolution, () → (int, int)
print(sensor.conversion_time())                                          # Typical conversion time, () → float s
sensor.set_resolution(12, 14)

sensor.set_heater(True)                                                  # Switch the heater, (on) → None
                                                                         # about 3.1 mA; readings drift while on
print(sensor.is_heater_on())                                             # Heater state, () → bool
sensor.set_heater(False)
print(sensor.vdd_low())                                                  # Supply low flag, () → bool
                                                                         # True when VDD is below about 1.9 V
reg = sensor.read_user_register()                                        # Read USER_REG_1, () → int
                                                                         # 0x3A after reset
sensor.write_user_register(reg)                                          # Write USER_REG_1, (value) → None
                                                                         # reserved bits are preserved
sensor.set_clock_stretching(False)                                       # Use hold-master commands, (on) → None
                                                                         # needs bus-layer support; off polls the NACKed read header

print(hex(sensor.serial_number()))                                       # Electronic serial number, () → int
print(hex(sensor.device_type()))                                         # Device type, () → int
                                                                         # 0x15 = Si7021
print(hex(sensor.firmware_revision()))                                   # Firmware revision, () → int
                                                                         # 0xFF = firmware 1.0
print(sensor.dew_point())                                                # Compute dew point, () → float °C
                                                                         # one measurement, Magnus formula
sensor.software_reset()                                                  # Software reset, () → None
                                                                         # 0xFE, waits 15 ms
