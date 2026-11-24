from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.light.apds9250 import APDS9250Full as _APDS9250Full

_periph_apds9250 = _APDS9250Full(_periph_i2c_conn(${_address}, bus=${_bus}))
