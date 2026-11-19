from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.pressure.lps22hb import LPS22HBFull as _LPS22HBFull

_periph_lps22hb = _LPS22HBFull(_periph_i2c_conn(${_address}, bus=${_bus}))