from periph.connection.i2c_auto import I2CConnection
from periph.chips.io_expander.mcp23017 import Mcp23017Full

connection = I2CConnection(0x20)                            # Create I2C connection, (i2c, addr=0x20)
chip = Mcp23017Full(connection)                                 # Create MCP23017 driver, (connection, addr=0x20)

chip.configure_pullup(0, 0x3F)                                 # Enable pull-ups on GPA0–GPA5, (port=0, mask=0x3F) → None
chip.configure_polarity(0, 0x00)                              # Set normal polarity PORTA, (port=0, mask=0x00) → None

p7 = chip.pin(7, Mcp23017Full.OUT)                           # GPA7 is output-only; get as output
p7.off()                                                      # Drive GPA7 low, () → None

for n in range(8):
    p = chip.pin(n, Mcp23017Full.IN)                          # Get pin n as input, (n, mode=IN)
    val = p.value()                                           # Read pin level, () → int 0|1
    print("GPA%d=%d" % (n, val), end="  ")
print()

chip.write_port(0, 0x80)                                     # Write GPA7 high (other pins are inputs), (port=0, mask=0x80) → None

chip.set_default_value(0, 0x00)                              # Set DEFVAL for PORTA, (port=0, mask=0x00) → None

def on_change(mask):
    print("PORTA changed: %02X" % mask)

chip.on_interrupt(on_change, port=0, mode='default')          # Enable INT on PORTA, (callback, port=0, mode='default') → None

chip.off_interrupt(port=0)                                     # Disable INT on PORTA, (port=0) → None
print("Interrupt stopped")
print("PORTA=%02X  PORTB=%02X" % (chip.read_port(0), chip.read_port(1)))