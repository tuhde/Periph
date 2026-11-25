from periph.connection.i2c_auto import I2CConnection
from periph.chips.io_expander.pca6416a import Pca6416aMinimal

connection = I2CConnection(0x20)                            # Create I2C connection, (i2c, addr=0x20)
chip = Pca6416aMinimal(connection)                          # Create PCA6416A driver, (connection, addr=0x20)

p0 = chip.pin(0, Pca6416aMinimal.IN)                        # Get P0_0 as input, (n, mode=IN)
p15 = chip.pin(15, Pca6416aMinimal.OUT)                     # Get P1_7 as output, (n, mode=OUT)

p15.on()                                                    # Drive P1_7 high, () → None
val = p0.value()                                            # Read P0_0 level, () → int 0|1

chip.write_port(0, 0x81)                                    # Write port 0 output latch, (port, mask) → None
port_val = chip.read_port(1)                                # Read the 8 pins of port 1, (port) → int 0–255

print("PORT1=%02X  P0=%d" % (port_val, val))
