// Package connection: SMBusConnection wraps a generic Connection (typically
// an I2CConnection) and adds 7-bit address validation plus software PEC
// (Packet Error Checking). It is platform-agnostic: it depends only on
// the Connection interface, not on a concrete I2C type, so it lives in
// a single file with no build tags — unlike I2C/SPI which are split
// per platform.
package connection

import "fmt"

// SMBusConnection is an I²C connection with SMBus-style address validation
// and optional software PEC. It wraps any Connection implementation,
// delegating Enable/Disable/IsEnabled/IntPin/EnPin to the wrapped
// connection rather than tracking its own — there is exactly one
// software-gate and one pair of pins per physical device.
type SMBusConnection struct {
	c   Connection
	pec bool
}

// NewSMBusConnection wraps the given Connection and returns an
// SMBusConnection. Returns an error immediately if addr falls in the
// reserved 0x00–0x07 / 0x78–0x7F range.
func NewSMBusConnection(c Connection, addr uint8, pec bool) (*SMBusConnection, error) {
	if err := validateSMBusAddr(addr); err != nil {
		return nil, err
	}
	return &SMBusConnection{c: c, pec: pec}, nil
}

// Enable delegates to the wrapped Connection.
func (s *SMBusConnection) Enable() { s.c.Enable() }

// Disable delegates to the wrapped Connection.
func (s *SMBusConnection) Disable() { s.c.Disable() }

// IsEnabled delegates to the wrapped Connection.
func (s *SMBusConnection) IsEnabled() bool { return s.c.IsEnabled() }

// IntPin delegates to the wrapped Connection.
func (s *SMBusConnection) IntPin() InputPin { return s.c.IntPin() }

// EnPin delegates to the wrapped Connection.
func (s *SMBusConnection) EnPin() OutputPin { return s.c.EnPin() }

// validateSMBusAddr returns an error if addr is in the SMBus reserved range.
func validateSMBusAddr(addr uint8) error {
	if addr <= 0x07 || addr >= 0x78 {
		return fmt.Errorf("smbus: reserved address 0x%02X", addr)
	}
	return nil
}

// Close releases the underlying connection.
func (s *SMBusConnection) Close() error {
	return s.c.Close()
}

// Write sends bytes to the device. If PEC is enabled, the CRC-8 byte
// is appended automatically.
func (s *SMBusConnection) Write(data []byte) error {
	if s.pec {
		data = append(data, pec8(pecPrefixWrite, data))
	}
	return s.c.Write(data)
}

// Read reads n bytes from the device. If PEC is enabled, the last byte
// is the PEC and is verified against bytes 0..n-2.
func (s *SMBusConnection) Read(n int) ([]byte, error) {
	if !s.pec {
		return s.c.Read(n)
	}
	// n includes the PEC byte (callers pass the data length, the
	// connection adds the PEC overhead). Convention: caller passes n
	// data bytes, we read n+1 bytes (data + PEC).
	buf, err := s.c.Read(n + 1)
	if err != nil {
		return nil, err
	}
	expected := pec8(pecPrefixRead, buf[:n])
	if buf[n] != expected {
		return nil, fmt.Errorf("smbus: PEC error (got 0x%02X, expected 0x%02X)", buf[n], expected)
	}
	return buf[:n], nil
}

// WriteRead writes data then reads n bytes in a single transaction. If
// PEC is enabled, the PEC byte is appended to the write phase and
// verified against the last received byte.
func (s *SMBusConnection) WriteRead(data []byte, n int) ([]byte, error) {
	if !s.pec {
		return s.c.WriteRead(data, n)
	}
	tx := append([]byte{}, data...)
	tx = append(tx, pec8(pecPrefixWrite, data))
	resp, err := s.c.WriteRead(tx, n+1)
	if err != nil {
		return nil, err
	}
	expected := pec8(pecPrefixRead, resp[:n])
	if resp[n] != expected {
		return nil, fmt.Errorf("smbus: PEC error (got 0x%02X, expected 0x%02X)", resp[n], expected)
	}
	return resp[:n], nil
}

// PEC byte prefixes per SMBus 3.2 §6.4.1.
const (
	pecPrefixWrite = 0x00 // (addr << 1) | 0
	pecPrefixRead  = 0x01 // (addr << 1) | 1, but address is bound to connection
)

// pec8 computes the SMBus CRC-8 (poly 0x07, init 0x00) over a sequence
// of frames. Each frame is (prefix, data...). The final CRC is the
// PEC byte to append (write) or to verify (read).
func pec8(prefix byte, data []byte) byte {
	crc := byte(0x00)
	process := func(b byte) {
		crc ^= b
		for i := 0; i < 8; i++ {
			if crc&0x80 != 0 {
				crc = (crc << 1) ^ 0x07
			} else {
				crc <<= 1
			}
		}
	}
	process(prefix)
	for _, b := range data {
		process(b)
	}
	return crc
}
