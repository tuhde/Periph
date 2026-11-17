from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.environmental.aht20 import AHT20Full as _AHT20Full

_periph_aht20 = _AHT20Full(_periph_i2c_conn(${_address}, bus=${_bus}))
