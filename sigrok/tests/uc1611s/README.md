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
