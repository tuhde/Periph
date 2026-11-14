package memory

import (
	"errors"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// CAT24C512 constants.
const (
	cat24c512Size         = 0x10000
	cat24c512Page         = 128
	cat24c512DefaultChunk = 30
	cat24c512WriteTimeout = 10 * time.Millisecond // tWR = 5 ms max, plus margin
)

// ErrCAT24C512Range is returned when an address or range lies outside
// 0x0000–0xFFFF (the hardware would silently wrap around).
var ErrCAT24C512Range = errors.New("cat24c512: address range outside 0-65535")

// ErrCAT24C512PageBoundary is returned by WritePage for data that would cross
// a 128-byte page boundary or exceed one page.
var ErrCAT24C512PageBoundary = errors.New("cat24c512: write crosses a page boundary")

// ErrCAT24C512Timeout is returned when the chip does not acknowledge its
// address again within the write-cycle timeout.
var ErrCAT24C512Timeout = errors.New("cat24c512: write cycle did not complete")

// CAT24C512Minimal is the CAT24C512 512 Kbit (64 KiB) I²C EEPROM — minimal
// interface.
//
// Writes are self-timed (5 ms max) and completed by ACK polling: after every
// write the driver polls the device address until the chip answers again, so a
// returned write is always committed. The chip has no configuration registers.
// The connection must be a RegisterConnection with a 2-byte register width
// (connection.NewI2CConnectionWithWidth(bus, addr, 2, nil, nil)) at address
// 0x50–0x57. WP high inhibits writes to the whole array; the driver cannot
// detect it.
type CAT24C512Minimal struct {
	connection connection.RegisterConnection
}

// NewCAT24C512Minimal creates a driver bound to conn. No bus traffic occurs.
func NewCAT24C512Minimal(conn connection.RegisterConnection) (*CAT24C512Minimal, error) {
	return &CAT24C512Minimal{connection: conn}, nil
}

// waitReady ACK-polls: an address-only write is acknowledged once the write
// cycle has ended.
func (d *CAT24C512Minimal) waitReady(timeout time.Duration) bool {
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

func (d *CAT24C512Minimal) commit() error {
	if !d.waitReady(cat24c512WriteTimeout) {
		return ErrCAT24C512Timeout
	}
	return nil
}

// ReadByteAt reads one byte (random read) at address 0–65535. Returns
// ErrCAT24C512Range for an out-of-range address.
func (d *CAT24C512Minimal) ReadByteAt(address int) (byte, error) {
	if address < 0 || address >= cat24c512Size {
		return 0, ErrCAT24C512Range
	}
	buf, err := d.connection.ReadReg(uint32(address), 1)
	if err != nil {
		return 0, err
	}
	return buf[0], nil
}

// WriteByteAt writes one byte at address 0–65535, then ACK-polls until the
// write cycle completes. Returns ErrCAT24C512Range for an out-of-range address
// and ErrCAT24C512Timeout if the chip does not answer again in time.
func (d *CAT24C512Minimal) WriteByteAt(address int, value byte) error {
	if address < 0 || address >= cat24c512Size {
		return ErrCAT24C512Range
	}
	if err := d.connection.WriteReg(uint32(address), []byte{value}); err != nil {
		return err
	}
	return d.commit()
}

// CAT24C512Full is the CAT24C512 full interface. Extends CAT24C512Minimal with
// sequential read, page-boundary-aware write, page write, fill, the array and
// page size and explicit ACK polling. Writes never cross a 128-byte page
// boundary and every chunk is ACK-polled. For maximum endurance (on-chip ECC
// re-programs 4-byte groups) write 4-byte-aligned multiples of 4 bytes.
type CAT24C512Full struct {
	*CAT24C512Minimal
	maxChunk int
}

// NewCAT24C512Full creates a full driver bound to conn. MaxChunk defaults to 30.
func NewCAT24C512Full(conn connection.RegisterConnection) (*CAT24C512Full, error) {
	m, err := NewCAT24C512Minimal(conn)
	if err != nil {
		return nil, err
	}
	return &CAT24C512Full{CAT24C512Minimal: m, maxChunk: cat24c512DefaultChunk}, nil
}

// MaxChunk returns the maximum data bytes per bus transaction (default 30;
// Arduino Wire limits transfers to 32 bytes including the 2 address bytes).
func (d *CAT24C512Full) MaxChunk() int { return d.maxChunk }

// SetMaxChunk sets the maximum data bytes per bus transaction (must be ≥ 1).
func (d *CAT24C512Full) SetMaxChunk(n int) error {
	if n < 1 {
		return errors.New("cat24c512: max chunk must be >= 1")
	}
	d.maxChunk = n
	return nil
}

// Size returns the array size in bytes (65536).
func (d *CAT24C512Full) Size() int { return cat24c512Size }

// PageSize returns the write page size in bytes (128).
func (d *CAT24C512Full) PageSize() int { return cat24c512Page }

// WaitReady ACK-polls until the chip answers its device address again and
// reports whether it did within timeout.
func (d *CAT24C512Full) WaitReady(timeout time.Duration) bool { return d.waitReady(timeout) }

func cat24c512CheckRange(address, length int) error {
	if address < 0 || length < 0 || address+length > cat24c512Size {
		return ErrCAT24C512Range
	}
	return nil
}

// chunkLen returns the next chunk length at address: bounded by the page end,
// MaxChunk and the remaining length.
func (d *CAT24C512Full) chunkLen(address, remaining int) int {
	n := cat24c512Page - address%cat24c512Page
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
// 0x0000–0xFFFF.
func (d *CAT24C512Full) Read(address, length int) ([]byte, error) {
	if err := cat24c512CheckRange(address, length); err != nil {
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

// Write writes data starting at address, split at 128-byte page boundaries and
// MaxChunk; the write cycle is ACK-polled after every chunk. The range must
// lie inside 0x0000–0xFFFF.
func (d *CAT24C512Full) Write(address int, data []byte) error {
	if err := cat24c512CheckRange(address, len(data)); err != nil {
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

// WritePage writes data that lies inside one 128-byte page (sent in MaxChunk
// pieces), ACK-polled. Returns ErrCAT24C512PageBoundary if the data crosses a
// page boundary or is longer than a page.
func (d *CAT24C512Full) WritePage(address int, data []byte) error {
	if err := cat24c512CheckRange(address, len(data)); err != nil {
		return err
	}
	if len(data) > cat24c512Page || address%cat24c512Page+len(data) > cat24c512Page {
		return ErrCAT24C512PageBoundary
	}
	return d.Write(address, data)
}

// Fill writes value over length bytes starting at address, page by page.
func (d *CAT24C512Full) Fill(address, length int, value byte) error {
	if err := cat24c512CheckRange(address, length); err != nil {
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
