from machine import Pin, SPI

from periph.chips.adc_dac.ad7705 import AD7705Minimal
from periph.connection.spi_micropython import SPIConnection

spi = SPI(1, baudrate=1_000_000, polarity=1, phase=1)
cs = Pin(15, Pin.OUT, value=1)

conn = SPIConnection(spi, cs)                                                          # Open SPI bus 1 with CS on pin 15, (bus, cs, int_pin=None, en_pin=None) → SPIConnection
adc = AD7705Minimal(conn, vref=2.5, mclk_hz=2_457_600)                                  # Create AD7705 driver, (connection, vref=2.5 V, mclk_hz=2_457_600 Hz) → None

while True:
    v = adc.read_voltage()                                                              # Read Channel 1 voltage, () → float V
    print(v)
