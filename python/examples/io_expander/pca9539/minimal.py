from periph.connection.i2c_auto import I2CConnection
from periph.chips.io_expander.pca9539 import Pca9539Minimal

connection = I2CConnection(0x74)                            # Create I2C connection, (i2c, addr=0x74)
chip = Pca9539Minimal(connection)                          # Create PCA9539 driver, (connection, addr=0x74)

p0 = chip.pin(0, Pca9539Minimal.IN)                        # Get P00 as input, (n, mode=IN)
p15 = chip.pin(15, Pca9539Minimal.OUT)                     # Get P17 as output, (n, mode=OUT)

p15.on()                                                    # Drive P17 high, () → None
val = p0.value()                                            # Read P00 level, () → int 0|1

chip.write_port(0, 0x81)                                    # Write port 0 output latch, (port, mask) → None
port_val = chip.read_port(1)                                # Read the 8 pins of port 1, (port) → int 0–255

print("PORT1=%02X  P0=%d" % (port_val, val))
