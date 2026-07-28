// Package periph is the Go implementation of the Periph multi-language
// peripheral-chip library. It provides drivers for I²C, SPI, UART, and
// other peripheral devices, plus a Connection interface and a per-connection
// implementation for each supported build target.
//
// # Build targets
//
// Two build targets are supported, selected at compile time via Go build
// tags on the connection implementation files:
//
//   - linux && !tinygo — standard `go build` against Linux host (no cgo,
//     uses golang.org/x/sys/unix for raw ioctl against /dev/i2c-N).
//   - tinygo — `tinygo build -target=<board>` against a bare-metal
//     microcontroller via the `machine` package. Hardware-in-loop tests
//     are pinned to a Raspberry Pi Pico W (-target=pico-w).
//
// # Architecture
//
// Every chip driver is a single Go file under periph/chips/<category>/,
// shared by both build targets. Chip drivers only call the Connection
// interface defined in this package's connection subpackage; the platform
// is selected by the build tag on the concrete I2CConnection / SPIConnection
// implementation file.
//
// # Naming
//
// Chip drivers follow Go's title-case convention with initialisms kept
// upper-case: AHT21Minimal / AHT21Full, not Aht21Minimal. The Minimal
// class is the primary use case; Full extends it via struct embedding
// (`type <Chip>Full struct { <Chip>Minimal }`) and adds the rest.
package periph
