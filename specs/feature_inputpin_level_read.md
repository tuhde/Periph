# Feature: InputPin level read

## Problem

`InputPin` (see `specs/feature_connection_design.md` §5) only delivers **edge notifications**
(`on_edge` / `off_edge`). Some chips expose a **level** signal that the driver must sample
at a moment of its own choosing, not react to: a BUSY output that is high while an
operation runs (SSD1683 e-paper controller: the ~14 s display refresh), a data-ready line
polled before a read, a power-good line checked once at start-up. With an edge-only
abstraction the driver has to register a handler *before* the busy-causing command, keep a
flag, and race against edges that may already have happened — and in C++ (handlers are
plain function pointers with no context) every driver instance needs a static trampoline
slot, as `VL53Base` does.

## Change

Add one **synchronous level read** to `InputPin` in every language. Nothing else about
`InputPin` changes: edge delivery, handler fan-out, `Connection.int_pin` and shared-pin
semantics are untouched.

| Language | Signature | Returns |
|----------|-----------|---------|
| Python | `InputPin.is_high(self) -> bool` | `True` when the line is at logic high |
| C++ | `virtual bool isHigh() = 0;` | `true` when high |
| Node.js | `isHigh(): boolean` | `true` when high (synchronous) |
| JVM | `boolean isHigh() throws IOException` (on `InputPin`) | `true` when high |
| Go | `IsHigh() (bool, error)` (on `connection.InputPin`) | level, or a read error |
| Rust | none — `embedded_hal::digital::InputPin::is_high()` already exists | |

"High" is the **electrical** level, not "asserted": active-low signals are interpreted by
the chip driver (as for `OutputPin.set(high)`).

## Implementations to update

Every in-tree `InputPin` implementation must implement the new method; there is no default
body (a silently wrong default is worse than a compile error).

| Language | Classes |
|----------|---------|
| Python | `MicroPythonPin` (`pin.value()`), `CircuitPythonPin` (`DigitalInOut.value`), `LinuxPollingPin`, `LinuxSysfsPin`, and every test mock that subclasses `InputPin` (a settable level) |
| C++ | `InputPinArduino` (`digitalRead`), `InputPinLinux` (`gpiod_line_request_get_value`), `InputPinZephyr` (`gpio_pin_get_raw`), `InputPinESPIDF` (`gpio_get_level`), `InputPinPicoSDK` (`gpio_get`), `InputPinSTM32Cube` (`HAL_GPIO_ReadPin`), plus mocks |
| Node.js | the epoll and polling implementations in `input_pin.js` (`readSync` / last polled value) |
| JVM | `SysfsInputPin` (read `value`), `PollingInputPin` (read the underlying pin) |
| Go | `GpioInputPin` (`input_pin_linux.go`: line value; `input_pin_tinygo.go`: `machine.Pin.Get()`), `PollingInputPin` |

Use the raw level (no inversion, no debounce). A level read must **not** disturb a
registered edge handler.

## Spec and AGENTS.md updates

- `specs/feature_connection_design.md` §5.1–§5.6: add the method to each language's
  `InputPin` listing and §10.1 feature-parity matrix.
- `AGENTS.md`: one sentence under the InputPin rules — drivers needing a level (BUSY,
  data-ready) call `is_high()`/`isHigh()`; edge handlers remain for INT lines.

## Tests

- Unit: each mock returns the level last set by the test; a driver test drives it low→high→low.
- Hardware-in-loop: none beyond the first consumer (`specs/display/ssd1683.md`).

## First consumer

`specs/display/ssd1683.md` (BUSY line). This feature must land **before** the SSD1683 driver
is implemented.
