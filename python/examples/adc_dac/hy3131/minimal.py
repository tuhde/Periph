from machine import Pin, SPI

from periph.chips.adc_dac.hy3131 import HY3131Minimal
from periph.connection.spi_micropython import SPIConnection

spi = SPI(1, baudrate=1_000_000, polarity=0, phase=0)
cs = Pin(15, Pin.OUT, value=1)

conn = SPIConnection(spi, cs)                                                          # Open SPI bus 1 with CS on pin 15, (bus, cs, int_pin=None, en_pin=None) → SPIConnection
adc = HY3131Minimal(conn)                                                               # Create HY3131 driver, (connection, vref=1.2 V) → None

while True:
    v = adc.read_voltage()                                                              # Read AD1 differential voltage, () → float V
    print(v)
