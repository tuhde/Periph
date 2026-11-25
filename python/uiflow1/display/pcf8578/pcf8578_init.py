from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.display.pcf8578 import PCF8578Full as _PCF8578Full

_periph_pcf8578 = _PCF8578Full(_periph_i2c_conn(${_address}, bus=${_bus}), ${_rows})
