from periph.connection.i2c_auto import I2CConnection
from periph.chips.power.ina260 import INA260Full
import time

connection = I2CConnection(0x40)                                       # Create I²C connection, (addr=0x40) → Connection
ina = INA260Full(connection)                                           # Create INA260 driver, (connection) → None

print(hex(ina.manufacturer_id()))                                      # Read manufacturer ID, () → int
                                                                       # expect 0x5449 (TI)
print(hex(ina.die_id()))                                               # Read die ID, () → int
                                                                       # (value >> 4) == 0x227, low nibble = revision

print(ina.voltage())                                                   # Read bus voltage, () → float V
print(ina.current())                                                   # Read current, () → float A (signed)
print(ina.power())                                                     # Read power, () → float W
print(ina.conversion_ready())                                          # Conversion Ready Flag, () → bool
                                                                       # reading Mask/Enable clears CVRF
print(ina.overflow())                                                  # Math overflow flag, () → bool

ina.configure(avg=INA260Full.AVG_64, vbus_ct=INA260Full.CT_1100US,     # Configure, (avg, vbus_ct, ish_ct, mode) → None
              ish_ct=INA260Full.CT_1100US,
              mode=INA260Full.MODE_SHUNT_BUS_CONT)

ina.trigger(INA260Full.MODE_SHUNT_BUS_TRIGGERED)                       # Start one triggered conversion, (mode) → None
while not ina.conversion_ready():
    pass

ina.set_alert(INA260Full.POL, limit=1.0, latch=1)                      # Alert on power, (function, limit, polarity, latch) → None
                                                                       # limit in W; limit register written first
print(hex(ina.alert_flags()))                                          # Read Mask/Enable, () → int
ina.set_alert(INA260Full.OCL, limit=5.0)                               # Over-current alert, limit in A
ina.set_alert(INA260Full.BOL, limit=5.5)                               # Bus over-voltage alert, limit in V
ina.set_alert(INA260Full.BUL, limit=4.5)                               # Bus under-voltage alert, limit in V
ina.set_alert(INA260Full.CNVR)                                         # Conversion-ready alert, limit ignored
ina.clear_alert()                                                      # Disable all alerts, () → None

ina.shutdown()                                                         # Power down, () → None
time.sleep(0.001)
ina.wake()                                                             # Restore previous mode, () → None

ina.reset()                                                            # Reset to power-on defaults, () → None
