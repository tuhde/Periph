from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.display.pcf8579 import PCF8579Full as _PCF8579Full

_periph_pcf8579 = _PCF8579Full(_periph_i2c_conn(${_address}, bus=${_bus}), ${_subaddress}, ${_rows}, bool(${_mixed}))
