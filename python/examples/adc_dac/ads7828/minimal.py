from periph.connection.i2c_auto import I2CConnection
from periph.chips.adc_dac.ads7828 import ADS7828Minimal

connection = I2CConnection(bus=1, addr=0x48)          # Create I2C connection, (bus, addr)
adc = ADS7828Minimal(connection)                      # Create ADS7828 driver, (connection, vref=None → internal 2.5 V)

raw = adc.read_raw(0)                                # Read CH0 raw code, (channel=0–7) → int
volts = adc.read_voltage(0)                          # Read CH0 voltage, (channel=0–7) → float V
all_v = adc.read_all()                               # Read all eight channels, () → list[float] V
