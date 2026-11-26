from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.pressure.spl06 import SPL06Full as _SPL06Full

_periph_spl06 = _SPL06Full(_periph_i2c_conn(${_address}, bus=${_bus}))
_periph_spl06.init()