from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.humidity.hdc1008 import HDC1008Full as _HDC1008Full

_periph_hdc1008 = _HDC1008Full(_periph_i2c_conn(${_address}, bus=${_bus}))
_periph_hdc1008.init()