from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.gas.rrh62000 import RRH62000Full as _RRH62000Full

_periph_rrh62000 = _RRH62000Full(_periph_i2c_conn(${_address}, bus=${_bus}))
_periph_rrh62000.init()