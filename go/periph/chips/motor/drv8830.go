// Package motor contains drivers for motor driver chips (DRV8830, etc.).
package motor

import (
	"errors"
	"fmt"
	"sync"

	"github.com/tuhde/Periph/go/periph/connection"
)

// DRV8830 register addresses.
const (
	drv8830RegControl uint8 = 0x00
	drv8830RegFault   uint8 = 0x01
)

// CONTROL (0x00) bits.
const (
	drv8830CtrlIN1 uint8 = 0x01
	drv8830CtrlIN2 uint8 = 0x02
)

// FAULT (0x01) bits.
const (
	drv8830FaultFault  uint8 = 0x01
	drv8830FaultOCP    uint8 = 0x02
	drv8830FaultUVLO   uint8 = 0x04
	drv8830FaultOTS    uint8 = 0x08
	drv8830FaultILimit uint8 = 0x10
	drv8830FaultClear  uint8 = 0x80
)

// DRV8830I2CAddress is the default 7-bit I²C address (A0 = A1 = GND). The
// A0/A1 straps select any address in 0x60-0x68.
const DRV8830I2CAddress uint8 = 0x60

// DRV8830VRef is the typical internal reference voltage in V (datasheet
// range 1.235-1.335 V).
const DRV8830VRef float32 = 1.285

// DRV8830VSetMin and DRV8830VSetMax bound the valid VSET codes (0-5 are
// reserved).
const (
	DRV8830VSetMin uint8 = 6
	DRV8830VSetMax uint8 = 63
)

// ErrDRV8830InvalidVSet is returned by SetOutput for a VSET code outside 6-63.
var ErrDRV8830InvalidVSet = errors.New("DRV8830: vset must be 6-63")

// DRV8830Direction is the H-bridge state decoded from IN1/IN2.
type DRV8830Direction uint8

// DRV8830Direction values, in IN2:IN1 bit order.
const (
	DRV8830Coast   DRV8830Direction = 0 // IN1=0, IN2=0 — outputs high-Z (standby)
	DRV8830Forward DRV8830Direction = 1 // IN1=1, IN2=0
	DRV8830Reverse DRV8830Direction = 2 // IN1=0, IN2=1
	DRV8830Brake   DRV8830Direction = 3 // IN1=1, IN2=1 — both outputs high
)

// String returns "coast", "forward", "reverse" or "brake".
func (d DRV8830Direction) String() string {
	switch d {
	case DRV8830Forward:
		return "forward"
	case DRV8830Reverse:
		return "reverse"
	case DRV8830Brake:
		return "brake"
	default:
		return "coast"
	}
}

// DRV8830Fault is the decoded FAULT register.
type DRV8830Fault struct {
	Fault  bool // any fault condition exists
	OCP    bool // overcurrent (short-circuit) event
	UVLO   bool // undervoltage lockout
	OTS    bool // overtemperature shutdown
	ILimit bool // extended current-limit event
}

// drv8830VoltageToVSet maps |voltage| to a VSET code; 0 means coast (below
// the vset=6 floor).
func drv8830VoltageToVSet(voltage float32) uint8 {
	if voltage < 0 {
		voltage = -voltage
	}
	vset := int(voltage*16/DRV8830VRef + 0.5)
	if vset < int(DRV8830VSetMin) {
		return 0
	}
	if vset > int(DRV8830VSetMax) {
		return DRV8830VSetMax
	}
	return uint8(vset)
}

func drv8830VSetToVoltage(vset uint8) float32 {
	if vset < DRV8830VSetMin {
		return 0
	}
	return DRV8830VRef * float32(vset) / 16
}

// DRV8830Minimal drives a brushed DC motor at a regulated voltage through a
// DRV8830 low-voltage H-bridge (Texas Instruments), controlled entirely over
// I²C. The chip PWM-regulates the bridge to hold the commanded average
// voltage regardless of supply sag. Conversion follows the datasheet's
// Table 1: voltage = VREF * vset / 16, usable 0.48-5.06 V.
type DRV8830Minimal struct {
	conn connection.Connection
}

// NewDRV8830Minimal creates a DRV8830Minimal and confirms the device answers
// on the bus (the DRV8830 has no identity register, so this is a plain
// CONTROL read). No register writes are made — the POR default already
// leaves the motor in standby/coast.
func NewDRV8830Minimal(conn connection.Connection) (*DRV8830Minimal, error) {
	d := &DRV8830Minimal{conn: conn}
	if _, err := d.readReg(drv8830RegControl); err != nil {
		return nil, fmt.Errorf("DRV8830: device not responding: %w", err)
	}
	return d, nil
}

func (d *DRV8830Minimal) readReg(reg uint8) (uint8, error) {
	b, err := d.conn.WriteRead([]byte{reg}, 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

func (d *DRV8830Minimal) writeReg(reg, val uint8) error {
	return d.conn.Write([]byte{reg, val})
}

// Drive drives the motor at a regulated output voltage. voltage is signed,
// in V: positive = forward, negative = reverse, 0 = standby/coast. VSET and
// IN1/IN2 are written together in one CONTROL write; a magnitude below the
// ~0.48 V floor coasts, above ~5.06 V it is clamped.
func (d *DRV8830Minimal) Drive(voltage float32) error {
	vset := drv8830VoltageToVSet(voltage)
	switch {
	case vset == 0:
		return d.writeReg(drv8830RegControl, 0x00)
	case voltage > 0:
		return d.writeReg(drv8830RegControl, vset<<2|drv8830CtrlIN1)
	default:
		return d.writeReg(drv8830RegControl, vset<<2|drv8830CtrlIN2)
	}
}

// Brake short-brakes the motor (IN1 = IN2 = 1, both outputs high).
func (d *DRV8830Minimal) Brake() error {
	return d.writeReg(drv8830RegControl, drv8830CtrlIN1|drv8830CtrlIN2)
}

// Stop puts the bridge in standby/coast (IN1 = IN2 = 0) — same as Drive(0).
func (d *DRV8830Minimal) Stop() error {
	return d.writeReg(drv8830RegControl, 0x00)
}

// DRV8830Full extends DRV8830Minimal with raw CONTROL access, output
// read-back, fault reporting/clearing, and the FAULTn interrupt API. Faults
// are never cleared implicitly: a latched OCP/ILIMIT fault also disables the
// H-bridge, so clearing is always an explicit ClearFault.
type DRV8830Full struct {
	*DRV8830Minimal

	mu          sync.Mutex
	callback    func(DRV8830Fault)
	unsubscribe func()
	pollPin     *connection.PollingInputPin
	wasFault    bool
}

// NewDRV8830Full creates a DRV8830Full.
func NewDRV8830Full(conn connection.Connection) (*DRV8830Full, error) {
	m, err := NewDRV8830Minimal(conn)
	if err != nil {
		return nil, err
	}
	return &DRV8830Full{DRV8830Minimal: m}, nil
}

// SetOutput writes the CONTROL register from raw fields. vset must be 6-63;
// reserved codes return ErrDRV8830InvalidVSet without touching the bus.
func (d *DRV8830Full) SetOutput(vset uint8, in1, in2 bool) error {
	if vset < DRV8830VSetMin || vset > DRV8830VSetMax {
		return ErrDRV8830InvalidVSet
	}
	v := vset << 2
	if in1 {
		v |= drv8830CtrlIN1
	}
	if in2 {
		v |= drv8830CtrlIN2
	}
	return d.writeReg(drv8830RegControl, v)
}

// ReadOutput reads back and decodes the CONTROL register. voltage is the
// commanded magnitude in V (0 for a reserved VSET code).
func (d *DRV8830Full) ReadOutput() (voltage float32, direction DRV8830Direction, err error) {
	c, err := d.readReg(drv8830RegControl)
	if err != nil {
		return 0, DRV8830Coast, err
	}
	return drv8830VSetToVoltage(c >> 2), DRV8830Direction(c & 0x03), nil
}

// ReadFault reads the FAULT register without clearing it.
func (d *DRV8830Full) ReadFault() (DRV8830Fault, error) {
	f, err := d.readReg(drv8830RegFault)
	if err != nil {
		return DRV8830Fault{}, err
	}
	return DRV8830Fault{
		Fault:  f&drv8830FaultFault != 0,
		OCP:    f&drv8830FaultOCP != 0,
		UVLO:   f&drv8830FaultUVLO != 0,
		OTS:    f&drv8830FaultOTS != 0,
		ILimit: f&drv8830FaultILimit != 0,
	}, nil
}

// ClearFault clears all fault status bits (CLEAR = 1); re-enables the
// H-bridge if an OCP/ILIMIT fault had latched it off.
func (d *DRV8830Full) ClearFault() error {
	return d.writeReg(drv8830RegFault, drv8830FaultClear)
}

// OnInterrupt subscribes callback to be invoked with the fault status when
// FAULTn asserts (falling edge). Uses the connection's IntPin if wired,
// otherwise falls back to a polling goroutine that fires once per new fault
// (FAULT bit rising). The fault is not cleared.
func (d *DRV8830Full) OnInterrupt(callback func(DRV8830Fault)) error {
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
	d.wasFault = false
	pin := d.conn.IntPin()
	if pin == nil {
		poll := connection.NewDefaultPollingInputPin()
		d.pollPin = poll
		pin = poll
	}
	d.mu.Unlock()

	d.unsubscribe = pin.OnEdge(connection.Falling, func() { d.handleEdge() })
	return nil
}

// OffInterrupt unsubscribes and stops delivery.
func (d *DRV8830Full) OffInterrupt() error {
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

// PollInterrupt reads the fault status — equivalent to ReadFault, does not
// clear.
func (d *DRV8830Full) PollInterrupt() (DRV8830Fault, error) {
	return d.ReadFault()
}

func (d *DRV8830Full) handleEdge() {
	status, err := d.PollInterrupt()
	if err != nil {
		return
	}
	d.mu.Lock()
	cb := d.callback
	// A real FAULTn edge is always a new fault; the polling fallback ticks
	// continuously, so it only reports the FAULT bit's rising transition.
	fire := status.Fault && (d.pollPin == nil || !d.wasFault)
	d.wasFault = status.Fault
	d.mu.Unlock()
	if fire && cb != nil {
		cb(status)
	}
}
