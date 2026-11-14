from periph.connection.i2c_auto import I2CConnection
from periph.chips.humidity.htu21df import HTU21DFFull
import time

connection = I2CConnection(0x40)                                         # Create I²C connection, (addr=0x40) → Connection
sensor = HTU21DFFull(connection)                                         # Create HTU21D-F driver, (connection) → None
sensor.init()                                                            # Soft reset, () → None

t, rh = sensor.read()                                                    # Read temperature and humidity, () → (float °C, float %RH)
                                                                         # no hold master, CRC checked
print(t, rh)
print(sensor.read_humidity())                                            # Read humidity only, () → float %RH
                                                                         # not clamped, ASIC range -6 ... 118 %RH
print(sensor.read_temperature())                                         # Read temperature only, () → float °C
print(sensor.read_humidity_compensated())                                # Temperature-compensated humidity, () → float %RH
                                                                         # -0.15 %RH/°C relative to 25 °C
print(sensor.read_raw())                                                 # Read raw codes, () → (int, int)
                                                                         # (temperature, humidity), status bits cleared

sensor.set_resolution(3)                                                 # Set resolution, (res) → None
                                                                         # 0 = 12/14 bit, 1 = 8/12, 2 = 10/13, 3 = 11/11
print(sensor.get_resolution())                                           # Get resolution, () → int
sensor.set_resolution(0)

sensor.set_heater(True)                                                  # Switch the heater, (on) → None
                                                                         # about 5.5 mW; readings drift while on
print(sensor.heater())                                                   # Heater state, () → bool
sensor.set_heater(False)
print(sensor.battery_low())                                              # End-of-battery flag, () → bool
                                                                         # True when VDD is below about 2.25 V
print(hex(sensor.read_user_register()))                                  # Read the user register, () → int
                                                                         # 0x02 after reset
sensor.set_hold_master(False)                                            # Use hold-master commands, (on) → None
                                                                         # needs bus-layer clock stretching; off polls the NACKed read header
print(sensor.dew_point())                                                # Compute dew point, () → float °C
                                                                         # one measurement
sensor.soft_reset()                                                      # Soft reset, () → None
                                                                         # 0xFE, waits 15 ms; heater bit survives
