from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.environmental.aht21 import AHT21Full as _AHT21Full

_periph_aht21 = _AHT21Full(_periph_i2c_conn(${_address}, bus=${_bus}))
