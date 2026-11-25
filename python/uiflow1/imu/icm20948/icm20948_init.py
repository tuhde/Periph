from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.imu.icm20948 import ICM20948Full as _ICM20948Full

_periph_icm20948 = _ICM20948Full(_periph_i2c_conn(${_address}, bus=${_bus}))