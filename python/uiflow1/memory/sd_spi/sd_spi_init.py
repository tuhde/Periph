from periph.connection.spi_auto import SPIConnection as _periph_spi_conn
from periph.chips.memory.sd_spi import SDSPIFull as _SDSPIFull
from periph.storage.block_device import BlockDeviceVFS as _BlockDeviceVFS

_periph_sd_spi = _SDSPIFull(_periph_spi_conn(bus=${_bus}, cs_pin=${_cs_pin}), crc=False)