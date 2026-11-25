from periph.connection.i2c_auto import I2CConnection
from periph.chips.io_expander.pca9539 import Pca9539Full

connection = I2CConnection(0x74)                            # Create I2C connection, (i2c, addr=0x74)
                                                            # I²C bus connection to the PCA9539
chip = Pca9539Full(connection)                             # Create PCA9539 driver, (connection, addr=0x74)
                                                            # writes the reset values; all 16 pins start as inputs

chip.configure_polarity(0x0000)                             # Normal input polarity, (mask=0x0000) → None
                                                            # writes POLARITY0/1; 1 bits invert the INPUT read
chip.set_direction(0xFF00)                                  # Port 0 outputs, port 1 inputs, (mask=0xFF00) → None
                                                            # OUTPUT is written before CONFIG (no glitch)
print("CONFIG=%04X" % chip.get_direction())                 # Read direction shadow, () → int 0–65535
                                                            # 1 = input, 0 = output

for n in range(4):
    p = chip.pin(n, Pca9539Full.OUT)                       # Get P00–P03 as outputs, (n, mode=OUT)
                                                            # clears the CONFIG bit after latching OUTPUT
    p.off()                                                 # Drive pin low, () → None
                                                            # clears the OUTPUT bit via the shadow
    p.toggle()                                              # Invert the output latch, () → None
                                                            # one OUTPUT write, no read-modify-write

chip.write_port(0, 0x05)                                    # Write port 0 output latch, (port, mask) → None
                                                            # single-port OUTPUT write, updates the shadow
chip.write_all(0xA505)                                      # Write both output latches, (value) → None
                                                            # one two-byte write at OUTPUT0 (pair toggling)
print("PORT1=%02X" % chip.read_port(1))                     # Read port 1 pins, (port) → int 0–255
                                                            # reads INPUT1 (actual pin levels)
print("ALL=%04X" % chip.read_all())                         # Read all 16 pins, () → int 0–65535
                                                            # one two-byte read at INPUT0
print("CONFIG1=%02X" % chip.read_register(0x07))            # Read raw register, (reg) → int 0–255
                                                            # escape hatch for debugging
chip.write_register(0x05, 0x10)                             # Write raw register, (reg, value) → None
                                                            # POLARITY1: invert P14

def on_change(changed):
    print("interrupt, changed=%04X" % changed)

chip.on_interrupt(on_change)                                # Subscribe to INT, (callback, int_pin=None) → None
                                                            # INT line if wired, else 5 ms polling
print("changed=%04X" % chip.poll_interrupt())               # Read INPUT0/1, get changed mask, () → int 0–65535
                                                            # XOR with the last INPUT values
chip.off_interrupt()                                        # Unsubscribe, () → None
                                                            # stops delivery
chip.reset()                                                # Restore reset values, () → None
                                                            # OUTPUT=0xFF, POLARITY=0x00, CONFIG=0xFF (both ports)
