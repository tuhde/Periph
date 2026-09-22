from periph.connection.sipo_micropython import SiPoConnection
from periph.chips.io_expander.tpic6b595 import Tpic6b595Minimal
from machine import SPI, Pin
import time

spi = SPI(0, baudrate=1_000_000, polarity=0, phase=0)                     # Create SPI bus, (id=0, baud=1 MHz, mode=0)
rck = Pin(17, Pin.OUT)                                                     # Configure RCK GPIO, (pin=17, mode=OUT)
connection = SiPoConnection(spi, rck)                                      # Create SiPo connection, (spi, rck, srclr=None, g=None)

chip = Tpic6b595Minimal(connection, num_devices=1)                          # Create TPIC6B595 driver, (connection, num_devices=1)
                                                                            # initialises every output to OFF (shadow zeroed, latched once)

p0 = chip.pin(0)                                                           # Get pin proxy, (n=0) → Pin
p7 = chip.pin(7)                                                           # Get pin proxy, (n=7) → Pin

while True:
    p0.on()                                                                # Set DMOS output ON, () → None
    p7.off()                                                               # Set DMOS output OFF, () → None
    time.sleep(0.5)
    p0.off()                                                               # Set DMOS output OFF, () → None
    p7.on()                                                                # Set DMOS output ON, () → None
    time.sleep(0.5)
