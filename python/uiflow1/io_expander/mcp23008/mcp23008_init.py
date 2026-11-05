from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.io_expander.mcp23008 import Mcp23008Full as _Mcp23008Full

_periph_mcp23008 = _Mcp23008Full(_periph_i2c_conn(${_address}, bus=${_bus}))
