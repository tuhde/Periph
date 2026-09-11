from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.pressure.lps28dfw import LPS28DFWFull as _LPS28DFWFull

_periph_lps28dfw = _LPS28DFWFull(_periph_i2c_conn(${_address}, bus=${_bus}))