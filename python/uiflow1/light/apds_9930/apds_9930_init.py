from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.light.apds_9930 import APDS9930Full as _APDS9930Full

_periph_apds9930 = _APDS9930Full(_periph_i2c_conn(${_address}, bus=${_bus}))