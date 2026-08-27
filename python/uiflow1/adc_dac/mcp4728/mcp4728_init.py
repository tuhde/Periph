from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.adc_dac.mcp4728 import MCP4728Full as _MCP4728Full

_periph_mcp4728 = _MCP4728Full(_periph_i2c_conn(${_address}, bus=${_bus}))
