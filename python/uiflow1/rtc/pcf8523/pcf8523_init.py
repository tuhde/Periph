from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.rtc.pcf8523 import PCF8523Full as _PCF8523Full, I2C_ADDRESS as _PCF8523_ADDR

_periph_pcf8523 = _PCF8523Full(_periph_i2c_conn(_PCF8523_ADDR, bus=${_bus}))
