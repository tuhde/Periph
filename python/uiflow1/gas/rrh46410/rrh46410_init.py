from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.gas.rrh46410 import RRH46410Full as _RRH46410Full

_periph_rrh46410 = _RRH46410Full(_periph_i2c_conn(${_address}, bus=${_bus}))
_periph_rrh46410.init()