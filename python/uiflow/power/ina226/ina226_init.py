from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.power.ina226 import INA226Full as _INA226Full

_periph_ina226 = _INA226Full(_periph_i2c_conn(${_address}, bus=${_bus}), r_shunt=${_r_shunt}, max_current=${_max_current})
