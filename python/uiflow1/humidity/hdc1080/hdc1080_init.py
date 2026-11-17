from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.humidity.hdc1080 import HDC1080Full as _HDC1080Full

_periph_hdc1080 = _HDC1080Full(_periph_i2c_conn(${_address}, bus=${_bus}))
_periph_hdc1080.init()