// Package transport provides the abstract I2C/SPI/... bus interface used by
// every chip driver in this repository, plus a Linux and TinyGo implementation
// of the transports that drive the underlying hardware.
//
// Selection between the Linux and TinyGo implementation of a given transport
// happens at build time via Go build tags on the implementation file
// (//go:build linux for Linux, //go:build tinygo for TinyGo). Chip drivers
// and examples import this package and reference the Transport interface
// only; only an example's main() ever names a concrete transport type.
package transport

// Transport represents one device on a bus. Each instance is bound to a
// single device address. Implementations wrap a platform-specific bus
// (Linux: raw ioctl() against /dev/i2c-N; TinyGo: machine.I2C.Tx).
type Transport interface {
	// Write sends bytes to the device.
	Write(data []byte) error
	// Read reads n bytes from the device.
	Read(n int) ([]byte, error)
	// WriteRead writes data then reads n bytes without releasing the bus
	// between the two phases (repeated start on I2C). Used for register reads.
	WriteRead(data []byte, n int) ([]byte, error)
	// Close releases the bus. After Close, the transport must not be reused.
	Close() error
}
