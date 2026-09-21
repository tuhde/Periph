from periph.connection.spi_auto import SPIConnection
from periph.chips.led.apa102 import APA102Minimal
import time

connection = SPIConnection(bus=0, device=0, polarity=0, phase=0, baudrate=1_000_000)  # Create SPI connection, (bus=0, device=0, mode=0, baudrate=1MHz)
strip = APA102Minimal(connection, 30)                                                 # Create APA102 driver, (connection, n=30 pixels)

strip.fill(255, 0, 0)                                                                 # Fill all pixels red, (r=0–255, g=0–255, b=0–255) → None
time.sleep(1)
strip.fill(0, 255, 0)                                                                 # Fill all pixels green, (r=0–255, g=0–255, b=0–255) → None
time.sleep(1)
strip.fill(0, 0, 255)                                                                 # Fill all pixels blue, (r=0–255, g=0–255, b=0–255) → None
time.sleep(1)
strip.off()                                                                           # Turn off all pixels, () → None