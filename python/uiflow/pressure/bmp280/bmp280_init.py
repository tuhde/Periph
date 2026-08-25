from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.pressure.bmp280 import BMP280Full as _BMP280Full

_periph_bmp280 = _BMP280Full(_periph_i2c_conn(${_address}, bus=${_bus}))
