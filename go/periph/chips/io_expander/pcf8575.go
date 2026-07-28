package ioexpander

import (
	"sync"

	"github.com/tuhde/Periph/go/periph/connection"
)

// PCF8575Minimal is the 16-bit quasi-bidirectional I/O port expander
// driver — minimal interface.
//
// Communicates over I²C at up to 400 kHz Fast mode. The 16 pins are
// organised as two independent 8-bit ports: Port 0 (P00–P07) and
// Port 1 (P10–P17). Like the PCF8574, direction is implicit — writing
// 1 enables the internal 100 µA pull-up (input/high-impedance) and
// writing 0 drives the pin strongly low (open-drain output, up to
// 25 mA sink). Two shadow registers track the output latches.
//
// At construction all 16 pins are initialised to input mode
// (shadow = [0xFF, 0xFF]).
//
// The device always transfers exactly 2 bytes per I²C transaction —
// ReadPort and WritePort issue a full 2-byte I²C transfer, preserving
// the other port's shadow value on writes.
type PCF8575Minimal struct {
	connection connection.Connection
	addr       uint8
	// shadow[0] covers pins 0–7 (Port 0), shadow[1] covers pins 8–15 (Port 1).
	shadow [2]uint8
}

// NewPCF8575Minimal creates a new PCF8575Minimal and sets all 16 pins
// to quasi-bidirectional input mode (writes [0xFF, 0xFF] to the bus).
//
// conn must be a configured I²C connection bound to the device's
// 7-bit address (0x20–0x27; default 0x20 with A2=A1=A0=0).
func NewPCF8575Minimal(conn connection.Connection, addr uint8) (*PCF8575Minimal, error) {
	d := &PCF8575Minimal{connection: conn, addr: addr, shadow: [2]uint8{0xFF, 0xFF}}
	if err := d.writeBoth(); err != nil {
		return nil, err
	}
	return d, nil
}

// ReadPort reads all 8 pins of the given port (0 or 1) as a bitmask.
// Always performs a full 2-byte I²C read; returns the byte for the
// requested port. Returns the actual logic level at each pin.
func (d *PCF8575Minimal) ReadPort(port uint8) (uint8, error) {
	buf, err := d.connection.Read(2)
	if err != nil {
		return 0, err
	}
	return buf[port&1], nil
}

// WritePort writes all 8 pins of the given port (0 or 1) at once and
// updates the shadow register. Always performs a full 2-byte I²C write,
// preserving the other port's shadow value.
func (d *PCF8575Minimal) WritePort(port uint8, mask uint8) error {
	d.shadow[port&1] = mask
	return d.writeBoth()
}

func (d *PCF8575Minimal) writeBoth() error {
	return d.connection.Write([]byte{d.shadow[0], d.shadow[1]})
}

func (d *PCF8575Minimal) readBoth() ([2]uint8, error) {
	buf, err := d.connection.Read(2)
	if err != nil {
		return [2]uint8{}, err
	}
	return [2]uint8{buf[0], buf[1]}, nil
}

// Pin returns a Pin proxy for pin n (0–15). port = n/8, bit = n%8.
func (d *PCF8575Minimal) Pin(n uint8) PCF8575Pin {
	return PCF8575Pin{chip: d, n: n}
}

// setPin is the internal helper that mutates the shadow and writes
// both bytes.
func (d *PCF8575Minimal) setPin(n uint8, high bool) error {
	portIdx := n >> 3
	bit := n & 7
	if high {
		d.shadow[portIdx] |= 1 << bit
	} else {
		d.shadow[portIdx] &^= 1 << bit
	}
	return d.writeBoth()
}

// PCF8575Pin is a GPIO proxy for a single PCF8575 pin.
//
// Obtained via (*PCF8575Minimal).Pin. The Pin holds a plain pointer back
// to the driver.
type PCF8575Pin struct {
	chip *PCF8575Minimal
	n    uint8
}

// Get reads the actual logic level at the pin. Performs a full 2-byte
// bus read; returns the bit for this pin.
func (p PCF8575Pin) Get() (bool, error) {
	buf, err := p.chip.readBoth()
	if err != nil {
		return false, err
	}
	portIdx := p.n >> 3
	bit := p.n & 7
	return ((buf[portIdx] >> bit) & 1) == 1, nil
}

// Set writes 1 (high — release to quasi-input) or 0 (low — drive low
// via open-drain sink).
func (p PCF8575Pin) Set(high bool) error {
	return p.chip.setPin(p.n, high)
}

// Toggle inverts the shadow bit for this pin.
func (p PCF8575Pin) Toggle() error {
	portIdx := p.n >> 3
	bit := p.n & 7
	high := ((p.chip.shadow[portIdx] >> bit) & 1) == 0
	return p.chip.setPin(p.n, high)
}

// pcf8575Watch is the per-pin bookkeeping record for PCF8575Full.Pin.Watch.
type pcf8575Watch struct {
	trigger connection.Trigger
	handler func(PCF8575FullPin)
	pin     PCF8575FullPin
}

// PCF8575Full is the PCF8575 driver — full interface. Extends
// PCF8575Minimal with interrupt-on-change support: chip-level
// OnInterrupt/OffInterrupt/PollInterrupt, plus per-pin Watch/Unwatch on
// PCF8575FullPin.
//
// The previous-read state is tracked internally; PollInterrupt returns
// a 16-bit bitmask of pins that changed since the last call (bits 0–7
// = Port 0, bits 8–15 = Port 1) and updates the stored previous value.
// Any I²C read clears the chip's INT output.
type PCF8575Full struct {
	*PCF8575Minimal
	prev [2]uint8

	mu          sync.Mutex
	callback    func(uint16)
	unsubscribe func()
	pollPin     *connection.PollingInputPin
	watches     [16]*pcf8575Watch
}

// NewPCF8575Full creates a new PCF8575Full, sets all 16 pins to input
// mode, and seeds the previous-read state.
func NewPCF8575Full(conn connection.Connection, addr uint8) (*PCF8575Full, error) {
	m, err := NewPCF8575Minimal(conn, addr)
	if err != nil {
		return nil, err
	}
	both, err := m.readBoth()
	if err != nil {
		return nil, err
	}
	return &PCF8575Full{PCF8575Minimal: m, prev: both}, nil
}

// PCF8575FullPin is a GPIO proxy for a single PCF8575 pin obtained via
// (*PCF8575Full).Pin — adds Watch/Unwatch to the base PCF8575Pin.
type PCF8575FullPin struct {
	PCF8575Pin
	full *PCF8575Full
}

// Pin returns a Pin proxy for pin n (0–15) backed by this driver's
// underlying connection, with Watch/Unwatch support.
func (d *PCF8575Full) Pin(n uint8) PCF8575FullPin {
	return PCF8575FullPin{PCF8575Pin: d.PCF8575Minimal.Pin(n), full: d}
}

// Watch subscribes handler to edges on this pin matching trigger. Requires
// OnInterrupt to have been called on the chip first (or a real hardware
// INT line wired via the Connection) so edges are actually delivered. At
// most one handler per pin; a second Watch call replaces the first.
func (p PCF8575FullPin) Watch(trigger connection.Trigger, handler func(PCF8575FullPin)) error {
	p.full.mu.Lock()
	p.full.watches[p.n] = &pcf8575Watch{trigger: trigger, handler: handler, pin: p}
	p.full.mu.Unlock()
	return nil
}

// Unwatch removes this pin's handler, if any.
func (p PCF8575FullPin) Unwatch() error {
	p.full.mu.Lock()
	p.full.watches[p.n] = nil
	p.full.mu.Unlock()
	return nil
}

// OnInterrupt subscribes callback to be called with the 16-bit
// changed-pin bitmask on each INT assertion. Uses the connection's
// InputPin if wired, otherwise falls back to a 5 ms polling goroutine.
func (d *PCF8575Full) OnInterrupt(callback func(uint16)) error {
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

	pin := d.connection.IntPin()
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
func (d *PCF8575Full) OffInterrupt() error {
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

// PollInterrupt reads both ports, returns the 16-bit bitmask of pins
// that changed since the previous call, and updates the stored
// previous values. Bits 0–7 = Port 0 changed, bits 8–15 = Port 1
// changed. Reading also clears the chip's INT output.
func (d *PCF8575Full) PollInterrupt() (uint16, error) {
	current, err := d.PCF8575Minimal.readBoth()
	if err != nil {
		return 0, err
	}
	d.mu.Lock()
	changed0 := current[0] ^ d.prev[0]
	changed1 := current[1] ^ d.prev[1]
	d.prev = current
	d.mu.Unlock()
	return uint16(changed0) | (uint16(changed1) << 8), nil
}

// handleEdge is called on each INT edge (real or polled). It reads and
// clears the interrupt, then dispatches the chip-level callback and any
// matching per-pin watches.
func (d *PCF8575Full) handleEdge() {
	changed, err := d.PollInterrupt()
	if err != nil {
		return
	}

	d.mu.Lock()
	current := d.prev
	cb := d.callback
	type call struct {
		fn  func(PCF8575FullPin)
		pin PCF8575FullPin
	}
	var calls []call
	for n := uint8(0); n < 16; n++ {
		w := d.watches[n]
		if w == nil || (changed>>n)&1 == 0 {
			continue
		}
		portIdx := n >> 3
		bit := n & 7
		high := (current[portIdx]>>bit)&1 == 1
		fire := w.trigger == connection.Change ||
			(w.trigger == connection.Rising && high) ||
			(w.trigger == connection.Falling && !high)
		if fire {
			calls = append(calls, call{fn: w.handler, pin: w.pin})
		}
	}
	d.mu.Unlock()

	if cb != nil {
		cb(changed)
	}
	for _, c := range calls {
		c.fn(c.pin)
	}
}
