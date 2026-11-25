from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.imu.bno055 import BNO055Full as _BNO055Full

_periph_bno055 = _BNO055Full(_periph_i2c_conn(${_address}, bus=${_bus}))