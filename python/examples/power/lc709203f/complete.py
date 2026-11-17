from periph.connection.i2c_auto import I2CConnection
from periph.chips.power.lc709203f import LC709203FFull
import time

connection = I2CConnection(0x0B)                                       # Create I²C connection, (addr=0x0B) → Connection
gauge = LC709203FFull(connection, capacity_mah=1000, profile=1)        # Create LC709203F driver, (connection, capacity_mah, profile) → None

print(gauge.voltage())                                                 # Cell voltage, () → float V
print(gauge.percent())                                                 # State of charge (ITE), () → float %
print(gauge.temperature())                                             # Cell temperature, () → float °C
gauge.set_temperature(25.0)                                            # Host temperature (I²C mode), (celsius) → None
                                                                       # clamped to -20...+60 °C

print(gauge.rsoc())                                                    # RSOC register, () → int % (0-100)
gauge.set_rsoc(50)                                                     # Force RSOC, (percent) → None
gauge.init_rsoc()                                                      # Initial RSOC from present voltage, () → None
gauge.before_rsoc()                                                    # RSOC from max voltage since reset, () → None

gauge.use_thermistor(3380)                                             # Thermistor mode, (b_constant) → None
print(gauge.is_thermistor_mode())                                      # Temperature source, () → bool
print(gauge.get_b_constant())                                          # Thermistor B constant, () → int K
gauge.use_i2c_temperature(25.0)                                        # Back to I²C temperature mode, (celsius) → None

gauge.set_apa(0x19)                                                    # Adjustment Pack Application, (value) → None
print(gauge.get_apa())                                                 # () → int
gauge.set_apt(0x1E)                                                    # Adjustment Pack Thermistor, (value) → None
print(gauge.get_apt())                                                 # () → int

gauge.set_current_direction(LC709203FFull.DIRECTION_AUTO)              # Current direction, (mode) → None
                                                                       # DIRECTION_AUTO / _CHARGE / _DISCHARGE
print(gauge.get_current_direction())                                   # () → int
gauge.set_profile(1)                                                   # Battery profile 0/1, (profile) → None
                                                                       # re-initializes the chip (90 ms)
print(gauge.get_profile())                                             # () → int
print(hex(gauge.profile_id()))                                         # Number of the parameter, () → int
print(hex(gauge.ic_version()))                                         # IC version, () → int

gauge.set_alarm_rsoc(20)                                               # Low-RSOC alarm, (percent) → None; 0 = off
print(gauge.get_alarm_rsoc())                                          # () → int %
gauge.set_alarm_voltage(3.5)                                           # Low-voltage alarm, (volts) → None; 0 = off
print(gauge.get_alarm_voltage())                                       # () → float V
print(gauge.poll_interrupt())                                          # Alarm bitmask, () → int
                                                                       # ALARM_RSOC = 1, ALARM_VOLTAGE = 2
gauge.on_interrupt(lambda status: print('alarm', status))              # Subscribe to ALARMB, (callback, int_pin=None) → None
time.sleep(2)
gauge.off_interrupt()                                                  # Unsubscribe, () → None

gauge.sleep()                                                          # Sleep mode, () → None
gauge.wake()                                                           # Wake up, () → None
print(gauge.is_sleeping())                                             # Power mode, () → bool
