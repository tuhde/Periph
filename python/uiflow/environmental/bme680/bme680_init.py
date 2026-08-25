from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.environmental.bme680 import BME680Full as _BME680Full

_periph_bme680 = _BME680Full(_periph_i2c_conn(${_address}, bus=${_bus}))
