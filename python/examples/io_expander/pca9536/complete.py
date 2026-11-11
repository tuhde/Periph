from periph.connection.i2c_auto import I2CConnection
from periph.chips.io_expander.pca9536 import Pca9536Full

connection = I2CConnection(0x41)                            # Create I2C connection, (i2c, addr=0x41)
                                                            # I²C bus connection to the PCA9536
chip = Pca9536Full(connection)                              # Create PCA9536 driver, (connection, addr=0x41)
                                                            # writes the reset values; all pins start as inputs

chip.configure_polarity(0x00)                               # Normal input polarity, (mask=0x00) → None
                                                            # writes POLARITY; 1 bits invert the INPUT read
chip.set_direction(0x0C)                                    # P0–P1 outputs, P2–P3 inputs, (mask=0x0C) → None
                                                            # OUTPUT is written before CONFIG (no glitch)
print("CONFIG=%X" % chip.get_direction())                   # Read direction shadow, () → int 0–15
                                                            # 1 = input, 0 = output

for n in range(2):
    p = chip.pin(n, Pca9536Full.OUT)                        # Get P0–P1 as outputs, (n, mode=OUT)
                                                            # clears the CONFIG bit after latching OUTPUT
    p.off()                                                 # Drive pin low, () → None
                                                            # clears the OUTPUT bit via the shadow
    p.toggle()                                              # Invert the output latch, () → None
                                                            # one OUTPUT write, no read-modify-write

chip.write_port(0, 0x01)                                    # Write output latch, (port=0, mask) → None
                                                            # whole-port OUTPUT write, updates the shadow
print("PORT=%X" % chip.read_port())                         # Read all pins, (port=0) → int 0–15
                                                            # reads INPUT (actual pin levels)
print("CONFIG=%02X" % chip.read_register(0x03))             # Read raw register, (reg) → int 0–255
                                                            # escape hatch for debugging
chip.write_register(0x02, 0x04)                             # Write raw register, (reg, value) → None
                                                            # POLARITY: invert P2

def on_change(changed):
    print("changed=%X" % changed)

chip.watch_changes(on_change, 0.005)                        # Start polling for changes, (callback, interval_s=0.005) → None
                                                            # no INT pin exists; a thread polls INPUT
print("changed=%X" % chip.poll_changes())                   # Read INPUT, get changed mask, () → int 0–15
                                                            # XOR with the last INPUT value
chip.unwatch_changes()                                      # Stop polling, () → None
chip.reset()                                                # Restore reset values, () → None
                                                            # OUTPUT 0xFF, POLARITY 0x00, CONFIG 0xFF
