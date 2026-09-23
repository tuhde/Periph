from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.motor.drv8830 import DRV8830Full as _DRV8830Full

_periph_drv8830 = _DRV8830Full(_periph_i2c_conn(${_address}, bus=${_bus}))
