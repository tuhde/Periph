from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.environmental.am2315 import AM2315Full as _AM2315Full

_periph_am2315 = _AM2315Full(_periph_i2c_conn(${_address}, bus=${_bus}))
