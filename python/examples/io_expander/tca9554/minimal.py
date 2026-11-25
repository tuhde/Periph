from periph.connection.i2c_auto import I2CConnection
from periph.chips.io_expander.tca9554 import Tca9554Minimal

connection = I2CConnection(0x20)                            # Create I2C connection, (i2c, addr=0x20)
chip = Tca9554Minimal(connection)                          # Create TCA9554 driver, (connection, addr=0x20)

p0 = chip.pin(0, Tca9554Minimal.IN)                        # Get P0 as input, (n, mode=IN)
p7 = chip.pin(7, Tca9554Minimal.OUT)                       # Get P7 as output, (n, mode=OUT)

p7.on()                                                     # Drive P7 high, () → None
val = p0.value()                                            # Read P0 level, () → int 0|1

chip.write_port(0, 0x81)                                    # Write the 8-bit output latch, (port=0, mask) → None
port_val = chip.read_port()                                 # Read all 8 pins, (port=0) → int 0–255

print("PORT=%02X  P0=%d" % (port_val, val))
