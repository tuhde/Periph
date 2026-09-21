from periph.connection.spi_auto import SPIConnection as _periph_spi_conn
from periph.chips.led.apa102 import APA102Full as _APA102Full

_periph_apa102 = _APA102Full(_periph_spi_conn(bus=${_bus}, device=${_device}, polarity=0, phase=0, baudrate=1000000), ${_n})