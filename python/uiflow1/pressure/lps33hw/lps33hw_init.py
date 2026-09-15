from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.pressure.lps33hw import LPS33HWFull as _LPS33HWFull

_periph_lps33hw = _LPS33HWFull(_periph_i2c_conn(${_address}, bus=${_bus}))