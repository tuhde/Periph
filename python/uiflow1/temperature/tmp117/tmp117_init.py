from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.temperature.tmp117 import TMP117Full as _TMP117Full

_periph_tmp117 = _TMP117Full(_periph_i2c_conn(${_address}, bus=${_bus}))
