from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.humidity.sht31 import SHT31Full as _SHT31Full

_periph_sht31 = _SHT31Full(_periph_i2c_conn(${_address}, bus=${_bus}))