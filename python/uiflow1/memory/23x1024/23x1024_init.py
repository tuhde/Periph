from periph.connection.spi_auto import SPIConnection as _periph_spi_conn
from periph.chips.memory._23x1024 import SRAM23X1024Full as _SRAM23X1024Full

_periph_23x1024 = _SRAM23X1024Full(_periph_spi_conn(bus=${_bus}, cs_pin=${_cs_pin}))