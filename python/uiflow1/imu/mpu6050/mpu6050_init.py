from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.imu.mpu6050 import MPU6050Full as _MPU6050Full

_periph_mpu6050 = _MPU6050Full(_periph_i2c_conn(${_address}, bus=${_bus}))
