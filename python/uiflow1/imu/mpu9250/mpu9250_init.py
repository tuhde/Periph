from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.imu.mpu9250 import MPU9250Full as _MPU9250Full

_periph_mpu9250 = _MPU9250Full(_periph_i2c_conn(${_address}, bus=${_bus}), _periph_i2c_conn(0x0C, bus=${_bus}))