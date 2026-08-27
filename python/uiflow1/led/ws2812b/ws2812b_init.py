from periph.connection.neopixel_auto import NeoPixelConnection as _periph_neopixel_conn
from periph.chips.led.ws2812b import WS2812BFull as _WS2812BFull

_periph_ws2812b = _WS2812BFull(_periph_neopixel_conn(mosi=${_mosi}, sck=${_sck}, miso=${_miso}), ${_n})
