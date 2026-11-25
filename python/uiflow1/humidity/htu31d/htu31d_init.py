from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.humidity.htu31d import HTU31DFull as _HTU31DFull

_periph_htu31d = _HTU31DFull(_periph_i2c_conn(${_address}, bus=${_bus}))
_periph_htu31d.init()