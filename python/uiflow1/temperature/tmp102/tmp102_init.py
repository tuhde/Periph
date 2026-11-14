from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.temperature.tmp102 import TMP102Full as _TMP102Full

_periph_tmp102 = _TMP102Full(_periph_i2c_conn(${_address}, bus=${_bus}))
