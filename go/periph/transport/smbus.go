// Package transport: SMBusTransport wraps a generic Transport (typically
// an I2CTransport) and adds 7-bit address validation plus software PEC
// (Packet Error Checking). It is platform-agnostic: it depends only on
// the Transport interface, not on a concrete I2C type, so it lives in
// a single file with no build tags — unlike I2C/SPI which are split
// per platform.
package transport

import "fmt"

// SMBusTransport is an I²C transport with SMBus-style address validation
// and optional software PEC. It wraps any Transport implementation.
type SMBusTransport struct {
	t   Transport
	pec bool
}

// NewSMBusTransport wraps the given Transport and returns an SMBusTransport.
// Returns an error immediately if addr falls in the reserved 0x00–0x07 /
// 0x78–0x7F range.
func NewSMBusTransport(t Transport, addr uint8, pec bool) (*SMBusTransport, error) {
	if err := validateSMBusAddr(addr); err != nil {
		return nil, err
	}
	return &SMBusTransport{t: t, pec: pec}, nil
}

// validateSMBusAddr returns an error if addr is in the SMBus reserved range.
func validateSMBusAddr(addr uint8) error {
	if addr <= 0x07 || addr >= 0x78 {
		return fmt.Errorf("smbus: reserved address 0x%02X", addr)
	}
	return nil
}

// Close releases the underlying transport.
func (s *SMBusTransport) Close() error {
	return s.t.Close()
}

// Write sends bytes to the device. If PEC is enabled, the CRC-8 byte
// is appended automatically.
func (s *SMBusTransport) Write(data []byte) error {
	if s.pec {
		data = append(data, pec8(pecPrefixWrite, data))
	}
	return s.t.Write(data)
}

// Read reads n bytes from the device. If PEC is enabled, the last byte
// is the PEC and is verified against bytes 0..n-2.
func (s *SMBusTransport) Read(n int) ([]byte, error) {
	if !s.pec {
		return s.t.Read(n)
	}
	// n includes the PEC byte (callers pass the data length, transport
	// adds the PEC overhead). Convention: caller passes n data bytes,
	// we read n+1 bytes (data + PEC).
	buf, err := s.t.Read(n + 1)
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
func (s *SMBusTransport) WriteRead(data []byte, n int) ([]byte, error) {
	if !s.pec {
		return s.t.WriteRead(data, n)
	}
	tx := append([]byte{}, data...)
	tx = append(tx, pec8(pecPrefixWrite, data))
	resp, err := s.t.WriteRead(tx, n+1)
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
	pecPrefixRead  = 0x01 // (addr << 1) | 1, but address is bound to transport
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
