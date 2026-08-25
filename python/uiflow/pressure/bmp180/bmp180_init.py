from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.pressure.bmp180 import BMP180Full as _BMP180Full

_periph_bmp180 = _BMP180Full(_periph_i2c_conn(${_address}, bus=${_bus}), oss=${_oversampling})
