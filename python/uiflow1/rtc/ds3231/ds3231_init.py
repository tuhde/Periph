from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.rtc.ds3231 import DS3231Full as _DS3231Full, I2C_ADDRESS as _DS3231_ADDR

_periph_ds3231 = _DS3231Full(_periph_i2c_conn(_DS3231_ADDR, bus=${_bus}))
