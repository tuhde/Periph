from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.rtc.pcf85063a import PCF85063AFull as _PCF85063AFull, I2C_ADDRESS as _PCF85063A_ADDR

_periph_pcf85063a = _PCF85063AFull(_periph_i2c_conn(_PCF85063A_ADDR, bus=${_bus}))
