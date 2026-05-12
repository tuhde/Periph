from periph.transport.i2c_micropython import I2CTransport
from periph.chips.adc_dac.mcp4725 import MCP4725Minimal

transport = I2CTransport(bus=1, addr=0x60)          # Create I2C transport, (bus=1, addr=0x60)
dac = MCP4725Minimal(transport)                      # Create MCP4725 driver, (transport)

dac.set_voltage(0.5)                                 # Set output as fraction of V_DD, (fraction=0.0–1.0) → None
dac.set_raw(2048)                                    # Set raw 12-bit code, (code=0–4095) → None