from periph.connection.i2c_auto import I2CConnection
from periph.chips.io_expander.mcp23008 import Mcp23008Minimal

connection = I2CConnection(0x20)                            # Create I2C connection, (i2c, addr=0x20)
chip = Mcp23008Minimal(connection)                          # Create MCP23008 driver, (connection, addr=0x20)

p0 = chip.pin(0, Mcp23008Minimal.IN)                        # Get GP0 as input, (n, mode=IN)
p7 = chip.pin(7, Mcp23008Minimal.OUT)                       # Get GP7 as output, (n, mode=OUT)

p7.on()                                                     # Drive GP7 high, () → None
val = p0.value()                                            # Read GP0 level, () → int 0|1

chip.write_port(0, 0x81)                                    # Write the 8-bit output latch, (port=0, mask) → None
port_val = chip.read_port()                                 # Read all 8 pins, (port=0) → int 0–255

print("GPIO=%02X  GP0=%d" % (port_val, val))
