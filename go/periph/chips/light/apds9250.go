package light

import (
	"errors"
	"sync"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// APDS-9250 registers.
const (
	apds9250RegMainCtrl       uint8 = 0x00
	apds9250RegMeasRate       uint8 = 0x04
	apds9250RegGain           uint8 = 0x05
	apds9250RegPartID         uint8 = 0x06
	apds9250RegMainStatus     uint8 = 0x07
	apds9250RegDataIR         uint8 = 0x0A
	apds9250RegDataGreen      uint8 = 0x0D
	apds9250RegDataBlue       uint8 = 0x10
	apds9250RegDataRed        uint8 = 0x13
	apds9250RegIntCfg         uint8 = 0x19
	apds9250RegIntPersistence uint8 = 0x1A
	apds9250RegThresUp        uint8 = 0x21
	apds9250RegThresLow       uint8 = 0x24
	apds9250RegThresVar       uint8 = 0x27

	apds9250CtrlSwReset uint8 = 0x10
	apds9250CtrlCSMode  uint8 = 0x04
	apds9250CtrlLSEn    uint8 = 0x02

	apds9250IntEn      uint8 = 0x04
	apds9250IntVarMode uint8 = 0x08
	apds9250IntSelMask uint8 = 0x30
)

// APDS9250I2CAddress is the fixed 7-bit I²C address.
const APDS9250I2CAddress uint8 = 0x52

// Interrupt sources for EnableInterrupt.
const (
	// APDS9250SourceThreshold: the selected channel is outside the threshold window.
	APDS9250SourceThreshold uint8 = 0x01
	// APDS9250SourceVariation: the selected channel changed by more than the variance threshold.
	APDS9250SourceVariation uint8 = 0x02
)

// MAIN_STATUS bits.
const (
	// APDS9250StatusPowerOn is set after a power-up event.
	APDS9250StatusPowerOn uint8 = 0x20
	// APDS9250StatusInterrupt is set when the interrupt condition was fulfilled.
	APDS9250StatusInterrupt uint8 = 0x10
	// APDS9250StatusDataReady is set when a new result is available.
	APDS9250StatusDataReady uint8 = 0x08
)

// Interrupt channels for SetInterruptChannel.
const (
	APDS9250ChannelIR    uint8 = 0
	APDS9250ChannelGreen uint8 = 1
	APDS9250ChannelRed   uint8 = 2
	APDS9250ChannelBlue  uint8 = 3
)

// apds9250StartWait is the wait after enabling the sensor so the support
// blocks settle; apds9250PollStep is the step while waiting for LS_DATA. Tests set both to 0.
var (
	apds9250StartWait = 5 * time.Millisecond
	apds9250PollStep  = 5 * time.Millisecond
)

var (
	apds9250ResolutionBits = [6]uint8{20, 19, 18, 17, 16, 13}
	apds9250IntegrationS   = [6]float32{0.4, 0.2, 0.1, 0.05, 0.025, 0.003125}
	apds9250RateMs         = [8]uint16{25, 50, 100, 200, 500, 1000, 2000, 2000}
	apds9250Gains          = [5]uint8{1, 3, 6, 9, 18}
)

// ErrAPDS9250NotFound is returned when PART_ID does not identify an APDS-9250.
var ErrAPDS9250NotFound = errors.New("apds9250: PART_ID mismatch")

// ErrAPDS9250Range is returned for an argument outside the supported values; nothing is written.
var ErrAPDS9250Range = errors.New("apds9250: argument out of range")

// APDS9250Counts holds the four channel counts of one conversion (unsigned, up to 20 bit).
type APDS9250Counts struct {
	Red, Green, Blue, IR uint32
}

func apds9250Count(b []byte) uint32 {
	return uint32(b[0]) | uint32(b[1])<<8 | uint32(b[2]&0x0F)<<16
}

// APDS9250Minimal is the minimal APDS-9250 interface: the four channel counts
// of one conversion.
//
// The constructor reads PART_ID (the upper nibble must be 0xB), writes the
// defaults (18 bit, 100 ms, gain x3, interrupt off) and sets LS_EN and CS_MODE.
// Reading MAIN_STATUS clears its flags, so the driver keeps the interrupt and
// power-on bits it has seen until PollInterrupt / Status hands them out.
type APDS9250Minimal struct {
	conn         connection.RegisterConnection
	pending      uint8
	integrationS float32
}

// NewAPDS9250Minimal creates an APDS9250Minimal and starts the sensor.
// It returns ErrAPDS9250NotFound if PART_ID does not match.
func NewAPDS9250Minimal(conn connection.RegisterConnection) (*APDS9250Minimal, error) {
	d := &APDS9250Minimal{conn: conn, integrationS: 0.1}
	part, err := d.readReg(apds9250RegPartID)
	if err != nil {
		return nil, err
	}
	if part&0xF0 != 0xB0 {
		return nil, ErrAPDS9250NotFound
	}
	for _, w := range [][2]uint8{
		{apds9250RegMeasRate, 0x22}, {apds9250RegGain, 0x01}, {apds9250RegIntCfg, 0x10},
		{apds9250RegMainCtrl, apds9250CtrlLSEn | apds9250CtrlCSMode},
	} {
		if err := d.writeReg(w[0], w[1]); err != nil {
			return nil, err
		}
	}
	if apds9250StartWait > 0 {
		time.Sleep(apds9250StartWait)
	}
	return d, nil
}

func (d *APDS9250Minimal) readReg(reg uint8) (uint8, error) {
	buf, err := d.conn.ReadReg(uint32(reg), 1)
	if err != nil {
		return 0, err
	}
	return buf[0], nil
}

func (d *APDS9250Minimal) writeReg(reg, value uint8) error {
	return d.conn.WriteReg(uint32(reg), []byte{value})
}

func (d *APDS9250Minimal) updateReg(reg, setBits, clearBits uint8) error {
	v, err := d.readReg(reg)
	if err != nil {
		return err
	}
	return d.writeReg(reg, (v&^clearBits)|setBits)
}

func (d *APDS9250Minimal) readStatus() (uint8, error) {
	v, err := d.readReg(apds9250RegMainStatus)
	if err != nil {
		return 0, err
	}
	d.pending |= v & (APDS9250StatusPowerOn | APDS9250StatusInterrupt)
	return v, nil
}

func (d *APDS9250Minimal) readBlock() (APDS9250Counts, error) {
	raw, err := d.conn.ReadReg(uint32(apds9250RegDataIR), 12)
	if err != nil {
		return APDS9250Counts{}, err
	}
	return APDS9250Counts{
		IR: apds9250Count(raw[0:3]), Green: apds9250Count(raw[3:6]),
		Blue: apds9250Count(raw[6:9]), Red: apds9250Count(raw[9:12]),
	}, nil
}

func (d *APDS9250Minimal) readChannel(reg uint8) (uint32, error) {
	raw, err := d.conn.ReadReg(uint32(reg), 3)
	if err != nil {
		return 0, err
	}
	return apds9250Count(raw), nil
}

// Read reads all four channels from one conversion. It waits for a new result
// (at most one integration time plus 50 ms), then block-reads registers 0x0A-0x15.
func (d *APDS9250Minimal) Read() (APDS9250Counts, error) {
	limit := time.Duration((d.integrationS + 0.05) * float32(time.Second))
	step := apds9250PollStep
	if step <= 0 {
		step = time.Millisecond
	}
	for waited := time.Duration(0); waited <= limit; waited += step {
		st, err := d.readStatus()
		if err != nil {
			return APDS9250Counts{}, err
		}
		if st&APDS9250StatusDataReady != 0 {
			break
		}
		if apds9250PollStep > 0 {
			time.Sleep(apds9250PollStep)
		}
	}
	return d.readBlock()
}

// APDS9250Full extends APDS9250Minimal with single-channel reads, mode,
// resolution, rate and gain configuration, thresholds, variation and
// persistence, and interrupt delivery.
type APDS9250Full struct {
	*APDS9250Minimal

	mu          sync.Mutex
	callback    func(status uint8)
	unsubscribe func()
	pollPin     *connection.PollingInputPin
}

// NewAPDS9250Full creates an APDS9250Full with the same initialization as NewAPDS9250Minimal.
func NewAPDS9250Full(conn connection.RegisterConnection) (*APDS9250Full, error) {
	m, err := NewAPDS9250Minimal(conn)
	if err != nil {
		return nil, err
	}
	return &APDS9250Full{APDS9250Minimal: m}, nil
}

// ReadRed reads the red channel (3-byte read).
func (d *APDS9250Full) ReadRed() (uint32, error) { return d.readChannel(apds9250RegDataRed) }

// ReadGreen reads the green / ambient-light channel.
func (d *APDS9250Full) ReadGreen() (uint32, error) { return d.readChannel(apds9250RegDataGreen) }

// ReadBlue reads the blue channel.
func (d *APDS9250Full) ReadBlue() (uint32, error) { return d.readChannel(apds9250RegDataBlue) }

// ReadIR reads the infrared channel.
func (d *APDS9250Full) ReadIR() (uint32, error) { return d.readChannel(apds9250RegDataIR) }

// ReadChannels block-reads all four channels without waiting (call after DataReady).
func (d *APDS9250Full) ReadChannels() (APDS9250Counts, error) { return d.readBlock() }

// DataReady reports whether a new result is available. It reads MAIN_STATUS,
// which clears its flags; the interrupt and power-on bits are kept for
// PollInterrupt and Status.
func (d *APDS9250Full) DataReady() (bool, error) {
	v, err := d.readStatus()
	if err != nil {
		return false, err
	}
	return v&APDS9250StatusDataReady != 0, nil
}

// SetMode selects colour-sensing mode (true: red, green, blue, IR) or ALS/IR
// mode (false: green and IR only).
func (d *APDS9250Full) SetMode(colour bool) error {
	var set uint8
	if colour {
		set = apds9250CtrlCSMode
	}
	return d.updateReg(apds9250RegMainCtrl, set, apds9250CtrlCSMode)
}

// Enable activates the light sensor (LS_EN = 1).
func (d *APDS9250Full) Enable() error { return d.updateReg(apds9250RegMainCtrl, apds9250CtrlLSEn, 0) }

// Disable puts the light sensor in standby (LS_EN = 0).
func (d *APDS9250Full) Disable() error { return d.updateReg(apds9250RegMainCtrl, 0, apds9250CtrlLSEn) }

// SetResolution sets the ADC resolution (13, 16, 17, 18, 19 or 20 bit);
// restarts the measurement. Any other value returns ErrAPDS9250Range.
func (d *APDS9250Full) SetResolution(bits uint8) error {
	for code, b := range apds9250ResolutionBits {
		if b == bits {
			if err := d.updateReg(apds9250RegMeasRate, uint8(code)<<4, 0x70); err != nil {
				return err
			}
			d.integrationS = apds9250IntegrationS[code]
			return nil
		}
	}
	return ErrAPDS9250Range
}

// SetMeasurementRate sets the measurement rate in ms (25, 50, 100, 200, 500,
// 1000 or 2000). Any other value returns ErrAPDS9250Range.
func (d *APDS9250Full) SetMeasurementRate(milliseconds uint16) error {
	for code := 0; code < 7; code++ {
		if apds9250RateMs[code] == milliseconds {
			return d.updateReg(apds9250RegMeasRate, uint8(code), 0x07)
		}
	}
	return ErrAPDS9250Range
}

// SetGain sets the analog gain (1, 3, 6, 9 or 18) for all channels; restarts
// the measurement. Any other value returns ErrAPDS9250Range.
func (d *APDS9250Full) SetGain(gain uint8) error {
	for code, g := range apds9250Gains {
		if g == gain {
			return d.writeReg(apds9250RegGain, uint8(code))
		}
	}
	return ErrAPDS9250Range
}

// GetResolution reads the ADC resolution in bits.
func (d *APDS9250Full) GetResolution() (uint8, error) {
	v, err := d.readReg(apds9250RegMeasRate)
	if err != nil {
		return 0, err
	}
	code := (v >> 4) & 0x07
	if code < 6 {
		return apds9250ResolutionBits[code], nil
	}
	return 18, nil
}

// GetMeasurementRate reads the measurement rate in ms.
func (d *APDS9250Full) GetMeasurementRate() (uint16, error) {
	v, err := d.readReg(apds9250RegMeasRate)
	if err != nil {
		return 0, err
	}
	return apds9250RateMs[v&0x07], nil
}

// GetGain reads the analog gain (1, 3, 6, 9 or 18).
func (d *APDS9250Full) GetGain() (uint8, error) {
	v, err := d.readReg(apds9250RegGain)
	if err != nil {
		return 0, err
	}
	code := v & 0x07
	if code < 5 {
		return apds9250Gains[code], nil
	}
	return 18, nil
}

// IntegrationTime returns the integration time of the current resolution in seconds.
func (d *APDS9250Full) IntegrationTime() (float32, error) {
	v, err := d.readReg(apds9250RegMeasRate)
	if err != nil {
		return 0, err
	}
	code := (v >> 4) & 0x07
	if code < 6 {
		return apds9250IntegrationS[code], nil
	}
	return 0.1, nil
}

// Normalized converts a count to counts per gain-second: counts / (gain · integration time).
func (d *APDS9250Full) Normalized(counts uint32) (float32, error) {
	gain, err := d.GetGain()
	if err != nil {
		return 0, err
	}
	t, err := d.IntegrationTime()
	if err != nil {
		return 0, err
	}
	return float32(counts) / (float32(gain) * t), nil
}

// PartID reads PART_ID as (part, revision) — the two nibbles (0xB, 0x5).
func (d *APDS9250Full) PartID() (part, revision uint8, err error) {
	v, err := d.readReg(apds9250RegPartID)
	if err != nil {
		return 0, 0, err
	}
	return v >> 4, v & 0x0F, nil
}

// Status reads MAIN_STATUS (clearing the flags on the chip) with the
// interrupt and power-on bits seen since the last call merged in.
func (d *APDS9250Full) Status() (uint8, error) {
	v, err := d.readStatus()
	if err != nil {
		return 0, err
	}
	v |= d.pending
	d.pending = 0
	return v, nil
}

// SoftReset triggers a software reset (all registers to defaults, sensor in standby).
func (d *APDS9250Full) SoftReset() error {
	if err := d.writeReg(apds9250RegMainCtrl, apds9250CtrlSwReset); err != nil {
		return err
	}
	if apds9250StartWait > 0 {
		time.Sleep(10 * time.Millisecond)
	}
	d.pending = 0
	d.integrationS = 0.1
	return nil
}

// SetThresholds sets the 20-bit threshold window (low ≤ high ≤ 1048575);
// otherwise ErrAPDS9250Range.
func (d *APDS9250Full) SetThresholds(low, high uint32) error {
	if low > 0xFFFFF || high > 0xFFFFF || low > high {
		return ErrAPDS9250Range
	}
	lo := []byte{byte(low), byte(low >> 8), byte(low>>16) & 0x0F}
	hi := []byte{byte(high), byte(high >> 8), byte(high>>16) & 0x0F}
	if err := d.conn.WriteReg(uint32(apds9250RegThresLow), lo); err != nil {
		return err
	}
	return d.conn.WriteReg(uint32(apds9250RegThresUp), hi)
}

// GetThresholds reads the threshold window.
func (d *APDS9250Full) GetThresholds() (low, high uint32, err error) {
	lo, err := d.conn.ReadReg(uint32(apds9250RegThresLow), 3)
	if err != nil {
		return 0, 0, err
	}
	hi, err := d.conn.ReadReg(uint32(apds9250RegThresUp), 3)
	if err != nil {
		return 0, 0, err
	}
	return apds9250Count(lo), apds9250Count(hi), nil
}

// SetInterruptChannel selects the channel the interrupt monitors
// (APDS9250ChannelIR / Green / Red / Blue); otherwise ErrAPDS9250Range.
func (d *APDS9250Full) SetInterruptChannel(channel uint8) error {
	if channel > APDS9250ChannelBlue {
		return ErrAPDS9250Range
	}
	return d.updateReg(apds9250RegIntCfg, channel<<4, apds9250IntSelMask)
}

// SetPersistence sets the number of consecutive events (1–16) before the interrupt asserts.
func (d *APDS9250Full) SetPersistence(count uint8) error {
	if count < 1 || count > 16 {
		return ErrAPDS9250Range
	}
	return d.writeReg(apds9250RegIntPersistence, (count-1)<<4)
}

// SetVariance sets the variation-interrupt threshold in counts (8, 16, 32, … 1024).
func (d *APDS9250Full) SetVariance(counts uint16) error {
	for n := uint8(0); n < 8; n++ {
		if counts == uint16(8)<<n {
			return d.writeReg(apds9250RegThresVar, n)
		}
	}
	return ErrAPDS9250Range
}

// EnableInterrupt selects threshold (APDS9250SourceThreshold) or variation
// (APDS9250SourceVariation) mode and enables the interrupt.
func (d *APDS9250Full) EnableInterrupt(source uint8) error {
	var mode uint8
	if source == APDS9250SourceVariation {
		mode = apds9250IntVarMode
	}
	return d.updateReg(apds9250RegIntCfg, apds9250IntEn|mode, apds9250IntEn|apds9250IntVarMode)
}

// DisableInterrupt disables the interrupt (clears LS_INT_EN; both sources share the bit).
func (d *APDS9250Full) DisableInterrupt() error {
	return d.updateReg(apds9250RegIntCfg, 0, apds9250IntEn)
}

// PollInterrupt reads MAIN_STATUS (clearing the flags) and returns it;
// APDS9250StatusInterrupt is set if the condition was fulfilled.
func (d *APDS9250Full) PollInterrupt() (uint8, error) { return d.Status() }

// OnInterrupt subscribes callback to INT assertions (falling edge). The
// callback receives the MAIN_STATUS byte after the driver has read (and
// thereby cleared) it. It uses the connection's InputPin if wired, otherwise a
// polling goroutine.
func (d *APDS9250Full) OnInterrupt(callback func(status uint8)) error {
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
func (d *APDS9250Full) OffInterrupt() error {
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
// MAIN_STATUS and dispatches the callback if the interrupt flag is set.
func (d *APDS9250Full) handleEdge() {
	status, err := d.PollInterrupt()
	if err != nil || status&APDS9250StatusInterrupt == 0 {
		return
	}
	d.mu.Lock()
	cb := d.callback
	d.mu.Unlock()
	if cb != nil {
		cb(status)
	}
}
