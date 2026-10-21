package memory

import (
	"errors"
	"fmt"

	"github.com/tuhde/Periph/go/periph/connection"
)

// 23X1024 instructions and sizes.
const (
	sram23x1024CmdRead  = 0x03
	sram23x1024CmdWrite = 0x02
	sram23x1024CmdRDMR  = 0x05
	sram23x1024CmdWRMR  = 0x01
	sram23x1024CmdRSTIO = 0xFF
	sram23x1024ModeMask = 0xC0

	// SRAM23X1024Size is the array size in bytes.
	SRAM23X1024Size = 131072

	sram23x1024PageSize      = 32
	sram23x1024MaxChunkLimit = 256
)

// 23X1024 operating modes (MODE register bits 7:6).
const (
	// SRAM23X1024ModeByte transfers exactly one data byte per READ/WRITE.
	SRAM23X1024ModeByte byte = 0x00
	// SRAM23X1024ModePage auto-increments within the 32-byte page and wraps to its start.
	SRAM23X1024ModePage byte = 0x80
	// SRAM23X1024ModeSequential auto-increments across the whole array (power-on default).
	SRAM23X1024ModeSequential byte = 0x40
)

// ErrSRAM23X1024Range is returned for an address or range outside the 128 KiB array.
var ErrSRAM23X1024Range = errors.New("23X1024: address range leaves the 128 KiB array")

// SRAM23X1024Minimal is the 23LC1024 / 23A1024 1-Mbit SPI serial SRAM —
// minimal interface: single-byte read/write.
//
// The chip is volatile but has unlimited cycles and zero write time: a
// write is complete when CS is released, with no polling. Every transaction
// is one instruction byte, a 24-bit big-endian address and data, with CS
// held low for the whole frame, so the driver takes the bare Connection and
// uses Write/WriteRead directly (the instruction is not a register address,
// so the read-bit logic of RegisterConnection does not apply).
type SRAM23X1024Minimal struct {
	connection connection.Connection
}

// NewSRAM23X1024Minimal creates the driver and runs the initialization
// sequence: it writes MODE=0x40 (Sequential) and verifies it with RDMR. A
// mismatch (floating/missing MISO, wrong SPI mode, chip stuck in SDI/SQI
// mode) returns an error.
func NewSRAM23X1024Minimal(t connection.Connection) (*SRAM23X1024Minimal, error) {
	d := &SRAM23X1024Minimal{connection: t}
	if err := d.writeMode(sram23x1024ModeSequentialValue); err != nil {
		return nil, err
	}
	mode, err := d.readMode()
	if err != nil {
		return nil, err
	}
	if mode != sram23x1024ModeSequentialValue {
		return nil, fmt.Errorf("23X1024: MODE register 0x%02X, expected 0x40 (check wiring and SPI Mode 0)", mode)
	}
	return d, nil
}

const sram23x1024ModeSequentialValue = 0x40

func (d *SRAM23X1024Minimal) readMode() (byte, error) {
	buf, err := d.connection.WriteRead([]byte{sram23x1024CmdRDMR}, 1)
	if err != nil {
		return 0, err
	}
	return buf[0], nil
}

func (d *SRAM23X1024Minimal) writeMode(mode byte) error {
	return d.connection.Write([]byte{sram23x1024CmdWRMR, mode})
}

func sram23x1024Header(cmd byte, address uint32) []byte {
	return []byte{cmd, byte(address >> 16), byte(address >> 8), byte(address)}
}

func (d *SRAM23X1024Minimal) readRaw(address uint32, n int) ([]byte, error) {
	return d.connection.WriteRead(sram23x1024Header(sram23x1024CmdRead, address), n)
}

func (d *SRAM23X1024Minimal) writeRaw(address uint32, data []byte) error {
	return d.connection.Write(append(sram23x1024Header(sram23x1024CmdWrite, address), data...))
}

// ReadByteAt reads one byte (READ, 0x03) from address 0–131071.
func (d *SRAM23X1024Minimal) ReadByteAt(address uint32) (byte, error) {
	if address >= SRAM23X1024Size {
		return 0, ErrSRAM23X1024Range
	}
	buf, err := d.readRaw(address, 1)
	if err != nil {
		return 0, err
	}
	return buf[0], nil
}

// WriteByteAt writes one byte (WRITE, 0x02) to address 0–131071; the write is
// complete when CS is released.
func (d *SRAM23X1024Minimal) WriteByteAt(address uint32, value byte) error {
	if address >= SRAM23X1024Size {
		return ErrSRAM23X1024Range
	}
	return d.writeRaw(address, []byte{value})
}

// SRAM23X1024Full is the 23X1024 full interface — extends
// SRAM23X1024Minimal with block access, operating modes and I/O reset.
//
// Read/Write/Fill have linear-address semantics in every mode: the driver
// splits transfers into transactions of at most MaxChunk data bytes and, in
// Page/Byte mode, at the 32-byte page / single-byte boundaries the hardware
// would otherwise wrap or truncate at. Ranges that would leave the array
// return ErrSRAM23X1024Range instead of silently wrapping around.
type SRAM23X1024Full struct {
	*SRAM23X1024Minimal
	mode     byte
	maxChunk int
}

// NewSRAM23X1024Full creates the driver and runs the initialization sequence.
func NewSRAM23X1024Full(t connection.Connection) (*SRAM23X1024Full, error) {
	m, err := NewSRAM23X1024Minimal(t)
	if err != nil {
		return nil, err
	}
	return &SRAM23X1024Full{SRAM23X1024Minimal: m, mode: SRAM23X1024ModeSequential, maxChunk: sram23x1024MaxChunkLimit}, nil
}

func (d *SRAM23X1024Full) chunkLen(address uint32, remaining int) int {
	n := remaining
	if n > d.maxChunk {
		n = d.maxChunk
	}
	switch d.mode {
	case SRAM23X1024ModeByte:
		return 1
	case SRAM23X1024ModePage:
		if toEnd := sram23x1024PageSize - int(address%sram23x1024PageSize); n > toEnd {
			n = toEnd
		}
	}
	return n
}

func sram23x1024CheckRange(address uint32, length int) error {
	if length < 0 || uint64(address)+uint64(length) > SRAM23X1024Size {
		return ErrSRAM23X1024Range
	}
	return nil
}

// Read reads length bytes starting at address; address+length must be <= 131072.
func (d *SRAM23X1024Full) Read(address uint32, length int) ([]byte, error) {
	if err := sram23x1024CheckRange(address, length); err != nil {
		return nil, err
	}
	out := make([]byte, 0, length)
	for length > 0 {
		n := d.chunkLen(address, length)
		part, err := d.readRaw(address, n)
		if err != nil {
			return nil, err
		}
		out = append(out, part...)
		address += uint32(n)
		length -= n
	}
	return out, nil
}

// Write writes data starting at address (same range rule as Read).
func (d *SRAM23X1024Full) Write(address uint32, data []byte) error {
	if err := sram23x1024CheckRange(address, len(data)); err != nil {
		return err
	}
	for len(data) > 0 {
		n := d.chunkLen(address, len(data))
		if err := d.writeRaw(address, data[:n]); err != nil {
			return err
		}
		address += uint32(n)
		data = data[n:]
	}
	return nil
}

// Fill writes value over length bytes starting at address (chunked).
func (d *SRAM23X1024Full) Fill(address uint32, length int, value byte) error {
	if err := sram23x1024CheckRange(address, length); err != nil {
		return err
	}
	block := make([]byte, sram23x1024PageSize)
	for i := range block {
		block[i] = value
	}
	for length > 0 {
		n := d.chunkLen(address, length)
		if n > len(block) {
			n = len(block)
		}
		if err := d.writeRaw(address, block[:n]); err != nil {
			return err
		}
		address += uint32(n)
		length -= n
	}
	return nil
}

// Size returns the array size in bytes (131072).
func (d *SRAM23X1024Full) Size() uint32 {
	return SRAM23X1024Size
}

// SetMode selects the operating mode (WRMR) and verifies it with RDMR.
// mode must be SRAM23X1024ModeByte, SRAM23X1024ModePage or
// SRAM23X1024ModeSequential; any other value returns an error.
func (d *SRAM23X1024Full) SetMode(mode byte) error {
	if mode != SRAM23X1024ModeByte && mode != SRAM23X1024ModePage && mode != SRAM23X1024ModeSequential {
		return fmt.Errorf("23X1024: invalid mode 0x%02X", mode)
	}
	if err := d.writeMode(mode); err != nil {
		return err
	}
	got, err := d.readMode()
	if err != nil {
		return err
	}
	if got != mode {
		return fmt.Errorf("23X1024: MODE register read-back 0x%02X, expected 0x%02X", got, mode)
	}
	d.mode = mode
	return nil
}

// GetMode reads the operating mode (RDMR bits 7:6) as one of the
// SRAM23X1024Mode* constants (0xC0 if the reserved value is read).
func (d *SRAM23X1024Full) GetMode() (byte, error) {
	m, err := d.readMode()
	if err != nil {
		return 0, err
	}
	return m & sram23x1024ModeMask, nil
}

// ResetIO sends RSTIO (0xFF) to return the chip to SPI mode after a stray
// EDIO/EQIO, then resynchronises the cached mode with the chip.
func (d *SRAM23X1024Full) ResetIO() error {
	if err := d.connection.Write([]byte{sram23x1024CmdRSTIO}); err != nil {
		return err
	}
	m, err := d.GetMode()
	if err != nil {
		return err
	}
	d.mode = m
	return nil
}

// MaxChunk returns the maximum data bytes per bus transaction (1–256, default 256).
func (d *SRAM23X1024Full) MaxChunk() int {
	return d.maxChunk
}

// SetMaxChunk sets the maximum data bytes per bus transaction; n must be 1–256.
func (d *SRAM23X1024Full) SetMaxChunk(n int) error {
	if n < 1 || n > sram23x1024MaxChunkLimit {
		return fmt.Errorf("23X1024: max chunk %d out of range 1-256", n)
	}
	d.maxChunk = n
	return nil
}
