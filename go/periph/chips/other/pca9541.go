package other

import (
	"sync"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// PCA9541 command codes (register pointers).
const (
	pca9541RegIE      uint8 = 0x00
	pca9541RegControl uint8 = 0x01
	pca9541RegIStat   uint8 = 0x02
	pca9541AI         uint8 = 0x10

	pca9541MyBus   uint8 = 0x01
	pca9541NMyBus  uint8 = 0x02
	pca9541BusOn   uint8 = 0x04
	pca9541NBusOn  uint8 = 0x08
	pca9541BusInit uint8 = 0x10
	pca9541TestOn  uint8 = 0x40
	pca9541NTestOn uint8 = 0x80
)

// PCA9541I2CAddress is the 7-bit I²C address with A3..A0 = 0 (0x70 … 0x77 recommended).
const PCA9541I2CAddress uint8 = 0x70

// ISTAT bits / IE mask bits (interrupt sources).
const (
	PCA9541SourceIntIn      uint8 = 0x01
	PCA9541SourceBusInit    uint8 = 0x02
	PCA9541SourceBusNotIdle uint8 = 0x04
	PCA9541SourceBusLost    uint8 = 0x08
	PCA9541SourceTest       uint8 = 0x40
	PCA9541SourceOtherTest  uint8 = 0x80
	pca9541Maskable         uint8 = 0x0F
	pca9541PollInterval           = 100 * time.Millisecond
)

func pca9541HasControlBits(c uint8) bool {
	return (c&pca9541MyBus != 0) == (c&pca9541NMyBus != 0)
}

func pca9541ConnectedBits(c uint8) bool {
	return (c&pca9541BusOn != 0) != (c&pca9541NBusOn != 0)
}

func pca9541Bit(c, mask uint8) uint8 {
	if c&mask != 0 {
		return 1
	}
	return 0
}

// PCA9541Status is the decoded CONTROL register.
type PCA9541Status struct {
	HasControl       bool // MYBUS == NMYBUS (control, regardless of the connection)
	Connected        bool // BUSON != NBUSON (some master is connected)
	BusInitRequested bool
	OwnTest          bool
	OtherTest        bool
}

// PCA9541Minimal lets one of two redundant masters take, give up and check
// ownership of the downstream I²C bus of a PCA9541 2-to-1 bus selector (NXP).
// There is no arbitration: whichever master writes its CONTROL register last
// before a STOP wins. Each master sees its own copy of the IE, CONTROL and
// ISTAT registers behind the same addresses. The constructor reads CONTROL as a
// bus check and ISTAT once to clear stale flags; it writes nothing. The CONTROL
// byte to write depends on the read-only complement bits, so every change reads
// CONTROL first and applies the datasheet's Table 12 rule.
type PCA9541Minimal struct {
	conn connection.RegisterConnection
}

// NewPCA9541Minimal creates a PCA9541Minimal and runs the initialization
// sequence (a CONTROL read as bus check, an ISTAT read); it writes nothing.
func NewPCA9541Minimal(conn connection.RegisterConnection) (*PCA9541Minimal, error) {
	d := &PCA9541Minimal{conn: conn}
	if _, err := d.readReg(pca9541RegControl); err != nil {
		return nil, err
	}
	if _, err := d.readReg(pca9541RegIStat); err != nil {
		return nil, err
	}
	return d, nil
}

func (d *PCA9541Minimal) readReg(reg uint8) (uint8, error) {
	buf, err := d.conn.ReadReg(uint32(reg), 1)
	if err != nil {
		return 0, err
	}
	return buf[0], nil
}

func (d *PCA9541Minimal) writeReg(reg, value uint8) error {
	return d.conn.WriteReg(uint32(reg), []byte{value})
}

// HasControl reports whether this master's channel is connected to the
// downstream bus (MYBUS equals NMYBUS and BUSON differs from NBUSON).
func (d *PCA9541Minimal) HasControl() (bool, error) {
	c, err := d.readReg(pca9541RegControl)
	if err != nil {
		return false, err
	}
	return pca9541HasControlBits(c) && pca9541ConnectedBits(c), nil
}

// Acquire takes the downstream bus. It reads CONTROL and writes MYBUS / BUSON
// per the datasheet rule; the write ends with a STOP, which makes the switch.
// With busInit the chip first sends nine clocks, a NACK and a STOP on the
// downstream bus and flags BUSINIT in ISTAT. It returns true if this master has
// control afterwards, false if not within timeoutMs milliseconds.
func (d *PCA9541Minimal) Acquire(busInit bool, timeoutMs int) (bool, error) {
	c, err := d.readReg(pca9541RegControl)
	if err != nil {
		return false, err
	}
	w := (1-pca9541Bit(c, pca9541NBusOn))<<2 | pca9541Bit(c, pca9541NMyBus)
	if busInit {
		w |= pca9541BusInit
	}
	if err := d.writeReg(pca9541RegControl, w); err != nil {
		return false, err
	}
	for i := 0; i <= timeoutMs; i++ {
		if busInit {
			st, err := d.readReg(pca9541RegIStat)
			if err != nil {
				return false, err
			}
			if st&0x02 == 0 {
				time.Sleep(time.Millisecond)
				continue
			}
		}
		has, err := d.HasControl()
		if err != nil {
			return false, err
		}
		if has {
			return true, nil
		}
		time.Sleep(time.Millisecond)
	}
	return false, nil
}

// Release gives the downstream bus to the other master (MYBUS = NOT NMYBUS; the
// bus stays connected).
func (d *PCA9541Minimal) Release() error {
	c, err := d.readReg(pca9541RegControl)
	if err != nil {
		return err
	}
	return d.writeReg(pca9541RegControl, (1-pca9541Bit(c, pca9541NBusOn))<<2|(1-pca9541Bit(c, pca9541NMyBus)))
}

// PCA9541Full extends PCA9541Minimal with raw control access, connect /
// disconnect, bus recovery, interrupt masks, the INT functional test and
// interrupt delivery.
type PCA9541Full struct {
	*PCA9541Minimal

	mu          sync.Mutex
	callback    func(status uint8)
	unsubscribe func()
	pollPin     *connection.PollingInputPin
}

// NewPCA9541Full creates a PCA9541Full with the same initialization as
// NewPCA9541Minimal.
func NewPCA9541Full(conn connection.RegisterConnection) (*PCA9541Full, error) {
	m, err := NewPCA9541Minimal(conn)
	if err != nil {
		return nil, err
	}
	return &PCA9541Full{PCA9541Minimal: m}, nil
}

// GetControl reads the raw CONTROL byte.
func (d *PCA9541Full) GetControl() (uint8, error) { return d.readReg(pca9541RegControl) }

// IsConnected reports whether the downstream bus is connected to some master
// (BUSON differs from NBUSON).
func (d *PCA9541Full) IsConnected() (bool, error) {
	c, err := d.readReg(pca9541RegControl)
	if err != nil {
		return false, err
	}
	return pca9541ConnectedBits(c), nil
}

// Status decodes the CONTROL register.
func (d *PCA9541Full) Status() (PCA9541Status, error) {
	c, err := d.readReg(pca9541RegControl)
	if err != nil {
		return PCA9541Status{}, err
	}
	return PCA9541Status{
		HasControl:       pca9541HasControlBits(c),
		Connected:        pca9541ConnectedBits(c),
		BusInitRequested: c&pca9541BusInit != 0,
		OwnTest:          c&pca9541TestOn != 0,
		OtherTest:        c&pca9541NTestOn != 0,
	}, nil
}

// Connect turns the connection on without changing ownership (BUSON = NOT
// NBUSON; MYBUS as read).
func (d *PCA9541Full) Connect() error {
	c, err := d.readReg(pca9541RegControl)
	if err != nil {
		return err
	}
	return d.writeReg(pca9541RegControl, (1-pca9541Bit(c, pca9541NBusOn))<<2|c&pca9541MyBus)
}

// Disconnect disconnects both masters from the downstream bus (BUSON = NBUSON;
// ownership bits left).
func (d *PCA9541Full) Disconnect() error {
	c, err := d.readReg(pca9541RegControl)
	if err != nil {
		return err
	}
	return d.writeReg(pca9541RegControl, pca9541Bit(c, pca9541NBusOn)<<2|c&pca9541MyBus)
}

// RecoverBus recovers a hung downstream bus and takes it (Acquire with bus
// initialization: nine clocks, a NACK and a STOP, then connect).
func (d *PCA9541Full) RecoverBus(timeoutMs int) (bool, error) { return d.Acquire(true, timeoutMs) }

// SetInterruptMask writes the IE register (true = masked, no interrupt);
// all-false re-enables every interrupt.
func (d *PCA9541Full) SetInterruptMask(busLost, busNotIdle, busInit, intIn bool) error {
	var v uint8
	if busLost {
		v |= 8
	}
	if busNotIdle {
		v |= 4
	}
	if busInit {
		v |= 2
	}
	if intIn {
		v |= 1
	}
	return d.writeReg(pca9541RegIE, v)
}

// GetInterruptMask reads the IE register (bit set = interrupt masked).
func (d *PCA9541Full) GetInterruptMask() (uint8, error) { return d.readReg(pca9541RegIE) }

// TestInterrupt drives the INT lines for the functional test: own drives this
// master's INT low (TESTON), other the other master's (NTESTON); both false
// clears the test. The bus bits are written back unchanged.
func (d *PCA9541Full) TestInterrupt(own, other bool) error {
	c, err := d.readReg(pca9541RegControl)
	if err != nil {
		return err
	}
	w := c & (pca9541BusOn | pca9541MyBus)
	if own {
		w |= pca9541TestOn
	}
	if other {
		w |= pca9541NTestOn
	}
	return d.writeReg(pca9541RegControl, w)
}

// ReadAll reads IE, CONTROL and ISTAT in one auto-increment transaction (clears
// the self-clearing ISTAT flags).
func (d *PCA9541Full) ReadAll() (ie, control, istat uint8, err error) {
	buf, err := d.conn.ReadReg(uint32(pca9541AI|pca9541RegIE), 3)
	if err != nil {
		return 0, 0, 0, err
	}
	return buf[0], buf[1], buf[2], nil
}

// EnableInterrupt enables an interrupt source (clears its mask bit in IE); the
// test sources need no enable.
func (d *PCA9541Full) EnableInterrupt(source uint8) error {
	bit := source & pca9541Maskable
	if bit == 0 {
		return nil
	}
	ie, err := d.readReg(pca9541RegIE)
	if err != nil {
		return err
	}
	return d.writeReg(pca9541RegIE, ie&^bit)
}

// DisableInterrupt disables an interrupt source (sets its mask bit in IE).
func (d *PCA9541Full) DisableInterrupt(source uint8) error {
	bit := source & pca9541Maskable
	if bit == 0 {
		return nil
	}
	ie, err := d.readReg(pca9541RegIE)
	if err != nil {
		return err
	}
	return d.writeReg(pca9541RegIE, ie|bit)
}

// PollInterrupt reads ISTAT (clears BUSLOST / BUSOK / BUSINIT); bits as the
// PCA9541Source* constants.
func (d *PCA9541Full) PollInterrupt() (uint8, error) { return d.readReg(pca9541RegIStat) }

// OnInterrupt subscribes callback to this master's INT pin (falling edge). The
// callback receives the ISTAT byte read (which clears the self-clearing flags).
// It uses the connection's InputPin if wired, otherwise a polling goroutine.
func (d *PCA9541Full) OnInterrupt(callback func(status uint8)) error {
	d.mu.Lock()
	if d.unsubscribe != nil {
		d.unsubscribe()
		d.unsubscribe = nil
	}
	if d.pollPin != nil {
		_ = d.pollPin.Close()
		d.pollPin = nil
	}
	d.callback = callback

	pin := d.conn.IntPin()
	if pin == nil {
		poll := connection.NewDefaultPollingInputPin()
		d.pollPin = poll
		pin = poll
	}
	d.mu.Unlock()

	unsub := pin.OnEdge(connection.Falling, d.handleEdge)
	d.mu.Lock()
	d.unsubscribe = unsub
	d.mu.Unlock()
	return nil
}

// OffInterrupt unsubscribes interrupt delivery.
func (d *PCA9541Full) OffInterrupt() error {
	d.mu.Lock()
	unsub := d.unsubscribe
	d.unsubscribe = nil
	d.callback = nil
	poll := d.pollPin
	d.pollPin = nil
	d.mu.Unlock()

	if unsub != nil {
		unsub()
	}
	if poll != nil {
		return poll.Close()
	}
	return nil
}

// handleEdge is called on each INT edge (real or polled): it reads ISTAT and
// dispatches the callback if any flag is set.
func (d *PCA9541Full) handleEdge() {
	status, err := d.PollInterrupt()
	if err != nil || status == 0 {
		return
	}
	d.mu.Lock()
	cb := d.callback
	d.mu.Unlock()
	if cb != nil {
		cb(status)
	}
}
