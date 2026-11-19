from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.humidity.hs30xx import HS30xxFull as _HS30xxFull

_periph_hs30xx = _HS30xxFull(_periph_i2c_conn(${_address}, bus=${_bus}))