from machine import I2C, Pin
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.io_expander.mcp23017 import Mcp23017Minimal

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400000)           # Create I2C bus, (id, sda, scl, freq=400000 Hz)
transport = I2CTransport(i2c, 0x20)                            # Create I2C transport, (i2c, addr=0x20)
chip = Mcp23017Minimal(transport)                             # Create MCP23017 driver, (transport, addr=0x20)

p0 = chip.pin(0, Mcp23017Minimal.IN)                          # Get pin 0 as input, (n, mode=IN)
p7 = chip.pin(7, Mcp23017Minimal.OUT)                         # Get pin 7 as output (GPA7 output-only)

p7.off()                                                      # Drive pin 7 low, () → None
val = p0.value()                                              # Read pin 0 level, () → int 0|1

chip.write_port(0, 0x01)                                     # Write PORTA mask, (port=0, mask) → None
port_val = chip.read_port(0)                                 # Read PORTA, (port=0) → int 0–255

print("PORTA=%02X  pin0=%d" % (port_val, val))