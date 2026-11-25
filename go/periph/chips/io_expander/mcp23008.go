package ioexpander

import (
	"errors"
	"sync"

	"github.com/tuhde/Periph/go/periph/connection"
)

// MCP23008 / MCP23S08 register addresses.
const (
	m08RegIODIR   uint8 = 0x00
	m08RegIPOL    uint8 = 0x01
	m08RegGPINTEN uint8 = 0x02
	m08RegDEFVAL  uint8 = 0x03
	m08RegINTCON  uint8 = 0x04
	m08RegIOCON   uint8 = 0x05
	m08RegGPPU    uint8 = 0x06
	m08RegINTF    uint8 = 0x07
	m08RegINTCAP  uint8 = 0x08
	m08RegGPIO    uint8 = 0x09
	m08RegOLAT    uint8 = 0x0A

	m08IOCONSEQOP  uint8 = 0x20
	m08IOCONHAEN   uint8 = 0x08
	m08IOCONODR    uint8 = 0x04
	m08IOCONINTPOL uint8 = 0x02
)

// PinMode selects a pin's direction (and pull-up, in the Full driver).
type PinMode uint8

// Pin modes accepted by (MCP23008Pin).Mode.
const (
	// MCP23008Input configures the pin as a high-impedance input.
	MCP23008Input PinMode = iota
	// MCP23008Output configures the pin as a push-pull output.
	MCP23008Output
	// MCP23008InputPullup configures the pin as an input with the 100 kΩ
	// pull-up enabled (Full driver only; Minimal treats it as MCP23008Input).
	MCP23008InputPullup
)

// IntMode selects what an armed pin is compared against to raise an interrupt.
type IntMode uint8

// Interrupt modes accepted by (*MCP23008Full).OnInterrupt.
const (
	// IntOnChange compares against the previous pin value (INTCON = 0).
	IntOnChange IntMode = iota
	// IntVsDefault compares against DEFVAL (INTCON = 1).
	IntVsDefault
)

// m08Bus is the register-level access to the chip over its serial interface.
type m08Bus interface {
	readReg(reg uint8) (uint8, error)
	writeReg(reg, value uint8) error
}

// m08I2C is the MCP23008 transport (1-byte register address).
type m08I2C struct {
	conn connection.RegisterConnection
}

func (b m08I2C) readReg(reg uint8) (uint8, error) {
	buf, err := b.conn.ReadReg(uint32(reg), 1)
	if err != nil {
		return 0, err
	}
	return buf[0], nil
}

func (b m08I2C) writeReg(reg, value uint8) error {
	return b.conn.WriteReg(uint32(reg), []byte{value})
}

// m08SPI is the MCP23S08 transport: [0x40 | (addr << 1) | R/W] [reg] [data…].
// It is not a RegisterConnection chip because of the separate opcode byte, so it
// talks to a plain Connection.
type m08SPI struct {
	conn connection.Connection
	addr uint8
}

func (b m08SPI) readReg(reg uint8) (uint8, error) {
	buf, err := b.conn.WriteRead([]byte{0x40 | (b.addr << 1) | 1, reg}, 1)
	if err != nil {
		return 0, err
	}
	return buf[0], nil
}

func (b m08SPI) writeReg(reg, value uint8) error {
	return b.conn.Write([]byte{0x40 | (b.addr << 1), reg, value})
}

// MCP23008Minimal is the MCP23008 (I²C) / MCP23S08 (SPI) 8-bit I/O port
// expander driver — minimal interface.
//
// Both chips have an identical register map and GPIO behaviour; they differ only
// in the serial interface. Use NewMCP23008Minimal for I²C and
// NewMCP23S08Minimal for SPI. All eight pins (GP0–GP7) are bidirectional.
// Direction is controlled per pin via IODIR (1 = input, 0 = output); shadows of
// OLAT and IODIR make a single-pin change one register write.
type MCP23008Minimal struct {
	bus       m08Bus
	conn      connection.Connection
	iocon     uint8
	ioconBase uint8
	shadow    uint8 // shadow of OLAT
	direction uint8 // shadow of IODIR (1 = input)
}

// MCP23S08Minimal is the SPI variant of the driver; it is the same type as
// MCP23008Minimal, constructed with NewMCP23S08Minimal.
type MCP23S08Minimal = MCP23008Minimal

func newMCP23x08(bus m08Bus, conn connection.Connection, ioconBase uint8) (*MCP23008Minimal, error) {
	d := &MCP23008Minimal{bus: bus, conn: conn, iocon: ioconBase, ioconBase: ioconBase, direction: 0xFF}
	if err := d.bus.writeReg(m08RegOLAT, 0x00); err != nil {
		return nil, err
	}
	return d, nil
}

// NewMCP23008Minimal creates an MCP23008 (I²C) driver and runs the
// initialisation sequence: OLAT is cleared; IODIR stays at its reset value 0xFF
// (all inputs).
//
// conn must be a configured I²C connection bound to the device's 7-bit address
// (0x20–0x27; default 0x20 with A2=A1=A0=0).
func NewMCP23008Minimal(conn connection.RegisterConnection, addr uint8) (*MCP23008Minimal, error) {
	_ = addr
	return newMCP23x08(m08I2C{conn: conn}, conn, 0x00)
}

// NewMCP23S08Minimal creates an MCP23S08 (SPI) driver. conn is a plain SPI
// Connection (mode 0, MSB first, CS active low) and addr the hardware address
// 0–3 (A1:A0 pins). IOCON.HAEN is set during construction, with the first IOCON
// write sent under the address-0 opcode 0x40 because HAEN resets to 0 (this also
// reaches every device on a shared chip select).
func NewMCP23S08Minimal(conn connection.Connection, addr uint8) (*MCP23008Minimal, error) {
	if err := conn.Write([]byte{0x40, m08RegIOCON, m08IOCONHAEN}); err != nil {
		return nil, err
	}
	return newMCP23x08(m08SPI{conn: conn, addr: addr & 3}, conn, m08IOCONHAEN)
}

// ReadPort reads all 8 pins via the GPIO register (actual pin levels). port is
// ignored; the chip has a single port.
func (d *MCP23008Minimal) ReadPort(port uint8) (uint8, error) {
	_ = port
	return d.bus.readReg(m08RegGPIO)
}

// WritePort writes all 8 output bits via OLAT and updates the shadow. Bits of
// input pins are stored but have no effect until the pin becomes an output.
// port is ignored; the chip has a single port.
func (d *MCP23008Minimal) WritePort(port uint8, mask uint8) error {
	_ = port
	d.shadow = mask
	return d.bus.writeReg(m08RegOLAT, mask)
}

// Pin returns a Pin proxy for pin n (0–7).
func (d *MCP23008Minimal) Pin(n uint8) MCP23008Pin {
	return MCP23008Pin{chip: d, n: n & 7}
}

func (d *MCP23008Minimal) setPin(n uint8, high bool) error {
	if high {
		d.shadow |= 1 << n
	} else {
		d.shadow &^= 1 << n
	}
	return d.bus.writeReg(m08RegOLAT, d.shadow)
}

// setDirection writes the pin's OLAT bit before clearing its IODIR bit so the
// pin never glitches to a stale level.
func (d *MCP23008Minimal) setDirection(n uint8, input bool) error {
	if input {
		d.direction |= 1 << n
	} else {
		if err := d.bus.writeReg(m08RegOLAT, d.shadow); err != nil {
			return err
		}
		d.direction &^= 1 << n
	}
	return d.bus.writeReg(m08RegIODIR, d.direction)
}

// MCP23008Pin is a GPIO proxy for a single pin. It satisfies
// connection.OutputPin (Set), so an output pin can be used directly as the EN
// pin of another chip's connection.
//
// Obtained via (*MCP23008Minimal).Pin.
type MCP23008Pin struct {
	chip *MCP23008Minimal
	n    uint8
}

// Mode sets the pin direction. MCP23008InputPullup behaves like
// MCP23008Input in the Minimal driver.
func (p MCP23008Pin) Mode(m PinMode) error {
	return p.chip.setDirection(p.n, m != MCP23008Output)
}

// Write sets the output latch to high (true) or low (false).
func (p MCP23008Pin) Write(high bool) error {
	return p.chip.setPin(p.n, high)
}

// Set is Write, satisfying connection.OutputPin.
func (p MCP23008Pin) Set(high bool) error {
	return p.chip.setPin(p.n, high)
}

// Read reads the actual logic level at the pin via the GPIO register.
func (p MCP23008Pin) Read() (bool, error) {
	v, err := p.chip.bus.readReg(m08RegGPIO)
	if err != nil {
		return false, err
	}
	return (v>>p.n)&1 == 1, nil
}

// Toggle inverts the output latch bit for this pin.
func (p MCP23008Pin) Toggle() error {
	return p.chip.setPin(p.n, (p.chip.shadow>>p.n)&1 == 0)
}

type mcp23008Watch struct {
	trigger connection.Trigger
	handler func(MCP23008FullPin)
}

// MCP23008Full is the MCP23008 / MCP23S08 driver — full interface. Extends
// MCP23008Minimal with pull-up configuration, polarity inversion,
// interrupt-on-change and raw register access.
type MCP23008Full struct {
	*MCP23008Minimal

	mu          sync.Mutex
	pullup      uint8
	gpinten     uint8
	activeHigh  bool
	callback    func(uint8)
	unsubscribe func()
	pollPin     *connection.PollingInputPin
	watches     [8]*mcp23008Watch
}

// MCP23S08Full is the SPI variant of MCP23008Full, constructed with
// NewMCP23S08Full.
type MCP23S08Full = MCP23008Full

// NewMCP23008Full creates an MCP23008 (I²C) full driver with the same
// initialisation as NewMCP23008Minimal.
func NewMCP23008Full(conn connection.RegisterConnection, addr uint8) (*MCP23008Full, error) {
	m, err := NewMCP23008Minimal(conn, addr)
	if err != nil {
		return nil, err
	}
	return &MCP23008Full{MCP23008Minimal: m}, nil
}

// NewMCP23S08Full creates an MCP23S08 (SPI) full driver with the same
// initialisation as NewMCP23S08Minimal.
func NewMCP23S08Full(conn connection.Connection, addr uint8) (*MCP23008Full, error) {
	m, err := NewMCP23S08Minimal(conn, addr)
	if err != nil {
		return nil, err
	}
	return &MCP23008Full{MCP23008Minimal: m}, nil
}

// ConfigurePullup writes GPPU: mask bit n = 1 enables the 100 kΩ pull-up on
// GPn. Only effective on pins configured as inputs.
func (d *MCP23008Full) ConfigurePullup(mask uint8) error {
	d.pullup = mask
	return d.bus.writeReg(m08RegGPPU, mask)
}

// ConfigurePolarity writes IPOL: mask bit n = 1 inverts the GPIO read of GPn.
func (d *MCP23008Full) ConfigurePolarity(mask uint8) error {
	return d.bus.writeReg(m08RegIPOL, mask)
}

// SetDefaultValue writes DEFVAL, the reference for default-compare interrupts.
func (d *MCP23008Full) SetDefaultValue(mask uint8) error {
	return d.bus.writeReg(m08RegDEFVAL, mask)
}

// EnablePinInterrupt writes GPINTEN: mask bit n = 1 arms GPn (only inputs can
// interrupt).
func (d *MCP23008Full) EnablePinInterrupt(mask uint8) error {
	d.gpinten = mask
	return d.bus.writeReg(m08RegGPINTEN, mask)
}

// ConfigureIntPin configures the INT output (IOCON ODR / INTPOL). openDrain
// overrides activeHigh.
func (d *MCP23008Full) ConfigureIntPin(openDrain, activeHigh bool) error {
	d.activeHigh = activeHigh && !openDrain
	d.iocon &^= m08IOCONODR | m08IOCONINTPOL
	if openDrain {
		d.iocon |= m08IOCONODR
	} else if activeHigh {
		d.iocon |= m08IOCONINTPOL
	}
	return d.bus.writeReg(m08RegIOCON, d.iocon)
}

// SetSequential enables or disables sequential register access (IOCON SEQOP).
// The driver itself never relies on a value other than enabled.
func (d *MCP23008Full) SetSequential(enabled bool) error {
	d.iocon &^= m08IOCONSEQOP
	if !enabled {
		d.iocon |= m08IOCONSEQOP
	}
	return d.bus.writeReg(m08RegIOCON, d.iocon)
}

// ReadRegister reads a raw register (debug / escape hatch).
func (d *MCP23008Full) ReadRegister(reg uint8) (uint8, error) {
	return d.bus.readReg(reg)
}

// WriteRegister writes a raw register; the IODIR / OLAT / IOCON / GPPU /
// GPINTEN shadows are kept in sync.
func (d *MCP23008Full) WriteRegister(reg, value uint8) error {
	switch reg {
	case m08RegIODIR:
		d.direction = value
	case m08RegOLAT:
		d.shadow = value
	case m08RegIOCON:
		d.iocon = value
	case m08RegGPPU:
		d.pullup = value
	case m08RegGPINTEN:
		d.gpinten = value
	}
	return d.bus.writeReg(reg, value)
}

// Reset rewrites every register to its reset value and clears the shadows. The
// chip has no reset command (RESET is an external pin). On the MCP23S08 IOCON is
// rewritten to HAEN (not 0) so addressed access keeps working.
func (d *MCP23008Full) Reset() error {
	if err := d.OffInterrupt(); err != nil {
		return err
	}
	for _, reg := range []uint8{m08RegIPOL, m08RegGPINTEN, m08RegDEFVAL, m08RegINTCON, m08RegGPPU, m08RegOLAT} {
		if err := d.bus.writeReg(reg, 0x00); err != nil {
			return err
		}
	}
	if err := d.bus.writeReg(m08RegIODIR, 0xFF); err != nil {
		return err
	}
	d.iocon = d.ioconBase
	if err := d.bus.writeReg(m08RegIOCON, d.iocon); err != nil {
		return err
	}
	d.shadow, d.direction, d.pullup, d.gpinten, d.activeHigh = 0x00, 0xFF, 0x00, 0x00, false
	return nil
}

// MCP23008FullPin is a GPIO proxy for a single pin obtained via
// (*MCP23008Full).Pin — adds SetPullUp and Watch/Unwatch, and satisfies
// connection.InputPin (OnEdge, IsHigh) as well as connection.OutputPin.
type MCP23008FullPin struct {
	MCP23008Pin
	full *MCP23008Full
}

// Pin returns a Full pin proxy for pin n (0–7).
func (d *MCP23008Full) Pin(n uint8) MCP23008FullPin {
	return MCP23008FullPin{MCP23008Pin: d.MCP23008Minimal.Pin(n), full: d}
}

// Mode sets the pin direction; MCP23008InputPullup also enables the pull-up
// (and MCP23008Input disables it).
func (p MCP23008FullPin) Mode(m PinMode) error {
	if err := p.MCP23008Pin.Mode(m); err != nil {
		return err
	}
	switch m {
	case MCP23008InputPullup:
		return p.SetPullUp(true)
	case MCP23008Input:
		return p.SetPullUp(false)
	}
	return nil
}

// SetPullUp enables or disables this pin's 100 kΩ pull-up (its GPPU bit).
func (p MCP23008FullPin) SetPullUp(on bool) error {
	mask := p.full.pullup
	if on {
		mask |= 1 << p.n
	} else {
		mask &^= 1 << p.n
	}
	return p.full.ConfigurePullup(mask)
}

// IsHigh reads the actual pin level, satisfying connection.InputPin.
func (p MCP23008FullPin) IsHigh() (bool, error) {
	return p.Read()
}

// Watch subscribes handler to changes on this pin matching trigger and arms the
// pin's GPINTEN bit. The chip reports changes only; the rising/falling filter is
// applied in software. Delivery starts once OnInterrupt has been called. At most
// one handler per pin; a second Watch replaces the first. Output pins cannot
// interrupt, so Watch on one returns an error.
func (p MCP23008FullPin) Watch(trigger connection.Trigger, handler func(MCP23008FullPin)) error {
	if (p.full.direction>>p.n)&1 == 0 {
		return errors.New("mcp23008: cannot watch an output pin")
	}
	p.full.mu.Lock()
	p.full.watches[p.n] = &mcp23008Watch{trigger: trigger, handler: handler}
	p.full.mu.Unlock()
	return p.full.EnablePinInterrupt(p.full.gpinten | 1<<p.n)
}

// Unwatch removes this pin's handler, if any, and clears its GPINTEN bit.
func (p MCP23008FullPin) Unwatch() error {
	p.full.mu.Lock()
	p.full.watches[p.n] = nil
	p.full.mu.Unlock()
	return p.full.EnablePinInterrupt(p.full.gpinten &^ (1 << p.n))
}

// OnEdge registers handler for edges on this pin, satisfying
// connection.InputPin, and returns a function that removes it.
func (p MCP23008FullPin) OnEdge(trigger connection.Trigger, handler func()) (unsubscribe func()) {
	if err := p.Watch(trigger, func(MCP23008FullPin) { handler() }); err != nil {
		return func() {}
	}
	return func() { _ = p.Unwatch() }
}

// OnInterrupt subscribes callback to INT assertions: it receives the INTF
// bitmask of the pins that caused the interrupt. Every pin armed via
// EnablePinInterrupt or Watch is used (none armed → error). mode selects the
// comparison. Uses the connection's InputPin if wired, otherwise a 5 ms polling
// goroutine.
func (d *MCP23008Full) OnInterrupt(callback func(status uint8), mode IntMode) error {
	if d.gpinten == 0 {
		return errors.New("mcp23008: no pins armed; call EnablePinInterrupt first")
	}
	intcon := uint8(0x00)
	if mode == IntVsDefault {
		intcon = d.gpinten
	}
	if err := d.bus.writeReg(m08RegINTCON, intcon); err != nil {
		return err
	}

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

	trigger := connection.Falling
	if d.activeHigh {
		trigger = connection.Rising
	}
	unsub := pin.OnEdge(trigger, d.handleEdge)
	d.mu.Lock()
	d.unsubscribe = unsub
	d.mu.Unlock()
	return nil
}

// OffInterrupt unsubscribes interrupt delivery (GPINTEN is left untouched).
func (d *MCP23008Full) OffInterrupt() error {
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

// PollInterrupt reads INTF, then INTCAP (clearing the interrupt), and returns
// INTF: bit n = 1 means GPn caused the interrupt. The captured port value is
// discarded — use ReadCapture.
func (d *MCP23008Full) PollInterrupt() (uint8, error) {
	flags, err := d.bus.readReg(m08RegINTF)
	if err != nil {
		return 0, err
	}
	if _, err := d.bus.readReg(m08RegINTCAP); err != nil {
		return 0, err
	}
	return flags, nil
}

// ReadCapture reads INTCAP, the port state captured at the interrupt. Reading
// it also clears the interrupt.
func (d *MCP23008Full) ReadCapture() (uint8, error) {
	return d.bus.readReg(m08RegINTCAP)
}

// handleEdge is called on each INT edge (real or polled). It reads INTF and
// INTCAP, then dispatches the callback and any matching per-pin watches.
func (d *MCP23008Full) handleEdge() {
	flags, err := d.bus.readReg(m08RegINTF)
	if err != nil || flags == 0 {
		return
	}
	captured, err := d.bus.readReg(m08RegINTCAP)
	if err != nil {
		return
	}

	d.mu.Lock()
	cb := d.callback
	type call struct {
		fn  func(MCP23008FullPin)
		pin MCP23008FullPin
	}
	var calls []call
	for n := uint8(0); n < 8; n++ {
		w := d.watches[n]
		if w == nil || (flags>>n)&1 == 0 {
			continue
		}
		high := (captured>>n)&1 == 1
		fire := w.trigger == connection.Change ||
			(w.trigger == connection.Rising && high) ||
			(w.trigger == connection.Falling && !high)
		if fire {
			calls = append(calls, call{fn: w.handler, pin: d.Pin(n)})
		}
	}
	d.mu.Unlock()

	if cb != nil {
		cb(flags)
	}
	for _, c := range calls {
		c.fn(c.pin)
	}
}
