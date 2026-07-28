from periph.connection.i2c_auto import I2CConnection
from periph.chips.adc_dac.pcf8591 import PCF8591Minimal

connection = I2CConnection(bus=1, addr=0x48)          # Create I2C connection, (bus, addr)
adc = PCF8591Minimal(connection)                      # Create PCF8591 driver, (connection)

ch0 = adc.read_channel(0)                            # Read single channel, (channel=0–3) → int
all_raw = adc.read_all()                             # Read all four channels, () → list[int]
