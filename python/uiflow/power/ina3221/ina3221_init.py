from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.power.ina3221 import INA3221Full as _INA3221Full

_periph_ina3221 = _INA3221Full(_periph_i2c_conn(${_address}, bus=${_bus}), r_shunt=${_r_shunt})
