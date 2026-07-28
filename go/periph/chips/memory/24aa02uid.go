// Package memory contains drivers for memory chips (EEPROM, Flash, FRAM, SRAM).
package memory

import (
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// 24AA02UID memory addresses and timing constants.
const (
	eeprom24aa02UIDBase        = 0xFC
	eeprom24aa02UIDMfrCode     = 0xFA
	eeprom24aa02UIDDevCode     = 0xFB
	eeprom24aa02UIDPageSize    = 8
	eeprom24aa02UIDWriteCycleMs = 5
)

// EEPROM24AA02UIDMinimal is the 2 Kbit EEPROM with 32-bit UID — minimal
// interface.
//
// Provides the chip's primary differentiating feature (the factory
// serial number) and single-byte user EEPROM read/write. User EEPROM
// spans 0x00–0x7F; addresses 0x80–0xFF are write-protected.
type EEPROM24AA02UIDMinimal struct {
	connection connection.Connection
}

// NewEEPROM24AA02UIDMinimal creates a new EEPROM24AA02UIDMinimal bound to
// the given connection. The chip has no configuration registers; no I²C
// traffic is performed at construction.
func NewEEPROM24AA02UIDMinimal(t connection.Connection) (*EEPROM24AA02UIDMinimal, error) {
	return &EEPROM24AA02UIDMinimal{connection: t}, nil
}

// ReadUID reads the chip's 32-bit factory-programmed serial number from
// 0xFC–0xFF, MSB first.
func (d *EEPROM24AA02UIDMinimal) ReadUID() ([4]byte, error) {
	buf, err := d.connection.WriteRead([]byte{eeprom24aa02UIDBase}, 4)
	if err != nil {
		return [4]byte{}, err
	}
	return [4]byte{buf[0], buf[1], buf[2], buf[3]}, nil
}

// ReadUserByte reads a single byte from user EEPROM at the given memory
// address (0x00–0x7F).
func (d *EEPROM24AA02UIDMinimal) ReadUserByte(address byte) (byte, error) {
	buf, err := d.connection.WriteRead([]byte{address}, 1)
	if err != nil {
		return 0, err
	}
	return buf[0], nil
}

// WriteUserByte writes a single byte to the given memory address and
// waits for the 5 ms internal write cycle to complete.
//
// Writes to 0x80–0xFF are accepted by the bus but silently ignored by
// the chip.
func (d *EEPROM24AA02UIDMinimal) WriteUserByte(address byte, value byte) error {
	if err := d.connection.Write([]byte{address, value}); err != nil {
		return err
	}
	time.Sleep(eeprom24aa02UIDWriteCycleMs * time.Millisecond)
	return nil
}

// EEPROM24AA02UIDFull is the 2 Kbit EEPROM with 32-bit UID — full
// interface. Extends EEPROM24AA02UIDMinimal with multi-byte read/write,
// page write, automatic page-crossing writes, and accessors for the
// manufacturer and device codes in the read-only upper block.
type EEPROM24AA02UIDFull struct {
	*EEPROM24AA02UIDMinimal
}

// NewEEPROM24AA02UIDFull creates a new EEPROM24AA02UIDFull bound to the
// given connection.
func NewEEPROM24AA02UIDFull(t connection.Connection) (*EEPROM24AA02UIDFull, error) {
	m, err := NewEEPROM24AA02UIDMinimal(t)
	if err != nil {
		return nil, err
	}
	return &EEPROM24AA02UIDFull{EEPROM24AA02UIDMinimal: m}, nil
}

// Read performs a sequential read of length bytes starting at address.
//
// The internal address pointer auto-increments; reads may cross any
// boundary and wrap at the end of the 256-byte address space.
func (d *EEPROM24AA02UIDFull) Read(address byte, length int) ([]byte, error) {
	if length == 0 {
		return []byte{}, nil
	}
	return d.connection.WriteRead([]byte{address}, length)
}

// WritePage writes up to 8 bytes within a single 8-byte page.
//
// The caller is responsible for ensuring all bytes lie within the same
// page. Bytes that would overflow the page boundary wrap to the start
// of the same page (FIFO overwrite) — use Write to handle page
// boundaries automatically.
func (d *EEPROM24AA02UIDFull) WritePage(address byte, data []byte) error {
	if len(data) == 0 {
		return nil
	}
	n := len(data)
	if n > eeprom24aa02UIDPageSize {
		n = eeprom24aa02UIDPageSize
	}
	buf := make([]byte, 1+n)
	buf[0] = address
	copy(buf[1:], data[:n])
	if err := d.connection.Write(buf); err != nil {
		return err
	}
	time.Sleep(eeprom24aa02UIDWriteCycleMs * time.Millisecond)
	return nil
}

// Write writes an arbitrary-length buffer, splitting at 8-byte page
// boundaries and waiting for the write cycle of each chunk before
// continuing.
func (d *EEPROM24AA02UIDFull) Write(address byte, data []byte) error {
	if len(data) == 0 {
		return nil
	}
	offset := 0
	remaining := len(data)
	addr := address
	for remaining > 0 {
		pageOffset := int(addr) & (eeprom24aa02UIDPageSize - 1)
		chunk := eeprom24aa02UIDPageSize - pageOffset
		if chunk > remaining {
			chunk = remaining
		}
		if err := d.WritePage(addr, data[offset:offset+chunk]); err != nil {
			return err
		}
		offset += chunk
		addr = byte(int(addr) + chunk)
		remaining -= chunk
	}
	return nil
}

// ReadManufacturerCode reads the manufacturer code at 0xFA; expected
// value is 0x29 (Microchip).
func (d *EEPROM24AA02UIDFull) ReadManufacturerCode() (byte, error) {
	return d.ReadUserByte(eeprom24aa02UIDMfrCode)
}

// ReadDeviceCode reads the device code at 0xFB; expected value is 0x41
// (I²C 2 Kbit EEPROM).
func (d *EEPROM24AA02UIDFull) ReadDeviceCode() (byte, error) {
	return d.ReadUserByte(eeprom24aa02UIDDevCode)
}
