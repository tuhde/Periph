from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.gyroscope.l3gd20h import L3GD20HFull as _L3GD20HFull

_periph_l3gd20h = _L3GD20HFull(_periph_i2c_conn(${_address}, bus=${_bus}))