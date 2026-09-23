from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.temperature.mcp9808 import MCP9808Full as _MCP9808Full

_periph_mcp9808 = _MCP9808Full(_periph_i2c_conn(${_address}, bus=${_bus}))
