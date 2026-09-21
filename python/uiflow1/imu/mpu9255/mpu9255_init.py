from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.imu.mpu9255 import MPU9255Full as _MPU9255Full

_periph_mpu9255 = _MPU9255Full(_periph_i2c_conn(${_address}, bus=${_bus}), _periph_i2c_conn(0x0C, bus=${_bus}))