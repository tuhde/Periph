from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.accelerometer.lsm303 import LSM303Full as _LSM303Full

_periph_lsm303 = _LSM303Full(_periph_i2c_conn(0x19, bus=${_bus}), _periph_i2c_conn(0x1E, bus=${_bus}))
