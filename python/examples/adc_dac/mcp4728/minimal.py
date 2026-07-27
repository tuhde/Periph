from periph.connection.i2c_auto import I2CConnection
from periph.chips.adc_dac.mcp4728 import MCP4728Minimal

connection = I2CConnection(bus=1, addr=0x60)          # Create I2C connection, (bus=1, addr=0x60)
dac = MCP4728Minimal(connection)                      # Create MCP4728 driver, (connection)

dac.set_voltage(0, 0.5)                              # Set channel A output as fraction of V_DD, (channel=0–3, fraction=0.0–1.0) → None
dac.set_raw(1, 2048)                                 # Set channel B raw 12-bit code, (channel=0–3, code=0–4095) → None
dac.set_all([0.0, 0.25, 0.5, 1.0])                   # Update all four channels simultaneously, (fractions[4]) → None
