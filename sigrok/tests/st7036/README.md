# ST7036 capture

`st7036_demo.sr` — the STM32Cube `demo` example (`cpp/examples/stm32cube/display/ST7036/demo`) driving an
EA DOGM162W-A (3.3 V, 2x16) from a NUCLEO-F411RE, captured at 4 MHz. The first ~2 s are the end of the
previous run, then the board is reset: hardware reset, DOG 3.3 V init, five bar glyphs into CGRAM, title,
progress bar on line 2, contrast sweep.

Channels: D0 = MOSI (SI), D1 = SCLK, D2 = CS (CSB), D3 = D/C (RS), D4 = Reset.

```
sigrok-cli -i st7036_demo.sr \
  -P display_spi:clk=SCLK:mosi=MOSI:cs=CS:dc=D/C:res=Reset,st7036 -A st7036
```

Capture without a trigger: with `--triggers` the fx2lafw driver writes 16-bit units and the decoder timing
comes out at half the real value.
