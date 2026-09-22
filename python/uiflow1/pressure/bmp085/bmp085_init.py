from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.pressure.bmp085 import BMP085Full as _BMP085Full

_periph_bmp085 = _BMP085Full(_periph_i2c_conn(${_address}, bus=${_bus}))
