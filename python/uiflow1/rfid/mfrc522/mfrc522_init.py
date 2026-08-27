from periph.connection.spi_auto import SPIConnection as _periph_spi_conn
from periph.chips.rfid.mfrc522 import MFRC522Full as _MFRC522Full

_periph_mfrc522 = _MFRC522Full(_periph_spi_conn(bus=${_bus}, cs_pin=${_cs_pin}))
