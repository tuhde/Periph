//go:build tinygo

// SPIConnection is the TinyGo implementation of the Connection interface
// for SPI, backed by a configured machine.SPI value the caller passes
// in. CS is a plain machine.Pin the connection drives itself around
// each call.
package connection

import "machine"

// SPIConnection is a TinyGo machine.SPI-backed implementation of
// Connection. CS is a separate machine.Pin the caller provides.
type SPIConnection struct {
	connectionBase
	spi *machine.SPI
	cs  machine.Pin
}

// NewSPIConnection binds the given configured machine.SPI to the given
// CS pin. The caller is responsible for calling machine.SPI.Configure(...)
// on spi before passing it in. intPin and enPin may be nil if the
// device's INT/EN lines are not wired.
func NewSPIConnection(spi *machine.SPI, cs machine.Pin, intPin InputPin, enPin OutputPin) *SPIConnection {
	cs.Configure(machine.PinConfig{Mode: machine.PinOutput})
	cs.High()
	return &SPIConnection{
		connectionBase: connectionBase{intPin: intPin, enPin: enPin},
		spi:             spi,
		cs:              cs,
	}
}

// Close is a no-op on TinyGo: machine.SPI has no explicit release.
func (t *SPIConnection) Close() error {
	return nil
}

// Write sends bytes to the device.
func (t *SPIConnection) Write(data []byte) error {
	if !t.IsEnabled() {
		return nil
	}
	t.cs.Low()
	err := t.spi.Tx(data, nil)
	t.cs.High()
	return err
}

// Read clocks out n bytes from the device, sending dummy 0x00 bytes
// on the MOSI line.
func (t *SPIConnection) Read(n int) ([]byte, error) {
	if !t.IsEnabled() {
		return make([]byte, n), nil
	}
	if n == 0 {
		return []byte{}, nil
	}
	buf := make([]byte, n)
	t.cs.Low()
	err := t.spi.Tx(nil, buf)
	t.cs.High()
	if err != nil {
		return nil, err
	}
	return buf, nil
}

// WriteRead sends data then reads n bytes with CS held low for the
// whole transfer (the spec calls for repeated-start-style non-release
// for register reads). TinyGo's machine.SPI.Tx can do both phases in
// one call when given both a tx buffer long enough for the data and
// a separate rx buffer of length n.
func (t *SPIConnection) WriteRead(data []byte, n int) ([]byte, error) {
	if !t.IsEnabled() {
		return make([]byte, n), nil
	}
	if n == 0 {
		return nil, nil
	}
	tx := make([]byte, len(data)+n)
	copy(tx, data)
	// Build a single combined RX buffer of len(data)+n; discard the
	// first len(data) bytes (response during the command phase).
	rx := make([]byte, len(data)+n)
	t.cs.Low()
	err := t.spi.Tx(tx, rx)
	t.cs.High()
	if err != nil {
		return nil, err
	}
	return rx[len(data):], nil
}
