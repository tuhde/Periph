from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.pressure.lps22df import LPS22DFFull as _LPS22DFFull

_periph_lps22df = _LPS22DFFull(_periph_i2c_conn(${_address}, bus=${_bus}))