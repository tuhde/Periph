from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.environmental.bme280 import BME280Full as _BME280Full

_periph_bme280 = _BME280Full(_periph_i2c_conn(${_address}, bus=${_bus}))
