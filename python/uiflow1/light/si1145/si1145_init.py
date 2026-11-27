from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.light.si1145 import Si1145Full as _Si1145Full

_periph_si1145 = _Si1145Full(_periph_i2c_conn(${_address}, bus=${_bus}))
_periph_si1145.init()