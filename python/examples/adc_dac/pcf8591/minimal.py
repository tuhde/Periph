from periph.transport.i2c_micropython import I2CTransport
from periph.chips.adc_dac.pcf8591 import PCF8591Minimal

transport = I2CTransport(bus=1, addr=0x48)          # Create I2C transport, (bus, addr)
adc = PCF8591Minimal(transport)                      # Create PCF8591 driver, (transport)

ch0 = adc.read_channel(0)                            # Read single channel, (channel=0–3) → int
all_raw = adc.read_all()                             # Read all four channels, () → list[int]
