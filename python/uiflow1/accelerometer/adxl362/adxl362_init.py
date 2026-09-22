from periph.connection.spi_auto import SPIConnection as _periph_spi_conn
from periph.chips.accelerometer.adxl362 import ADXL362Full as _ADXL362Full

_periph_adxl362 = _ADXL362Full(_periph_spi_conn(bus=${_bus}, cs_pin=${_cs_pin}))