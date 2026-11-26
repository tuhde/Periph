from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.gas.ccs811 import CCS811Full as _CCS811Full

_periph_ccs811 = _CCS811Full(_periph_i2c_conn(${_address}, bus=${_bus}))
_periph_ccs811.init()