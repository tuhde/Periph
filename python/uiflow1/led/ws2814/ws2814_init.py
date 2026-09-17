from periph.connection.neopixel_auto import NeoPixelConnection as _periph_neopixel_conn
from periph.chips.led.ws2814 import WS2814Full as _WS2814Full

_periph_ws2814 = _WS2814Full(_periph_neopixel_conn(mosi=${_mosi}, sck=${_sck}, miso=${_miso}), ${_n})
