from periph.connection.i2c_auto import I2CConnection as _periph_i2c_conn
from periph.chips.magnetometer.hmc5883l import HMC5883LFull as _HMC5883LFull

_periph_hmc5883l = _HMC5883LFull(_periph_i2c_conn(${_address}, bus=${_bus}))
