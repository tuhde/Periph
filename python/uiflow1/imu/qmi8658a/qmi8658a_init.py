from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.imu.qmi8658a import QMI8658AFull as _QMI8658AFull

_periph_qmi8658a = _QMI8658AFull(_periph_i2c_conn(${_address}, bus=${_bus}))