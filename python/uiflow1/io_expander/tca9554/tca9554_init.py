from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.io_expander.tca9554 import Tca9554Full as _Tca9554Full

_periph_tca9554 = _Tca9554Full(_periph_i2c_conn(${_address}, bus=${_bus}))
