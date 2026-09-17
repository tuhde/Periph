from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.other.mpr121 import Mpr121Full as _Mpr121Full

_periph_mpr121 = _Mpr121Full(_periph_i2c_conn(${_address}, bus=${_bus}))
