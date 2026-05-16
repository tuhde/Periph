from machine import I2C, Pin
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.io_expander.pcf8574 import Pcf8574Minimal
import time

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=100000)           # Create I2C bus, (id, sda, scl, freq=100000 Hz)
transport = I2CTransport(i2c, 0x20)                            # Create I2C transport, (i2c, addr=0x20)
chip = Pcf8574Minimal(transport)                               # Create PCF8574 driver, (transport, addr=0x20)

p0 = chip.pin(0)                                               # Get pin proxy, (n) → Pin
p0.init(Pcf8574Minimal.OUT)                                    # Set direction, (mode=OUT) → None

p4 = chip.pin(4)                                               # Get pin proxy, (n) → Pin
p4.init(Pcf8574Minimal.IN)                                     # Set direction, (mode=IN) → None

while True:
    port = chip.read_port()                                     # Read all 8 pins, () → int bitmask
    p0.on() if (port >> 4) & 1 else p0.off()                   # Set high, () → None / Set low, () → None
    time.sleep(0.2)
