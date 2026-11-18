from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.led.is31fl3731 import IS31FL3731Full as _IS31FL3731Full

_periph_is31fl3731 = _IS31FL3731Full(_periph_i2c_conn(${_address}, bus=${_bus}))
