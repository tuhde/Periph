from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.power.ina260 import INA260Full as _INA260Full

_periph_ina260 = _INA260Full(_periph_i2c_conn(${_address}, bus=${_bus}))
