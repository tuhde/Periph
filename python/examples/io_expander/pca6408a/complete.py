from periph.connection.i2c_auto import I2CConnection
from periph.chips.io_expander.pca6408a import Pca6408aFull

connection = I2CConnection(0x20)                            # Create I2C connection, (i2c, addr=0x20)
                                                            # I²C bus connection to the PCA6408A
chip = Pca6408aFull(connection)                             # Create PCA6408A driver, (connection, addr=0x20)
                                                            # writes the reset values; all pins start as inputs

chip.configure_polarity(0x00)                               # Normal input polarity, (mask=0x00) → None
                                                            # writes POLARITY; 1 bits invert the INPUT read
chip.set_direction(0xF0)                                    # P0–P3 outputs, P4–P7 inputs, (mask=0xF0) → None
                                                            # OUTPUT is written before CONFIG (no glitch)
print("CONFIG=%02X" % chip.get_direction())                 # Read direction shadow, () → int 0–255
                                                            # 1 = input, 0 = output

for n in range(4):
    p = chip.pin(n, Pca6408aFull.OUT)                       # Get P0–P3 as outputs, (n, mode=OUT)
                                                            # clears the CONFIG bit after latching OUTPUT
    p.off()                                                 # Drive pin low, () → None
                                                            # clears the OUTPUT bit via the shadow
    p.toggle()                                              # Invert the output latch, () → None
                                                            # one OUTPUT write, no read-modify-write

chip.write_port(0, 0x05)                                    # Write output latch, (port=0, mask) → None
                                                            # whole-port OUTPUT write, updates the shadow
print("PORT=%02X" % chip.read_port())                       # Read all pins, (port=0) → int 0–255
                                                            # reads INPUT (actual pin levels)
print("CONFIG=%02X" % chip.read_register(0x03))             # Read raw register, (reg) → int 0–255
                                                            # escape hatch for debugging
chip.write_register(0x02, 0x10)                             # Write raw register, (reg, value) → None
                                                            # POLARITY: invert P4

def on_change(changed):
    print("interrupt, changed=%02X" % changed)

chip.on_interrupt(on_change)                                # Subscribe to INT, (callback, int_pin=None) → None
                                                            # INT line if wired, else 5 ms polling
print("changed=%02X" % chip.poll_interrupt())               # Read INPUT, get changed mask, () → int 0–255
                                                            # XOR with the last INPUT value
chip.off_interrupt()                                        # Unsubscribe, () → None
                                                            # stops delivery
chip.reset()                                                # Restore reset values, () → None
                                                            # OUTPUT=0xFF, POLARITY=0x00, CONFIG=0xFF
