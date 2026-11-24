from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.humidity.hts221 import HTS221Full as _HTS221Full

_periph_hts221 = _HTS221Full(_periph_i2c_conn(${_address}, bus=${_bus}))