from periph.connection.i2c_auto import I2CConnection
from periph.chips.adc_dac.mcp4xxx import Mcp4xxxMinimal
import time

connection = I2CConnection(0x58)
pot = Mcp4xxxMinimal(connection, part='MCP4532', r_ab=10000.0, r_wiper=75.0)  # Create MCP4XXX driver, (connection, part='MCP4532', r_ab=10000 Ω, r_wiper=75 Ω) → Mcp4xxxMinimal

pot.set_wiper(32)                                              # Set wiper, (code 0-128, channel=0) → None
pot.set_resistance(2200.0)                                     # Set W-B resistance, (ohms, channel=0) → None

while True:
    code = pot.get_wiper()                                     # Read wiper, (channel=0) → int 0-128
    ohms = pot.get_resistance()                                # Read W-B resistance, (channel=0) → float Ω
    print('code=%d  R_WB=%.0f ohm' % (code, ohms))
    time.sleep(1)
