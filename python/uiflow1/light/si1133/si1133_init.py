from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.light.si1133 import Si1133Full as _Si1133Full

_periph_si1133 = _Si1133Full(_periph_i2c_conn(${_address}, bus=${_bus}))
_periph_si1133.init()