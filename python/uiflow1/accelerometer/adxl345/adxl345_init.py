from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.accelerometer.adxl345 import ADXL345Full as _ADXL345Full

_periph_adxl345 = _ADXL345Full(_periph_i2c_conn(${_address}, bus=${_bus}))