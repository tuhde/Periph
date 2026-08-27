from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.io_expander.pcf8575 import Pcf8575Full as _Pcf8575Full

_periph_pcf8575 = _Pcf8575Full(_periph_i2c_conn(${_address}, bus=${_bus}))
