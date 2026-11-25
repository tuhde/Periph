from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.motor.drv2605 import DRV2605Full as _DRV2605Full

_periph_drv2605 = _DRV2605Full(_periph_i2c_conn(0x5A, bus=${_bus}), lra=bool(${_lra}))
