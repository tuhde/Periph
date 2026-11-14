from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.tof.vl6180x import VL6180XFull as _VL6180XFull

_periph_vl6180x = _VL6180XFull(_periph_i2c_conn(${_address}, bus=${_bus}, reg_bytes=2))
