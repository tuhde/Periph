from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.power.lc709203f import LC709203FFull as _LC709203FFull

_periph_lc709203f = _LC709203FFull(_periph_i2c_conn(${_address}, bus=${_bus}), ${_capacity}, ${_profile})
