//go:build tinygo

// I2CTransport is the TinyGo implementation of the Transport interface,
// backed by a configured machine.I2C value the caller passes in.
package transport

import "machine"

// I2CTransport is a TinyGo machine.I2C-backed implementation of Transport.
// The address is fixed at construction — one I2CTransport instance
// represents one device on the bus.
type I2CTransport struct {
	i2c  *machine.I2C
	addr uint16
}

// NewI2CTransport binds the given configured machine.I2C to the given 7-bit
// device address. The caller is responsible for calling
// machine.I2C.Configure(...) on i2c before passing it in.
func NewI2CTransport(i2c *machine.I2C, addr uint8) *I2CTransport {
	return &I2CTransport{i2c: i2c, addr: uint16(addr)}
}

// Close is a no-op on TinyGo: machine.I2C has no explicit release.
// Provided so I2CTransport satisfies the Transport interface and so the
// same example code can call transport.Close() unconditionally.
func (t *I2CTransport) Close() error {
	return nil
}

// Write sends bytes to the device.
func (t *I2CTransport) Write(data []byte) error {
	return t.i2c.Tx(t.addr, data, nil)
}

// Read reads n bytes from the device.
func (t *I2CTransport) Read(n int) ([]byte, error) {
	buf := make([]byte, n)
	if err := t.i2c.Tx(t.addr, nil, buf); err != nil {
		return nil, err
	}
	return buf, nil
}

// WriteRead writes data then reads n bytes in a single combined machine.I2C.Tx
// call, which already produces a repeated START between the two phases.
func (t *I2CTransport) WriteRead(writeData []byte, n int) ([]byte, error) {
	readBuf := make([]byte, n)
	if err := t.i2c.Tx(t.addr, writeData, readBuf); err != nil {
		return nil, err
	}
	return readBuf, nil
}
