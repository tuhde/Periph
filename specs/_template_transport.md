# Transport Spec: <TransportName>

**Protocol:** <e.g. SPI, I²C, UART>  
**Reference:** <standard or datasheet section>

## Overview

<!-- What this transport is and when chip drivers should use it. -->

## Interface Contract

All transport implementations must provide these operations:

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `init` | config | — | Configure and open the bus |
| `write` | bytes | — | Send bytes to the device |
| `read` | length | bytes | Receive N bytes from the device |
| `write_read` | bytes, length | bytes | Write then read (repeated start / CS held) |
| `close` | — | — | Release the bus |

## Configuration Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `clock_hz` | int | | Bus clock frequency |
| ... | | | |

## Error Handling

<!-- What errors implementations must raise/return and under what conditions. -->

## Platform Notes

### MicroPython

<!-- MicroPython-specific implementation notes. -->

### Arduino

<!-- Arduino-specific implementation notes. -->
