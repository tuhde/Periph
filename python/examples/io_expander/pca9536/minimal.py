from periph.connection.i2c_auto import I2CConnection
from periph.chips.io_expander.pca9536 import Pca9536Minimal

connection = I2CConnection(0x41)                            # Create I2C connection, (i2c, addr=0x41)
chip = Pca9536Minimal(connection)                           # Create PCA9536 driver, (connection, addr=0x41)

p2 = chip.pin(2, Pca9536Minimal.IN)                         # Get P2 as input, (n, mode=IN)
p0 = chip.pin(0, Pca9536Minimal.OUT)                        # Get P0 as output, (n, mode=OUT)

p0.on()                                                     # Drive P0 high, () → None
val = p2.value()                                            # Read P2 level, () → int 0|1

chip.write_port(0, 0x09)                                    # Write the 4-bit output latch, (port=0, mask) → None
port_val = chip.read_port()                                 # Read all 4 pins, (port=0) → int 0–15

print("PORT=%X  P2=%d" % (port_val, val))
