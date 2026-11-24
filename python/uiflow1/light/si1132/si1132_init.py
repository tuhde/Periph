from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.light.si1132 import Si1132Full as _Si1132Full

_periph_si1132 = _Si1132Full(_periph_i2c_conn(${_address}, bus=${_bus}))
