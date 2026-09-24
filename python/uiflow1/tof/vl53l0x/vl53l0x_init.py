from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.tof.vl53l0x import VL53L0XFull as _VL53L0XFull

_periph_vl53l0x = _VL53L0XFull(_periph_i2c_conn(${_address}, bus=${_bus}))
