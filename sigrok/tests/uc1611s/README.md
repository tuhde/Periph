# UC1611s capture

`uc1611s_demo.sr` — the STM32Cube `demo` example (`cpp/examples/stm32cube/display/UC1611s/demo`) driving an
EA DOGXL240-7 (3.3 V, 240x128) over I²C from a NUCLEO-F411RE (I2C1, 100 kHz, external pull-ups), captured at
2 MHz for 12 s with the board reset shortly after the capture starts: hardware reset, System Reset, DOG
bottom-view init, RAM clear, display on, status reads, then the demo screens.

Channels: D4 = Reset, D6 = SCL, D7 = SDA. Device addresses 0x3C (command) and 0x3D (data).

```
sigrok-cli -i uc1611s_demo.sr -P i2c:scl=SCL:sda=SDA,uc1611s -A uc1611s
```

The capture contains no chip NACKs: the 28 NACKs seen by the `i2c` decoder are the master's NACK of the last
byte of each of the 28 status reads, which is normal.

## 4-wire SPI capture

`uc1611s_spi_demo.sr` — the STM32Cube `demo_spi` example (`cpp/examples/stm32cube/display/UC1611s/demo_spi`) driving the
same EA DOGXL240-7 over 4-wire SPI (SPI1, mode 0, MSB first) from a NUCLEO-F411RE, captured at 24 MHz for 4 s with the board
reset one second after the capture starts: the first events are the glitches of the board reset (a one-bit CS window, short
RST pulses), then hardware reset, System Reset, DOG bottom-view init, RAM clear (20 pages of 240 bytes) and the first demo frames.

Channels: D0 = SI (MOSI), D1 = SCK, D2 = CS0, D3 = CD (D/C), D4 = RST. At least 4x oversampling is needed: a 4 MHz capture of the
same run (SPI clock around 6 MHz) cannot be decoded.

```
sigrok-cli -i uc1611s_spi_demo.sr \
  -P display_spi:clk=SCK:mosi=MOSI:cs=CS:dc=DC:res=RST,uc1611s -A uc1611s
```

Every command byte, including the arguments (`0xC0 0x02`, `0xF1 0x7F`, `0x81 0x8F`), is its own CS window with CD low; the decoder
joins them into the commands. RAM data is one 240-byte CD-high transfer per page.
