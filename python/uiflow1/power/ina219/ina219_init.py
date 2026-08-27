from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.power.ina219 import INA219Full as _INA219Full

_periph_ina219 = _INA219Full(_periph_i2c_conn(${_address}, bus=${_bus}), r_shunt=${_r_shunt}, max_current=${_max_current})
