from periph.connection.i2c_auto import I2CConnection
from periph.chips.io_expander.mcp23008 import Mcp23008Full

connection = I2CConnection(0x20)                            # Create I2C connection, (i2c, addr=0x20)
                                                            # I²C bus connection to the MCP23008
chip = Mcp23008Full(connection)                             # Create MCP23008 driver, (connection, addr=0x20)
                                                            # clears OLAT; all pins start as inputs

chip.configure_pullup(0xF0)                                 # Enable pull-ups on GP4–GP7, (mask=0xF0) → None
                                                            # writes GPPU; only effective on inputs
chip.configure_polarity(0x00)                               # Normal input polarity, (mask=0x00) → None
                                                            # writes IPOL; 1 bits invert the GPIO read
chip.set_default_value(0x00)                                # Set DEFVAL compare value, (mask=0x00) → None
                                                            # reference for default-compare interrupts
chip.configure_int_pin(open_drain=False, active_high=False) # Configure INT output, (open_drain=False, active_high=False) → None
                                                            # push-pull, active-low (IOCON ODR/INTPOL)
chip.set_sequential(True)                                   # Keep auto-increment on, (enabled) → None
                                                            # IOCON SEQOP = 0 (driver default)

for n in range(4):
    p = chip.pin(n, Mcp23008Full.OUT)                       # Get GP0–GP3 as outputs, (n, mode=OUT)
                                                            # clears the IODIR bit after latching OLAT
    p.on()                                                  # Drive pin high, () → None
                                                            # sets the OLAT bit via the shadow
    p.toggle()                                              # Invert the output latch, () → None
                                                            # one OLAT write, no read-modify-write

chip.write_port(0, 0x05)                                    # Write output latch, (port=0, mask) → None
                                                            # whole-port OLAT write, updates the shadow
print("GPIO=%02X" % chip.read_port())                       # Read all pins, (port=0) → int 0–255
                                                            # reads GPIO (actual pin levels)
print("IODIR=%02X" % chip.read_register(0x00))              # Read raw register, (reg) → int 0–255
                                                            # escape hatch for debugging
chip.write_register(0x02, 0x10)                             # Write raw register, (reg, value) → None
                                                            # GPINTEN: arm GP4

def on_change(status):
    print("interrupt, INTF=%02X" % status)

chip.on_interrupt(on_change, mode='default')                # Subscribe to INT, (callback, int_pin=None, mode='change') → None
                                                            # INT line if wired, else 5 ms polling
print("INTF=%02X" % chip.poll_interrupt())                  # Read INTF and clear, () → int 0–255
                                                            # INTCAP is read and discarded
print("INTCAP=%02X" % chip.read_capture())                  # Read captured port state, () → int 0–255
                                                            # port snapshot at the interrupt
chip.off_interrupt()                                        # Unsubscribe, () → None
                                                            # stops delivery
chip.reset()                                                # Restore reset values, () → None
                                                            # rewrites all registers, clears shadows
