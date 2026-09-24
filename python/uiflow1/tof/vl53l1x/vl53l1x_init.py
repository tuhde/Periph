from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.tof.vl53l1x import VL53L1XFull as _VL53L1XFull

_periph_vl53l1x = _VL53L1XFull(_periph_i2c_conn(${_address}, bus=${_bus}))
