from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.display.pcf8576 import PCF8576Full as _PCF8576Full

_periph_pcf8576 = _PCF8576Full(_periph_i2c_conn(${_address}, bus=${_bus}))
