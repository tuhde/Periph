from machine import I2C, Pin
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.io_expander.pcf8575 import Pcf8575Minimal
import time

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400000)           # Create I2C bus, (id, sda, scl, freq=400000 Hz)
transport = I2CTransport(i2c, 0x20)                            # Create I2C transport, (i2c, addr=0x20)
chip = Pcf8575Minimal(transport)                              # Create PCF8575 driver, (transport, addr=0x20)

p0 = chip.pin(0)                                                # Get pin proxy, (n=0) → Pin
p0.init(Pcf8575Minimal.OUT)                                     # Set direction, (mode=OUT) → None

p8 = chip.pin(8)                                                # Get pin proxy, (n=8) → Pin
p8.init(Pcf8575Minimal.IN)                                      # Set direction, (mode=IN) → None

while True:
    port0 = chip.read_port(0)                                    # Read Port 0, (port=0) → int bitmask
    port1 = chip.read_port(1)                                    # Read Port 1, (port=1) → int bitmask
    p0.on() if (port1 >> 0) & 1 else p0.off()                   # Set high, () → None / Set low, () → None
    time.sleep(0.2)