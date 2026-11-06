from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.accelerometer.mma8451q import MMA8451QFull as _MMA8451QFull

_periph_mma8451q = _MMA8451QFull(_periph_i2c_conn(${_address}, bus=${_bus}))
