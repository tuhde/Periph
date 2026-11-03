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
	spi          *machine.SPI
	cs           machine.Pin
	readBit      byte
	multiByteBit byte // 0 = chip has no such bit
	held         bool // Select() active: CS low across calls
	cfg          *machine.SPIConfig
}

// NewSPIConnection binds the given configured machine.SPI to the given
// CS pin. The caller is responsible for calling machine.SPI.Configure(...)
// on spi before passing it in. intPin and enPin may be nil if the
// device's INT/EN lines are not wired.
//
// Uses the default register-addressing convention (readBit 0x80, no
// multi-byte bit). Use NewSPIConnectionWithConvention for chips needing a
// different convention (e.g. ADXL345-style, with an explicit multi-byte bit).
func NewSPIConnection(spi *machine.SPI, cs machine.Pin, intPin InputPin, enPin OutputPin) *SPIConnection {
	return NewSPIConnectionWithConvention(spi, cs, 0x80, 0, intPin, enPin)
}

// NewSPIConnectionWithConvention is like NewSPIConnection but additionally
// takes the chip's register-addressing convention: readBit is ORed into the
// command byte for a read (0 if the chip has no such bit), multiByteBit is
// ORed in for multi-byte (burst) transfers when length > 1 (0 if the chip
// has no such bit and always auto-increments).
func NewSPIConnectionWithConvention(spi *machine.SPI, cs machine.Pin, readBit, multiByteBit byte,
	intPin InputPin, enPin OutputPin) *SPIConnection {
	cs.Configure(machine.PinConfig{Mode: machine.PinOutput})
	cs.High()
	return &SPIConnection{
		connectionBase: connectionBase{intPin: intPin, enPin: enPin},
		spi:             spi,
		cs:              cs,
		readBit:         readBit,
		multiByteBit:    multiByteBit,
	}
}

// EnableSpeedChange allows SetSpeed: cfg is the machine.SPIConfig the SPI
// was configured with; SetSpeed re-runs Configure with Frequency replaced.
func (t *SPIConnection) EnableSpeedChange(cfg machine.SPIConfig) {
	t.cfg = &cfg
}

// SetSpeed re-configures the SPI at hz. It needs EnableSpeedChange;
// otherwise it returns ErrSPIExtensionUnsupported.
func (t *SPIConnection) SetSpeed(hz uint32) error {
	if t.cfg == nil {
		return ErrSPIExtensionUnsupported
	}
	c := *t.cfg
	c.Frequency = hz
	return t.spi.Configure(c)
}

// IdleClocks clocks n bits of 0xFF with CS high.
func (t *SPIConnection) IdleClocks(n int) error {
	t.cs.High()
	tx := make([]byte, (n+7)/8)
	for i := range tx {
		tx[i] = 0xFF
	}
	err := t.spi.Tx(tx, nil)
	if t.held {
		t.cs.Low()
	}
	return err
}

// Select asserts CS and holds it low across following calls until Deselect.
func (t *SPIConnection) Select() error {
	t.cs.Low()
	t.held = true
	return nil
}

// Deselect releases CS asserted by Select.
func (t *SPIConnection) Deselect() error {
	t.held = false
	t.cs.High()
	return nil
}

// ReadInto fills buf with len(buf) bytes read from the device, clocking 0xFF on MOSI.
func (t *SPIConnection) ReadInto(buf []byte) error {
	if !t.IsEnabled() {
		for i := range buf {
			buf[i] = 0
		}
		return nil
	}
	if len(buf) == 0 {
		return nil
	}
	tx := make([]byte, len(buf))
	for i := range tx {
		tx[i] = 0xFF
	}
	if !t.held {
		t.cs.Low()
	}
	err := t.spi.Tx(tx, buf)
	if !t.held {
		t.cs.High()
	}
	return err
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
	if !t.held {
		t.cs.Low()
	}
	err := t.spi.Tx(data, nil)
	if !t.held {
		t.cs.High()
	}
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
	if err := t.ReadInto(buf); err != nil {
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
	for i := len(data); i < len(tx); i++ {
		tx[i] = 0xFF // MOSI idles high during the read phase
	}
	copy(tx, data)
	// Build a single combined RX buffer of len(data)+n; discard the
	// first len(data) bytes (response during the command phase).
	rx := make([]byte, len(data)+n)
	if !t.held {
		t.cs.Low()
	}
	err := t.spi.Tx(tx, rx)
	if !t.held {
		t.cs.High()
	}
	if err != nil {
		return nil, err
	}
	return rx[len(data):], nil
}

// ReadReg reads length bytes starting at register reg, building the SPI
// command byte from the connection's configured convention.
func (t *SPIConnection) ReadReg(reg uint32, length int) ([]byte, error) {
	cmd := byte(reg) | t.readBit
	if length > 1 && t.multiByteBit != 0 {
		cmd |= t.multiByteBit
	}
	return t.WriteRead([]byte{cmd}, length)
}

// WriteReg writes data to register reg, building the SPI command byte from
// the connection's configured convention.
func (t *SPIConnection) WriteReg(reg uint32, data []byte) error {
	cmd := byte(reg) &^ t.readBit
	if len(data) > 1 && t.multiByteBit != 0 {
		cmd |= t.multiByteBit
	}
	return t.Write(append([]byte{cmd}, data...))
}
