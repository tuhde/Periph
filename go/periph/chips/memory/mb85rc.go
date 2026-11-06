package memory

import (
	"errors"

	"github.com/tuhde/Periph/go/periph/connection"
)

// MB85RC256V constants.
const (
	mb85rcSize         = 0x8000
	mb85rcDeviceIDBase = 0xA0
	mb85rcDefaultChunk = 30
)

// ErrMB85RCRange is returned when an address or range lies outside
// 0x0000–0x7FFF (the hardware would silently wrap around).
var ErrMB85RCRange = errors.New("mb85rc: address range outside 0-32767")

// MB85RCMinimal is the MB85RC256V 256 Kbit (32 KiB) I²C FRAM — minimal
// interface.
//
// FRAM is non-volatile like EEPROM but every write completes at bus speed:
// no write cycle, no page buffer, no ACK polling. The chip has no
// configuration registers. The connection must be a RegisterConnection with
// a 2-byte register width (connection.NewI2CConnectionWithWidth(bus, addr,
// 2, nil, nil)) at address 0x50–0x57.
type MB85RCMinimal struct {
	connection connection.RegisterConnection
}

// NewMB85RCMinimal creates a driver bound to conn. No bus traffic occurs.
func NewMB85RCMinimal(conn connection.RegisterConnection) (*MB85RCMinimal, error) {
	return &MB85RCMinimal{connection: conn}, nil
}

// ReadUserByte reads one byte (random read) at address 0–32767. Returns
// ErrMB85RCRange for an out-of-range address.
func (d *MB85RCMinimal) ReadUserByte(address int) (byte, error) {
	if address < 0 || address >= mb85rcSize {
		return 0, ErrMB85RCRange
	}
	buf, err := d.connection.ReadReg(uint32(address), 1)
	if err != nil {
		return 0, err
	}
	return buf[0], nil
}

// WriteUserByte writes one byte (byte write) at address 0–32767; it returns when
// the transfer completes (no polling needed). Returns ErrMB85RCRange for an
// out-of-range address.
func (d *MB85RCMinimal) WriteUserByte(address int, value byte) error {
	if address < 0 || address >= mb85rcSize {
		return ErrMB85RCRange
	}
	return d.connection.WriteReg(uint32(address), []byte{value})
}

// MB85RCFull is the MB85RC256V full interface. Extends MB85RCMinimal with
// chunked sequential read/write, fill, the array size and the Device ID.
type MB85RCFull struct {
	*MB85RCMinimal
	maxChunk int
}

// NewMB85RCFull creates a full driver bound to conn. MaxChunk defaults to 30.
func NewMB85RCFull(conn connection.RegisterConnection) (*MB85RCFull, error) {
	m, err := NewMB85RCMinimal(conn)
	if err != nil {
		return nil, err
	}
	return &MB85RCFull{MB85RCMinimal: m, maxChunk: mb85rcDefaultChunk}, nil
}

// MaxChunk returns the maximum data bytes per bus transaction (default 30;
// Arduino Wire limits transfers to 32 bytes including the 2 address bytes).
func (d *MB85RCFull) MaxChunk() int { return d.maxChunk }

// SetMaxChunk sets the maximum data bytes per bus transaction (must be ≥ 1).
func (d *MB85RCFull) SetMaxChunk(n int) error {
	if n < 1 {
		return errors.New("mb85rc: max chunk must be >= 1")
	}
	d.maxChunk = n
	return nil
}

// Size returns the array size in bytes (32768).
func (d *MB85RCFull) Size() int { return mb85rcSize }

func mb85rcCheckRange(address, length int) error {
	if address < 0 || length < 0 || address+length > mb85rcSize {
		return ErrMB85RCRange
	}
	return nil
}

// Read performs a sequential read of length bytes starting at address, split
// into transactions of at most MaxChunk bytes. The range must lie inside
// 0x0000–0x7FFF.
func (d *MB85RCFull) Read(address, length int) ([]byte, error) {
	if err := mb85rcCheckRange(address, length); err != nil {
		return nil, err
	}
	out := make([]byte, 0, length)
	for length > 0 {
		n := length
		if n > d.maxChunk {
			n = d.maxChunk
		}
		buf, err := d.connection.ReadReg(uint32(address), n)
		if err != nil {
			return nil, err
		}
		out = append(out, buf...)
		address += n
		length -= n
	}
	return out, nil
}

// Write performs a sequential write of data starting at address, split into
// transactions of at most MaxChunk bytes. The range must lie inside
// 0x0000–0x7FFF.
func (d *MB85RCFull) Write(address int, data []byte) error {
	if err := mb85rcCheckRange(address, len(data)); err != nil {
		return err
	}
	for off := 0; off < len(data); {
		n := len(data) - off
		if n > d.maxChunk {
			n = d.maxChunk
		}
		if err := d.connection.WriteReg(uint32(address+off), data[off:off+n]); err != nil {
			return err
		}
		off += n
	}
	return nil
}

// Fill writes value over length bytes starting at address (chunked).
func (d *MB85RCFull) Fill(address, length int, value byte) error {
	if err := mb85rcCheckRange(address, length); err != nil {
		return err
	}
	for length > 0 {
		n := length
		if n > d.maxChunk {
			n = d.maxChunk
		}
		buf := make([]byte, n)
		for i := range buf {
			buf[i] = value
		}
		if err := d.connection.WriteReg(uint32(address), buf); err != nil {
			return err
		}
		address += n
		length -= n
	}
	return nil
}

// ReadDeviceID reads the 3-byte Device ID through the reserved slave address
// 0x7C. idConn is a RegisterConnection (1-byte register width) bound to 0x7C
// and address is the chip's 7-bit address (0x50–0x57; use 0x50 by default),
// from which the device address word 0xA0 + (A2A1A0 × 2) is built. Returns
// (0x00A, 0x5, 0x10) for the MB85RC256V. Linux i2c-dev may refuse 0x7C.
func (d *MB85RCFull) ReadDeviceID(idConn connection.RegisterConnection, address uint8) (manufacturer, density, product uint16, err error) {
	word := uint32(mb85rcDeviceIDBase) + uint32(address&0x07)*2
	raw, err := idConn.ReadReg(word, 3)
	if err != nil {
		return 0, 0, 0, err
	}
	manufacturer = uint16(raw[0])<<4 | uint16(raw[1]>>4)
	density = uint16(raw[1] & 0x0F)
	product = uint16(raw[2])
	return manufacturer, density, product, nil
}
