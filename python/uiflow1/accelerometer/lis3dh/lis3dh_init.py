from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.accelerometer.lis3dh import LIS3DHFull as _LIS3DHFull

_periph_lis3dh = _LIS3DHFull(_periph_i2c_conn(${_address}, bus=${_bus}))