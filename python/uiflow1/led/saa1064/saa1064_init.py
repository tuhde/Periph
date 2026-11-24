from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.led.saa1064 import SAA1064Full as _SAA1064Full

_periph_saa1064 = _SAA1064Full(_periph_i2c_conn(${_address}, bus=${_bus}))
