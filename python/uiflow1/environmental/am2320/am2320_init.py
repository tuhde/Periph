from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.environmental.am2320 import AM2320Full as _AM2320Full

_periph_am2320 = _AM2320Full(_periph_i2c_conn(${_address}, bus=${_bus}))
