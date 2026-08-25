from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.adc_dac.mcp4725 import MCP4725Full as _MCP4725Full

_periph_mcp4725 = _MCP4725Full(_periph_i2c_conn(${_address}, bus=${_bus}))
