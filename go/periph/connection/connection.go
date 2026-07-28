// Package connection provides the abstract I2C/SPI/... bus interface used by
// every chip driver in this repository, plus a Linux and TinyGo implementation
// of the connections that drive the underlying hardware, and the InputPin /
// OutputPin abstractions used for interrupt delivery and hardware enable
// control.
//
// Selection between the Linux and TinyGo implementation of a given connection
// happens at build time via Go build tags on the implementation file
// (//go:build linux for Linux, //go:build tinygo for TinyGo). Chip drivers
// and examples import this package and reference the Connection interface
// only; only an example's main() ever names a concrete connection type.
package connection

// Connection represents one device on a bus, plus its optional INT pin, EN
// pin, and software enable gate. Each instance is bound to a single device
// address. Implementations wrap a platform-specific bus (Linux: raw ioctl()
// against /dev/i2c-N; TinyGo: machine.I2C.Tx).
type Connection interface {
	// Write sends bytes to the device.
	Write(data []byte) error
	// Read reads n bytes from the device.
	Read(n int) ([]byte, error)
	// WriteRead writes data then reads n bytes without releasing the bus
	// between the two phases (repeated start on I2C). Used for register reads.
	WriteRead(data []byte, n int) ([]byte, error)
	// Close releases the bus. After Close, the connection must not be reused.
	Close() error

	// Enable resumes bus access, driving the EN pin high if wired.
	Enable()
	// Disable gates all bus access — reads return zeros, writes are dropped
	// — and drives the EN pin low if wired.
	Disable()
	// IsEnabled returns the current software-gate state.
	IsEnabled() bool

	// IntPin returns the InputPin wired to this device's INT line, or nil
	// if none was configured.
	IntPin() InputPin
	// EnPin returns the OutputPin wired to this device's EN line, or nil
	// if none was configured.
	EnPin() OutputPin
}

// connectionBase is embedded by every concrete Connection implementation to
// share enable/disable/pin state without repeating it in each one.
type connectionBase struct {
	intPin InputPin  // nil if unused
	enPin  OutputPin // nil if unused

	disabled bool // zero value = enabled
}

// Enable resumes bus access and drives EnPin high if wired.
func (b *connectionBase) Enable() {
	b.disabled = false
	if b.enPin != nil {
		_ = b.enPin.Set(true)
	}
}

// Disable gates all bus access and drives EnPin low if wired.
func (b *connectionBase) Disable() {
	b.disabled = true
	if b.enPin != nil {
		_ = b.enPin.Set(false)
	}
}

// IsEnabled returns the current software-gate state.
func (b *connectionBase) IsEnabled() bool { return !b.disabled }

// IntPin returns the InputPin wired to this device's INT line, or nil.
func (b *connectionBase) IntPin() InputPin { return b.intPin }

// EnPin returns the OutputPin wired to this device's EN line, or nil.
func (b *connectionBase) EnPin() OutputPin { return b.enPin }
