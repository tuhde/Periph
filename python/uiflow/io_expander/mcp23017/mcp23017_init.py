from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.io_expander.mcp23017 import Mcp23017Full as _Mcp23017Full

_periph_mcp23017 = _Mcp23017Full(_periph_i2c_conn(${_address}, bus=${_bus}))
