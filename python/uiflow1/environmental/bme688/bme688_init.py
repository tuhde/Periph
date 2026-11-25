from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.environmental.bme688 import BME688Full as _BME688Full

_periph_bme688 = _BME688Full(_periph_i2c_conn(${_address}, bus=${_bus}))
