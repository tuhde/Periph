from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.color.isl29125 import ISL29125Full as _ISL29125Full

_periph_isl29125 = _ISL29125Full(_periph_i2c_conn(${_address}, bus=${_bus}))
