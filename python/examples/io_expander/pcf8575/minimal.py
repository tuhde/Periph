from periph.connection.i2c_auto import I2CConnection
from periph.chips.io_expander.pcf8575 import Pcf8575Minimal
import time

connection = I2CConnection(0x20)                            # Create I2C connection, (i2c, addr=0x20)
chip = Pcf8575Minimal(connection)                              # Create PCF8575 driver, (connection, addr=0x20)

p0 = chip.pin(0)                                                # Get pin proxy, (n=0) → Pin
p0.init(Pcf8575Minimal.OUT)                                     # Set direction, (mode=OUT) → None

p8 = chip.pin(8)                                                # Get pin proxy, (n=8) → Pin
p8.init(Pcf8575Minimal.IN)                                      # Set direction, (mode=IN) → None

while True:
    port0 = chip.read_port(0)                                    # Read Port 0, (port=0) → int bitmask
    port1 = chip.read_port(1)                                    # Read Port 1, (port=1) → int bitmask
    p0.on() if (port1 >> 0) & 1 else p0.off()                   # Set high, () → None / Set low, () → None
    time.sleep(0.2)