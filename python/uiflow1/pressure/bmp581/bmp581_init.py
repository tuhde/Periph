from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.pressure.bmp581 import BMP581Full as _BMP581Full

_periph_bmp581 = _BMP581Full(_periph_i2c_conn(${_address}, bus=${_bus}))