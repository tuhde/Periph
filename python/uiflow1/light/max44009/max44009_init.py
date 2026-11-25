from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.light.max44009 import MAX44009Full as _MAX44009Full

_periph_max44009 = _MAX44009Full(_periph_i2c_conn(${_address}, bus=${_bus}))
