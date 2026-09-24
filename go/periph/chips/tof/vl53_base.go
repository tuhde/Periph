package tof

import (
	"sync"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// Shared plumbing for ST's VL53 FlightSense Time-of-Flight ranging family.
// VL53L0X and VL53L1X have different register maps (8-bit vs 16-bit register
// index), so vl53Base holds no register addresses and no ranging logic, only
// what both chips share: big-endian register access with a 1- or 2-byte
// index, the bounded poll helper, the XSHUT boot wait, the mutex that
// serializes multi-register sequences against the interrupt goroutine,
// interrupt delivery (IntPin edge or 5 ms polling pin), and the volatile
// re-addressing helper. It is embedded by value in each chip's Minimal
// driver; see specs/tof/_vl53_base.md.

const (
	vl53I2CAddress uint8 = 0x29
	vl53Timeout          = 500 * time.Millisecond
	vl53BootTime         = 1200 * time.Microsecond

	// Logical interrupt sources shared by the family (mutually exclusive).
	vl53SourceLevelLow       uint8 = 1
	vl53SourceLevelHigh      uint8 = 2
	vl53SourceOutOfWindow    uint8 = 3
	vl53SourceNewSampleReady uint8 = 4
	vl53SourceInWindow       uint8 = 5 // VL53L1X only
)

type vl53Base struct {
	conn       connection.Connection
	indexBytes int
	timeoutErr error

	// bus serializes multi-register sequences against the Full driver's
	// interrupt goroutine (a status read/clear must not interleave with them).
	bus sync.Mutex

	mu          sync.Mutex
	callback    func(uint8)
	unsubscribe func()
	pollPin     *connection.PollingInputPin
}

func newVL53Base(conn connection.Connection, indexBytes int, timeoutErr error) vl53Base {
	return vl53Base{conn: conn, indexBytes: indexBytes, timeoutErr: timeoutErr}
}

func (b *vl53Base) index(reg uint16) []byte {
	if b.indexBytes == 2 {
		return []byte{byte(reg >> 8), byte(reg)}
	}
	return []byte{byte(reg)}
}

func (b *vl53Base) writeBlock(reg uint16, data ...byte) error {
	return b.conn.Write(append(b.index(reg), data...))
}

func (b *vl53Base) readBlock(reg uint16, n int) ([]byte, error) {
	return b.conn.WriteRead(b.index(reg), n)
}

func (b *vl53Base) write8(reg uint16, value uint8) error {
	return b.writeBlock(reg, value)
}

func (b *vl53Base) read8(reg uint16) (uint8, error) {
	v, err := b.readBlock(reg, 1)
	if err != nil {
		return 0, err
	}
	return v[0], nil
}

func (b *vl53Base) write16(reg uint16, value uint16) error {
	return b.writeBlock(reg, byte(value>>8), byte(value))
}

func (b *vl53Base) read16(reg uint16) (uint16, error) {
	v, err := b.readBlock(reg, 2)
	if err != nil {
		return 0, err
	}
	return uint16(v[0])<<8 | uint16(v[1]), nil
}

func (b *vl53Base) write32(reg uint16, value uint32) error {
	return b.writeBlock(reg, byte(value>>24), byte(value>>16), byte(value>>8), byte(value))
}

func (b *vl53Base) read32(reg uint16) (uint32, error) {
	v, err := b.readBlock(reg, 4)
	if err != nil {
		return 0, err
	}
	return uint32(v[0])<<24 | uint32(v[1])<<16 | uint32(v[2])<<8 | uint32(v[3]), nil
}

// waitUntil polls predicate until it returns true; the chip's timeout error
// after 500 ms.
func (b *vl53Base) waitUntil(predicate func() (bool, error)) error {
	start := time.Now()
	for {
		ok, err := predicate()
		if err != nil {
			return err
		}
		if ok {
			return nil
		}
		if time.Since(start) > vl53Timeout {
			return b.timeoutErr
		}
	}
}

// bootWait drives XSHUT high (if the connection has an EnPin) and waits tBOOT.
func (b *vl53Base) bootWait() {
	if b.conn.EnPin() != nil {
		b.conn.Enable()
	}
	time.Sleep(vl53BootTime)
}

// setAddressReg writes a new 7-bit address to reg; invalidErr (no write)
// outside 0x08-0x77.
func (b *vl53Base) setAddressReg(reg uint16, address uint8, invalidErr error) error {
	if address < 0x08 || address > 0x77 {
		return invalidErr
	}
	return b.write8(reg, address&0x7F)
}

// subscribe delivers GPIO1 events (falling edge, active low) to callback.
// poll is the chip's locked read-and-clear; it returns the SOURCE value or 0.
// Without an IntPin a 5 ms polling pin is used.
func (b *vl53Base) subscribe(callback func(status uint8), poll func() (uint8, error)) error {
	b.mu.Lock()
	if b.unsubscribe != nil {
		b.unsubscribe()
		b.unsubscribe = nil
	}
	if b.pollPin != nil {
		_ = b.pollPin.Close()
		b.pollPin = nil
	}
	b.callback = callback
	b.mu.Unlock()

	pin := b.conn.IntPin()
	if pin == nil {
		p := connection.NewDefaultPollingInputPin()
		b.mu.Lock()
		b.pollPin = p
		b.mu.Unlock()
		pin = p
	}
	unsub := pin.OnEdge(connection.Falling, func() { b.handleEdge(poll) })
	b.mu.Lock()
	b.unsubscribe = unsub
	b.mu.Unlock()
	return nil
}

// unsubscribeAll detaches the edge handler, stops the polling pin and clears
// the callback.
func (b *vl53Base) unsubscribeAll() error {
	b.mu.Lock()
	unsub := b.unsubscribe
	b.unsubscribe = nil
	b.callback = nil
	p := b.pollPin
	b.pollPin = nil
	b.mu.Unlock()

	if unsub != nil {
		unsub()
	}
	if p != nil {
		return p.Close()
	}
	return nil
}

func (b *vl53Base) handleEdge(poll func() (uint8, error)) {
	status, err := poll()
	if err != nil || status == 0 {
		return
	}
	b.mu.Lock()
	cb := b.callback
	b.mu.Unlock()
	if cb != nil {
		cb(status)
	}
}
