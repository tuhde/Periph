from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.pressure.bmp384 import BMP384Full as _BMP384Full

_periph_bmp384 = _BMP384Full(_periph_i2c_conn(${_address}, bus=${_bus}))
