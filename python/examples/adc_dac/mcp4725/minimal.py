from periph.connection.i2c_auto import I2CConnection
from periph.chips.adc_dac.mcp4725 import MCP4725Minimal

connection = I2CConnection(bus=1, addr=0x60)          # Create I2C connection, (bus=1, addr=0x60)
dac = MCP4725Minimal(connection)                      # Create MCP4725 driver, (connection)

dac.set_voltage(0.5)                                 # Set output as fraction of V_DD, (fraction=0.0–1.0) → None
dac.set_raw(2048)                                    # Set raw 12-bit code, (code=0–4095) → None