from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.adc_dac.mcp3422 import MCP3422Full as _MCP3422Full

_periph_mcp3422 = _MCP3422Full(_periph_i2c_conn(${_address}, bus=${_bus}))
