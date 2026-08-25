from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.magnetometer.as5600 import AS5600Full as _AS5600Full

_periph_as5600 = _AS5600Full(_periph_i2c_conn(${_address}, bus=${_bus}))
