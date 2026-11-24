package color

import (
	"errors"
	"sync"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// ISL29125 registers.
const (
	isl29125RegDeviceID uint8 = 0x00
	isl29125RegConfig1  uint8 = 0x01
	isl29125RegConfig2  uint8 = 0x02
	isl29125RegConfig3  uint8 = 0x03
	isl29125RegLowTh    uint8 = 0x04
	isl29125RegHighTh   uint8 = 0x06
	isl29125RegStatus   uint8 = 0x08
	isl29125RegGreen    uint8 = 0x09
	isl29125RegRed      uint8 = 0x0B
	isl29125RegBlue     uint8 = 0x0D

	isl29125ResetValue uint8 = 0x46
	isl29125Cfg1Sync   uint8 = 0x20
	isl29125Cfg1Bits12 uint8 = 0x10
	isl29125Cfg1Rng10K uint8 = 0x08
	isl29125Cfg1Mode   uint8 = 0x07
	isl29125Cfg3Conven uint8 = 0x10
)

// ISL29125I2CAddress is the fixed 7-bit I²C address.
const ISL29125I2CAddress uint8 = 0x44

// ISL29125DeviceID is the expected DEVICE_ID value.
const ISL29125DeviceID uint8 = 0x7D

// Interrupt sources for EnableInterrupt / DisableInterrupt.
const (
	// ISL29125SourceThreshold: the selected channel is outside the threshold window.
	ISL29125SourceThreshold uint8 = 0x01
	// ISL29125SourceConversion: a conversion has completed.
	ISL29125SourceConversion uint8 = 0x02
)

// STATUS bits.
const (
	// ISL29125StatusBrownout is set after a power-down or brown-out.
	ISL29125StatusBrownout uint8 = 0x04
	// ISL29125StatusConversion is set when a conversion has completed.
	ISL29125StatusConversion uint8 = 0x02
	// ISL29125StatusThreshold is set when the threshold window was crossed.
	ISL29125StatusThreshold uint8 = 0x01
)

// Operating modes for SetMode.
const (
	ISL29125ModePowerDown uint8 = 0
	ISL29125ModeGreen     uint8 = 1
	ISL29125ModeRed       uint8 = 2
	ISL29125ModeBlue      uint8 = 3
	ISL29125ModeStandby   uint8 = 4
	ISL29125ModeRGB       uint8 = 5
	ISL29125ModeGreenRed  uint8 = 6
	ISL29125ModeGreenBlue uint8 = 7
)

// Interrupt channels for SetInterruptChannel.
const (
	ISL29125ChannelNone  uint8 = 0
	ISL29125ChannelGreen uint8 = 1
	ISL29125ChannelRed   uint8 = 2
	ISL29125ChannelBlue  uint8 = 3
)

// isl29125PollStep is the step while waiting for CONVENF. Tests set it to 0.
var isl29125PollStep = 10 * time.Millisecond

const isl29125WaitLimit = 400 * time.Millisecond

var isl29125Persistence = [4]uint8{1, 2, 4, 8}

// ErrISL29125NotFound is returned when DEVICE_ID does not identify an ISL29125.
var ErrISL29125NotFound = errors.New("isl29125: DEVICE_ID mismatch")

// ErrISL29125Range is returned for an argument outside the supported values; nothing is written.
var ErrISL29125Range = errors.New("isl29125: argument out of range")

// ISL29125Counts holds the three channel counts of one conversion (unsigned 16 bit).
type ISL29125Counts struct {
	Red, Green, Blue uint16
}

func isl29125Word(b []byte) uint16 { return uint16(b[0]) | uint16(b[1])<<8 }

// ISL29125Minimal is the minimal ISL29125 interface: the three channel counts
// of one conversion.
//
// The constructor reads DEVICE_ID (must be 0x7D), clears the brown-out flag and
// writes CONFIG1 = 0x0D (RGB, 10 000 lux, 16 bit), CONFIG2 = 0xBF (maximum IR
// compensation), CONFIG3 = 0x00. Reading STATUS clears its flags, so the driver
// keeps the flags it has seen until PollInterrupt / Status hands them out.
type ISL29125Minimal struct {
	conn    connection.RegisterConnection
	pending uint8
}

// NewISL29125Minimal creates an ISL29125Minimal and starts the sensor.
// It returns ErrISL29125NotFound if DEVICE_ID does not match.
func NewISL29125Minimal(conn connection.RegisterConnection) (*ISL29125Minimal, error) {
	d := &ISL29125Minimal{conn: conn}
	id, err := d.readReg(isl29125RegDeviceID)
	if err != nil {
		return nil, err
	}
	if id != ISL29125DeviceID {
		return nil, ErrISL29125NotFound
	}
	for _, w := range [][2]uint8{
		{isl29125RegStatus, 0x00}, {isl29125RegConfig3, 0x00}, {isl29125RegConfig2, 0xBF}, {isl29125RegConfig1, 0x0D},
	} {
		if err := d.writeReg(w[0], w[1]); err != nil {
			return nil, err
		}
	}
	return d, nil
}

func (d *ISL29125Minimal) readReg(reg uint8) (uint8, error) {
	buf, err := d.conn.ReadReg(uint32(reg), 1)
	if err != nil {
		return 0, err
	}
	return buf[0], nil
}

func (d *ISL29125Minimal) writeReg(reg, value uint8) error {
	return d.conn.WriteReg(uint32(reg), []byte{value})
}

func (d *ISL29125Minimal) updateReg(reg, setBits, clearBits uint8) error {
	v, err := d.readReg(reg)
	if err != nil {
		return err
	}
	return d.writeReg(reg, (v&^clearBits)|setBits)
}

func (d *ISL29125Minimal) readStatus() (uint8, error) {
	v, err := d.readReg(isl29125RegStatus)
	if err != nil {
		return 0, err
	}
	d.pending |= v & (ISL29125StatusBrownout | ISL29125StatusConversion | ISL29125StatusThreshold)
	return v, nil
}

func (d *ISL29125Minimal) readBlock() (ISL29125Counts, error) {
	raw, err := d.conn.ReadReg(uint32(isl29125RegGreen), 6)
	if err != nil {
		return ISL29125Counts{}, err
	}
	return ISL29125Counts{Green: isl29125Word(raw[0:2]), Red: isl29125Word(raw[2:4]), Blue: isl29125Word(raw[4:6])}, nil
}

func (d *ISL29125Minimal) readChannel(reg uint8) (uint16, error) {
	raw, err := d.conn.ReadReg(uint32(reg), 2)
	if err != nil {
		return 0, err
	}
	return isl29125Word(raw), nil
}

// Read reads red, green and blue from one conversion. It waits for a new
// result (at most 400 ms), then block-reads registers 0x09-0x0E.
func (d *ISL29125Minimal) Read() (ISL29125Counts, error) {
	step := isl29125PollStep
	if step <= 0 {
		step = time.Millisecond
	}
	for waited := time.Duration(0); waited <= isl29125WaitLimit; waited += step {
		st, err := d.readStatus()
		if err != nil {
			return ISL29125Counts{}, err
		}
		if st&ISL29125StatusConversion != 0 {
			break
		}
		if isl29125PollStep > 0 {
			time.Sleep(isl29125PollStep)
		}
	}
	return d.readBlock()
}

// ISL29125Full extends ISL29125Minimal with single-channel reads, mode, range,
// resolution, synchronisation and IR-compensation configuration, thresholds,
// persistence, and interrupt delivery.
type ISL29125Full struct {
	*ISL29125Minimal

	mu          sync.Mutex
	callback    func(status uint8)
	unsubscribe func()
	pollPin     *connection.PollingInputPin
}

// NewISL29125Full creates an ISL29125Full with the same initialization as NewISL29125Minimal.
func NewISL29125Full(conn connection.RegisterConnection) (*ISL29125Full, error) {
	m, err := NewISL29125Minimal(conn)
	if err != nil {
		return nil, err
	}
	return &ISL29125Full{ISL29125Minimal: m}, nil
}

// ReadRed reads the red channel (2-byte read).
func (d *ISL29125Full) ReadRed() (uint16, error) { return d.readChannel(isl29125RegRed) }

// ReadGreen reads the green channel.
func (d *ISL29125Full) ReadGreen() (uint16, error) { return d.readChannel(isl29125RegGreen) }

// ReadBlue reads the blue channel.
func (d *ISL29125Full) ReadBlue() (uint16, error) { return d.readChannel(isl29125RegBlue) }

// ReadRGB block-reads all three channels without waiting.
func (d *ISL29125Full) ReadRGB() (ISL29125Counts, error) { return d.readBlock() }

// DataReady reports whether a conversion has completed. It reads STATUS,
// which clears its flags; the flags are kept for PollInterrupt and Status.
func (d *ISL29125Full) DataReady() (bool, error) {
	v, err := d.readStatus()
	if err != nil {
		return false, err
	}
	return v&ISL29125StatusConversion != 0, nil
}

// SetMode selects the operating mode (ISL29125ModePowerDown … ISL29125ModeGreenBlue);
// conversions are continuous. Any other value returns ErrISL29125Range.
func (d *ISL29125Full) SetMode(mode uint8) error {
	if mode > ISL29125ModeGreenBlue {
		return ErrISL29125Range
	}
	return d.updateReg(isl29125RegConfig1, mode, isl29125Cfg1Mode)
}

// SetRange selects the sensing range: 375 or 10000 lux full scale.
// Any other value returns ErrISL29125Range.
func (d *ISL29125Full) SetRange(lux uint16) error {
	switch lux {
	case 375:
		return d.updateReg(isl29125RegConfig1, 0, isl29125Cfg1Rng10K)
	case 10000:
		return d.updateReg(isl29125RegConfig1, isl29125Cfg1Rng10K, 0)
	}
	return ErrISL29125Range
}

// SetResolution selects the ADC resolution: 12 or 16 bit.
// Any other value returns ErrISL29125Range.
func (d *ISL29125Full) SetResolution(bits uint8) error {
	switch bits {
	case 16:
		return d.updateReg(isl29125RegConfig1, 0, isl29125Cfg1Bits12)
	case 12:
		return d.updateReg(isl29125RegConfig1, isl29125Cfg1Bits12, 0)
	}
	return ErrISL29125Range
}

// SetSync selects the conversion start source: true starts on the rising edge
// of INT (INT becomes an input), false on the I²C write.
func (d *ISL29125Full) SetSync(external bool) error {
	var set uint8
	if external {
		set = isl29125Cfg1Sync
	}
	return d.updateReg(isl29125RegConfig1, set, isl29125Cfg1Sync)
}

// SetIRCompensation sets the active IR compensation (CONFIG2): offset false
// selects codes 0-63, true codes 106-169; adjust is 0-63, otherwise ErrISL29125Range.
func (d *ISL29125Full) SetIRCompensation(offset bool, adjust uint8) error {
	if adjust > 63 {
		return ErrISL29125Range
	}
	v := adjust
	if offset {
		v |= 0x80
	}
	return d.writeReg(isl29125RegConfig2, v)
}

// DeviceID reads DEVICE_ID (0x7D for the ISL29125).
func (d *ISL29125Full) DeviceID() (uint8, error) { return d.readReg(isl29125RegDeviceID) }

// Status reads STATUS (clearing the flags on the chip) with the flags seen
// since the last call merged in.
func (d *ISL29125Full) Status() (uint8, error) {
	v, err := d.readStatus()
	if err != nil {
		return 0, err
	}
	v |= d.pending
	d.pending = 0
	return v, nil
}

// SoftReset resets all registers to their defaults (writes 0x46 to register 0x00).
func (d *ISL29125Full) SoftReset() error {
	if err := d.writeReg(isl29125RegDeviceID, isl29125ResetValue); err != nil {
		return err
	}
	if isl29125PollStep > 0 {
		time.Sleep(10 * time.Millisecond)
	}
	d.pending = 0
	return nil
}

// SetThresholds sets the 16-bit threshold window (low ≤ high); otherwise ErrISL29125Range.
func (d *ISL29125Full) SetThresholds(low, high uint16) error {
	if low > high {
		return ErrISL29125Range
	}
	if err := d.conn.WriteReg(uint32(isl29125RegLowTh), []byte{byte(low), byte(low >> 8)}); err != nil {
		return err
	}
	return d.conn.WriteReg(uint32(isl29125RegHighTh), []byte{byte(high), byte(high >> 8)})
}

// GetThresholds reads the threshold window.
func (d *ISL29125Full) GetThresholds() (low, high uint16, err error) {
	lo, err := d.conn.ReadReg(uint32(isl29125RegLowTh), 2)
	if err != nil {
		return 0, 0, err
	}
	hi, err := d.conn.ReadReg(uint32(isl29125RegHighTh), 2)
	if err != nil {
		return 0, 0, err
	}
	return isl29125Word(lo), isl29125Word(hi), nil
}

// SetInterruptChannel selects the channel the threshold interrupt monitors
// (ISL29125ChannelNone / Green / Red / Blue); otherwise ErrISL29125Range.
func (d *ISL29125Full) SetInterruptChannel(channel uint8) error {
	if channel > ISL29125ChannelBlue {
		return ErrISL29125Range
	}
	return d.updateReg(isl29125RegConfig3, channel, 0x03)
}

// SetPersistence sets the number of integration cycles (1, 2, 4 or 8) before
// the interrupt asserts; otherwise ErrISL29125Range.
func (d *ISL29125Full) SetPersistence(count uint8) error {
	for code, c := range isl29125Persistence {
		if c == count {
			return d.updateReg(isl29125RegConfig3, uint8(code)<<2, 0x0C)
		}
	}
	return ErrISL29125Range
}

// EnableInterrupt enables an interrupt source (ISL29125SourceThreshold or
// ISL29125SourceConversion). For the threshold source select the channel
// beforehand; green is used if none is set.
func (d *ISL29125Full) EnableInterrupt(source uint8) error {
	if source == ISL29125SourceConversion {
		return d.updateReg(isl29125RegConfig3, isl29125Cfg3Conven, 0)
	}
	v, err := d.readReg(isl29125RegConfig3)
	if err != nil {
		return err
	}
	if v&0x03 == 0 {
		return d.updateReg(isl29125RegConfig3, ISL29125ChannelGreen, 0)
	}
	return nil
}

// DisableInterrupt disables an interrupt source (threshold: INTSEL = none;
// conversion: CONVEN = 0).
func (d *ISL29125Full) DisableInterrupt(source uint8) error {
	if source == ISL29125SourceConversion {
		return d.updateReg(isl29125RegConfig3, 0, isl29125Cfg3Conven)
	}
	return d.updateReg(isl29125RegConfig3, 0, 0x03)
}

// PollInterrupt reads STATUS (clearing the flags) and returns it;
// ISL29125StatusThreshold / ISL29125StatusConversion are set for events seen.
func (d *ISL29125Full) PollInterrupt() (uint8, error) { return d.Status() }

// OnInterrupt subscribes callback to INT assertions (falling edge). The
// callback receives the STATUS byte after the driver has read (and
// thereby cleared) it. It uses the connection's InputPin if wired, otherwise a
// polling goroutine.
func (d *ISL29125Full) OnInterrupt(callback func(status uint8)) error {
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
func (d *ISL29125Full) OffInterrupt() error {
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

// handleEdge is called on each INT edge (real or polled): it reads and clears
// STATUS and dispatches the callback if a threshold or conversion flag is set.
func (d *ISL29125Full) handleEdge() {
	status, err := d.PollInterrupt()
	if err != nil || status&(ISL29125StatusThreshold|ISL29125StatusConversion) == 0 {
		return
	}
	d.mu.Lock()
	cb := d.callback
	d.mu.Unlock()
	if cb != nil {
		cb(status)
	}
}
