package memory

import (
	"errors"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// AT24C02N constants.
const (
	at24c02nSize         = 0x0100
	at24c02nPage         = 8
	at24c02nDefaultChunk = 30
	at24c02nWriteTimeout = 10 * time.Millisecond // tWR = 5 ms max, plus margin
)

// ErrAT24C02NRange is returned when an address or range lies outside
// 0x0000–0x00FF (the hardware would silently wrap around).
var ErrAT24C02NRange = errors.New("at24c02n: address range outside 0-255")

// ErrAT24C02NPageBoundary is returned by WritePage for data that would cross
// a 8-byte page boundary or exceed one page.
var ErrAT24C02NPageBoundary = errors.New("at24c02n: write crosses a page boundary")

// ErrAT24C02NTimeout is returned when the chip does not acknowledge its
// address again within the write-cycle timeout.
var ErrAT24C02NTimeout = errors.New("at24c02n: write cycle did not complete")

// AT24C02NMinimal is the AT24C02N 2 Kbit (256 B) I²C EEPROM — minimal
// interface.
//
// Writes are self-timed (5 ms max) and completed by ACK polling: after every
// write the driver polls the device address until the chip answers again, so a
// returned write is always committed. The chip has no configuration registers.
// The connection must be a RegisterConnection with a 1-byte register width
// (connection.NewI2CConnection(bus, addr, nil, nil)) at address
// 0x50–0x57. WP high protects the entire array against writes; the driver cannot
// detect it.
type AT24C02NMinimal struct {
	connection connection.RegisterConnection
}

// NewAT24C02NMinimal creates a driver bound to conn. No bus traffic occurs.
func NewAT24C02NMinimal(conn connection.RegisterConnection) (*AT24C02NMinimal, error) {
	return &AT24C02NMinimal{connection: conn}, nil
}

// waitReady ACK-polls: an address-only write is acknowledged once the write
// cycle has ended.
func (d *AT24C02NMinimal) waitReady(timeout time.Duration) bool {
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

func (d *AT24C02NMinimal) commit() error {
	if !d.waitReady(at24c02nWriteTimeout) {
		return ErrAT24C02NTimeout
	}
	return nil
}

// ReadByteAt reads one byte (random read) at address 0–255. Returns
// ErrAT24C02NRange for an out-of-range address.
func (d *AT24C02NMinimal) ReadByteAt(address int) (byte, error) {
	if address < 0 || address >= at24c02nSize {
		return 0, ErrAT24C02NRange
	}
	buf, err := d.connection.ReadReg(uint32(address), 1)
	if err != nil {
		return 0, err
	}
	return buf[0], nil
}

// WriteByteAt writes one byte at address 0–255, then ACK-polls until the
// write cycle completes. Returns ErrAT24C02NRange for an out-of-range address
// and ErrAT24C02NTimeout if the chip does not answer again in time.
func (d *AT24C02NMinimal) WriteByteAt(address int, value byte) error {
	if address < 0 || address >= at24c02nSize {
		return ErrAT24C02NRange
	}
	if err := d.connection.WriteReg(uint32(address), []byte{value}); err != nil {
		return err
	}
	return d.commit()
}

// AT24C02NFull is the AT24C02N full interface. Extends AT24C02NMinimal with
// sequential read, page-boundary-aware write, page write, fill, the array and
// page size and explicit ACK polling. Writes never cross a 8-byte page
// boundary and every chunk is ACK-polled.
type AT24C02NFull struct {
	*AT24C02NMinimal
	maxChunk int
}

// NewAT24C02NFull creates a full driver bound to conn. MaxChunk defaults to 30.
func NewAT24C02NFull(conn connection.RegisterConnection) (*AT24C02NFull, error) {
	m, err := NewAT24C02NMinimal(conn)
	if err != nil {
		return nil, err
	}
	return &AT24C02NFull{AT24C02NMinimal: m, maxChunk: at24c02nDefaultChunk}, nil
}

// MaxChunk returns the maximum data bytes per bus transaction (default 30;
// Arduino Wire limits transfers to 32 bytes including the address byte).
func (d *AT24C02NFull) MaxChunk() int { return d.maxChunk }

// SetMaxChunk sets the maximum data bytes per bus transaction (must be ≥ 1).
func (d *AT24C02NFull) SetMaxChunk(n int) error {
	if n < 1 {
		return errors.New("at24c02n: max chunk must be >= 1")
	}
	d.maxChunk = n
	return nil
}

// Size returns the array size in bytes (256).
func (d *AT24C02NFull) Size() int { return at24c02nSize }

// PageSize returns the write page size in bytes (8).
func (d *AT24C02NFull) PageSize() int { return at24c02nPage }

// WaitReady ACK-polls until the chip answers its device address again and
// reports whether it did within timeout.
func (d *AT24C02NFull) WaitReady(timeout time.Duration) bool { return d.waitReady(timeout) }

func at24c02nCheckRange(address, length int) error {
	if address < 0 || length < 0 || address+length > at24c02nSize {
		return ErrAT24C02NRange
	}
	return nil
}

// chunkLen returns the next chunk length at address: bounded by the page end,
// MaxChunk and the remaining length.
func (d *AT24C02NFull) chunkLen(address, remaining int) int {
	n := at24c02nPage - address%at24c02nPage
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
// 0x0000–0x00FF.
func (d *AT24C02NFull) Read(address, length int) ([]byte, error) {
	if err := at24c02nCheckRange(address, length); err != nil {
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

// Write writes data starting at address, split at 8-byte page boundaries and
// MaxChunk; the write cycle is ACK-polled after every chunk. The range must
// lie inside 0x0000–0x00FF.
func (d *AT24C02NFull) Write(address int, data []byte) error {
	if err := at24c02nCheckRange(address, len(data)); err != nil {
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

// WritePage writes data that lies inside one 8-byte page (sent in MaxChunk
// pieces), ACK-polled. Returns ErrAT24C02NPageBoundary if the data crosses a
// page boundary or is longer than a page.
func (d *AT24C02NFull) WritePage(address int, data []byte) error {
	if err := at24c02nCheckRange(address, len(data)); err != nil {
		return err
	}
	if len(data) > at24c02nPage || address%at24c02nPage+len(data) > at24c02nPage {
		return ErrAT24C02NPageBoundary
	}
	return d.Write(address, data)
}

// Fill writes value over length bytes starting at address, page by page.
func (d *AT24C02NFull) Fill(address, length int, value byte) error {
	if err := at24c02nCheckRange(address, length); err != nil {
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
