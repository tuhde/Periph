//go:build tinygo

// I2CConnection is the TinyGo implementation of the Connection interface,
// backed by a configured machine.I2C value the caller passes in.
package connection

import "machine"

// I2CConnection is a TinyGo machine.I2C-backed implementation of
// Connection. The address is fixed at construction — one I2CConnection
// instance represents one device on the bus.
type I2CConnection struct {
	connectionBase
	i2c      *machine.I2C
	addr     uint16
	regBytes uint8
}

// NewI2CConnection binds the given configured machine.I2C to the given
// 7-bit device address. The caller is responsible for calling
// machine.I2C.Configure(...) on i2c before passing it in. intPin and enPin
// may be nil if the device's INT/EN lines are not wired.
//
// Uses a 1-byte register address. Use NewI2CConnectionWithWidth for chips
// needing a wider address (e.g. ADE7953, VL53L1X).
func NewI2CConnection(i2c *machine.I2C, addr uint8, intPin InputPin, enPin OutputPin) *I2CConnection {
	return NewI2CConnectionWithWidth(i2c, addr, 1, intPin, enPin)
}

// NewI2CConnectionWithWidth is like NewI2CConnection but additionally takes
// the register address width in bytes, big-endian (1-4).
func NewI2CConnectionWithWidth(i2c *machine.I2C, addr uint8, regBytes uint8, intPin InputPin, enPin OutputPin) *I2CConnection {
	return &I2CConnection{
		connectionBase: connectionBase{intPin: intPin, enPin: enPin},
		i2c:             i2c,
		addr:            uint16(addr),
		regBytes:        regBytes,
	}
}

// Close is a no-op on TinyGo: machine.I2C has no explicit release.
// Provided so I2CConnection satisfies the Connection interface and so the
// same example code can call connection.Close() unconditionally.
func (t *I2CConnection) Close() error {
	return nil
}

// Write sends bytes to the device.
func (t *I2CConnection) Write(data []byte) error {
	if !t.IsEnabled() {
		return nil
	}
	return t.i2c.Tx(t.addr, data, nil)
}

// Read reads n bytes from the device.
func (t *I2CConnection) Read(n int) ([]byte, error) {
	if !t.IsEnabled() {
		return make([]byte, n), nil
	}
	buf := make([]byte, n)
	if err := t.i2c.Tx(t.addr, nil, buf); err != nil {
		return nil, err
	}
	return buf, nil
}

// WriteRead writes data then reads n bytes in a single combined machine.I2C.Tx
// call, which already produces a repeated START between the two phases.
func (t *I2CConnection) WriteRead(writeData []byte, n int) ([]byte, error) {
	if !t.IsEnabled() {
		return make([]byte, n), nil
	}
	readBuf := make([]byte, n)
	if err := t.i2c.Tx(t.addr, writeData, readBuf); err != nil {
		return nil, err
	}
	return readBuf, nil
}

// ReadReg reads length bytes starting at register reg.
func (t *I2CConnection) ReadReg(reg uint32, length int) ([]byte, error) {
	return t.WriteRead(regAddrBytes(reg, t.regBytes), length)
}

// WriteReg writes data to register reg.
func (t *I2CConnection) WriteReg(reg uint32, data []byte) error {
	return t.Write(append(regAddrBytes(reg, t.regBytes), data...))
}
