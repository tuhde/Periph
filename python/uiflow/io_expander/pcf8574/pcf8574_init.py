from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.io_expander.pcf8574 import Pcf8574Full as _Pcf8574Full

_periph_pcf8574 = _Pcf8574Full(_periph_i2c_conn(${_address}, bus=${_bus}))
