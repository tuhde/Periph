from periph.connection.i2c_auto import I2CConnection
from periph.chips.power.ltc4151 import LTC4151Full
import time

connection = I2CConnection(0x6A)                                       # Create I²C connection, (addr=0x6A) → Connection
ltc = LTC4151Full(connection, r_sense=0.02)                            # Create LTC4151 driver, (connection, r_sense=0.02 Ω) → None

print(ltc.voltage())                                                   # Read supply voltage, () → float V
print(ltc.current())                                                   # Read load current, () → float A
print(ltc.power())                                                     # Read load power, () → float W
print(ltc.sense_voltage())                                             # Read SENSE+ − SENSE−, () → float V
print(ltc.adin_voltage())                                              # Read ADIN pin voltage, () → float V
print(ltc.read_all())                                                  # Consistent read of all channels, () → dict
                                                                       # keys: vin, sense_v, current, power, adin
print(ltc.read_raw(LTC4151Full.VIN))                                   # Read raw 12-bit code, (channel) → int
                                                                       # SENSE = 0, VIN = 1, ADIN = 2

ltc.set_sense_resistor(0.01)                                           # Change sense resistor, (ohms) → None
print(ltc.get_sense_resistor())                                        # Read sense resistor, () → float Ω
ltc.set_sense_resistor(0.02)

print(ltc.snapshot(LTC4151Full.SENSE))                                 # One snapshot conversion, (channel) → float V
                                                                       # returns to continuous scan afterwards
print(ltc.is_snapshot_mode())                                          # Snapshot mode active, () → bool
print(ltc.is_busy(LTC4151Full.VIN))                                    # Channel busy, (channel) → bool
ltc.set_continuous()                                                   # Back to continuous scan, () → None

ltc.set_stuck_bus_timer(True)                                          # Stuck-bus reset timer, (enabled) → None
ltc.set_page_rw(True)                                                  # I²C page read/write, (enabled) → None
print(hex(ltc.read_control()))                                         # Read CONTROL, () → int
ltc.write_control(0x0C)                                                # Write CONTROL, (value) → None
                                                                       # test mode bit 4 is always masked to 0

print(ltc.temperature_from_adin(900, 1200))                            # NTC example circuit, (adin_code, vin_code) → float °C
