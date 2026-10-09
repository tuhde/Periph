from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.accelerometer.bma150 import BMA150Full as _BMA150Full

_periph_bma150 = _BMA150Full(_periph_i2c_conn(${_address}, bus=${_bus}))
