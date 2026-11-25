from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.humidity.hs40xx import HS40xxFull as _HS40xxFull

_periph_hs40xx = _HS40xxFull(_periph_i2c_conn(${_address}, bus=${_bus}))