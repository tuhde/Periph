from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.color.as7262 import AS7262Full as _AS7262Full

_periph_as7262 = _AS7262Full(_periph_i2c_conn(${_address}, bus=${_bus}))