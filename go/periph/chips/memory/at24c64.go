package memory

import (
	"errors"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// AT24C64 constants.
const (
	at24c64Size         = 0x2000
	at24c64Page         = 32
	at24c64DefaultChunk = 30
	at24c64WriteTimeout = 25 * time.Millisecond // tWR = 10 ms max (20 ms at 1.8 V), plus margin
)

// ErrAT24C64Range is returned when an address or range lies outside
// 0x0000–0x1FFF (the hardware would silently wrap around).
var ErrAT24C64Range = errors.New("at24c64: address range outside 0-8191")

// ErrAT24C64PageBoundary is returned by WritePage for data that would cross
// a 32-byte page boundary or exceed one page.
var ErrAT24C64PageBoundary = errors.New("at24c64: write crosses a page boundary")

// ErrAT24C64Timeout is returned when the chip does not acknowledge its
// address again within the write-cycle timeout.
var ErrAT24C64Timeout = errors.New("at24c64: write cycle did not complete")

// AT24C64Minimal is the AT24C64 64 Kbit (8 KiB) I²C EEPROM — minimal
// interface.
//
// Writes are self-timed (10 ms max (20 ms at 1.8 V)) and completed by ACK polling: after every
// write the driver polls the device address until the chip answers again, so a
// returned write is always committed. The chip has no configuration registers.
// The connection must be a RegisterConnection with a 2-byte register width
// (connection.NewI2CConnectionWithWidth(bus, addr, 2, nil, nil)) at address
// 0x50–0x57. WP high inhibits writes to the upper quadrant 0x1800–0x1FFF; the driver cannot
// detect it.
type AT24C64Minimal struct {
	connection connection.RegisterConnection
}

// NewAT24C64Minimal creates a driver bound to conn. No bus traffic occurs.
func NewAT24C64Minimal(conn connection.RegisterConnection) (*AT24C64Minimal, error) {
	return &AT24C64Minimal{connection: conn}, nil
}

// waitReady ACK-polls: an address-only write is acknowledged once the write
// cycle has ended.
func (d *AT24C64Minimal) waitReady(timeout time.Duration) bool {
	deadline := time.Now().Add(timeout)
	for {
		if err := d.connection.WriteReg(0, nil); err == nil {
			return true
		}
		if !time.Now().Before(deadline) {
			return false
		}
		time.Sleep(500 * time.Microsecond)
	}
}

func (d *AT24C64Minimal) commit() error {
	if !d.waitReady(at24c64WriteTimeout) {
		return ErrAT24C64Timeout
	}
	return nil
}

// ReadByteAt reads one byte (random read) at address 0–8191. Returns
// ErrAT24C64Range for an out-of-range address.
func (d *AT24C64Minimal) ReadByteAt(address int) (byte, error) {
	if address < 0 || address >= at24c64Size {
		return 0, ErrAT24C64Range
	}
	buf, err := d.connection.ReadReg(uint32(address), 1)
	if err != nil {
		return 0, err
	}
	return buf[0], nil
}

// WriteByteAt writes one byte at address 0–8191, then ACK-polls until the
// write cycle completes. Returns ErrAT24C64Range for an out-of-range address
// and ErrAT24C64Timeout if the chip does not answer again in time.
func (d *AT24C64Minimal) WriteByteAt(address int, value byte) error {
	if address < 0 || address >= at24c64Size {
		return ErrAT24C64Range
	}
	if err := d.connection.WriteReg(uint32(address), []byte{value}); err != nil {
		return err
	}
	return d.commit()
}

// AT24C64Full is the AT24C64 full interface. Extends AT24C64Minimal with
// sequential read, page-boundary-aware write, page write, fill, the array and
// page size and explicit ACK polling. Writes never cross a 32-byte page
// boundary and every chunk is ACK-polled.
type AT24C64Full struct {
	*AT24C64Minimal
	maxChunk int
}

// NewAT24C64Full creates a full driver bound to conn. MaxChunk defaults to 30.
func NewAT24C64Full(conn connection.RegisterConnection) (*AT24C64Full, error) {
	m, err := NewAT24C64Minimal(conn)
	if err != nil {
		return nil, err
	}
	return &AT24C64Full{AT24C64Minimal: m, maxChunk: at24c64DefaultChunk}, nil
}

// MaxChunk returns the maximum data bytes per bus transaction (default 30;
// Arduino Wire limits transfers to 32 bytes including the 2 address bytes).
func (d *AT24C64Full) MaxChunk() int { return d.maxChunk }

// SetMaxChunk sets the maximum data bytes per bus transaction (must be ≥ 1).
func (d *AT24C64Full) SetMaxChunk(n int) error {
	if n < 1 {
		return errors.New("at24c64: max chunk must be >= 1")
	}
	d.maxChunk = n
	return nil
}

// Size returns the array size in bytes (8192).
func (d *AT24C64Full) Size() int { return at24c64Size }

// PageSize returns the write page size in bytes (32).
func (d *AT24C64Full) PageSize() int { return at24c64Page }

// WaitReady ACK-polls until the chip answers its device address again and
// reports whether it did within timeout.
func (d *AT24C64Full) WaitReady(timeout time.Duration) bool { return d.waitReady(timeout) }

func at24c64CheckRange(address, length int) error {
	if address < 0 || length < 0 || address+length > at24c64Size {
		return ErrAT24C64Range
	}
	return nil
}

// chunkLen returns the next chunk length at address: bounded by the page end,
// MaxChunk and the remaining length.
func (d *AT24C64Full) chunkLen(address, remaining int) int {
	n := at24c64Page - address%at24c64Page
	if n > d.maxChunk {
		n = d.maxChunk
	}
	if n > remaining {
		n = remaining
	}
	return n
}

// Read performs a sequential read of length bytes starting at address, split
// into transactions of at most MaxChunk bytes. The range must lie inside
// 0x0000–0x1FFF.
func (d *AT24C64Full) Read(address, length int) ([]byte, error) {
	if err := at24c64CheckRange(address, length); err != nil {
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

// Write writes data starting at address, split at 32-byte page boundaries and
// MaxChunk; the write cycle is ACK-polled after every chunk. The range must
// lie inside 0x0000–0x1FFF.
func (d *AT24C64Full) Write(address int, data []byte) error {
	if err := at24c64CheckRange(address, len(data)); err != nil {
		return err
	}
	for off := 0; off < len(data); {
		n := d.chunkLen(address+off, len(data)-off)
		if err := d.connection.WriteReg(uint32(address+off), data[off:off+n]); err != nil {
			return err
		}
		if err := d.commit(); err != nil {
			return err
		}
		off += n
	}
	return nil
}

// WritePage writes data that lies inside one 32-byte page (sent in MaxChunk
// pieces), ACK-polled. Returns ErrAT24C64PageBoundary if the data crosses a
// page boundary or is longer than a page.
func (d *AT24C64Full) WritePage(address int, data []byte) error {
	if err := at24c64CheckRange(address, len(data)); err != nil {
		return err
	}
	if len(data) > at24c64Page || address%at24c64Page+len(data) > at24c64Page {
		return ErrAT24C64PageBoundary
	}
	return d.Write(address, data)
}

// Fill writes value over length bytes starting at address, page by page.
func (d *AT24C64Full) Fill(address, length int, value byte) error {
	if err := at24c64CheckRange(address, length); err != nil {
		return err
	}
	for done := 0; done < length; {
		n := d.chunkLen(address+done, length-done)
		buf := make([]byte, n)
		for i := range buf {
			buf[i] = value
		}
		if err := d.connection.WriteReg(uint32(address+done), buf); err != nil {
			return err
		}
		if err := d.commit(); err != nil {
			return err
		}
		done += n
	}
	return nil
}
