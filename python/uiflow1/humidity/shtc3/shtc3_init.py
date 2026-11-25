from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.humidity.shtc3 import SHTC3Full as _SHTC3Full

_periph_shtc3 = _SHTC3Full(_periph_i2c_conn(${_address}, bus=${_bus}))
_periph_shtc3.init()