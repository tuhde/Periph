from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.light.apds9960 import APDS9960Full as _APDS9960Full

_periph_apds9960 = _APDS9960Full(_periph_i2c_conn(${_address}, bus=${_bus}))
