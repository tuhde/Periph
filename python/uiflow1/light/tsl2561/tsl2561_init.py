from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.light.tsl2561 import TSL2561Full as _TSL2561Full

_periph_tsl2561 = _TSL2561Full(_periph_i2c_conn(${_address}, bus=${_bus}))
