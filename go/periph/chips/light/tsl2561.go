// TSL2561 — light-to-digital converter with two photodiode channels (TAOS / ams).
//
// Channel 0 responds to visible and infrared light, channel 1 mainly to
// infrared; the two 16-bit integrating ADC counts are combined with the
// datasheet's empirical formula into lux. Gain is 1× or 16×, the integration
// time 13.7 ms, 101 ms or 402 ms (or host-timed manual integration), and a
// channel-0 threshold window drives the open-drain INT pin. The I²C address is
// 0x29 (ADDR SEL = GND), 0x39 (floating) or 0x49 (VDD).
//
// Every register access starts with a command byte (0x80 | addr for a byte,
// 0xA0 | addr for a little-endian word); both channels are read in one 4-byte
// block read (0x9C) so the pair belongs to one integration.

package light

import (
	"errors"
	"fmt"
	"math"
	"sync"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

const (
	tsl2561Cmd       uint8  = 0x80
	tsl2561CmdClear  uint8  = 0xC0
	tsl2561CmdWord   uint8  = 0xA0
	tsl2561CmdBlock  uint8  = 0x9C
	tsl2561RegCtrl   uint8  = 0x0
	tsl2561RegTiming uint8  = 0x1
	tsl2561RegThLow  uint8  = 0x2
	tsl2561RegThHigh uint8  = 0x4
	tsl2561RegInt    uint8  = 0x6
	tsl2561RegID     uint8  = 0xA
	tsl2561PowerOn   uint8  = 0x03
	tsl2561IntegMan  uint8  = 0x03
	tsl2561AutoMin   uint16 = 100
)

// TSL2561I2CAddress is the 7-bit I²C address with ADDR SEL floating (default of most breakout boards).
const TSL2561I2CAddress uint8 = 0x39

// TSL2561I2CAddressGND is the 7-bit I²C address with ADDR SEL = GND.
const TSL2561I2CAddressGND uint8 = 0x29

// TSL2561I2CAddressVDD is the 7-bit I²C address with ADDR SEL = VDD.
const TSL2561I2CAddressVDD uint8 = 0x49

// Per INTEG code 0..2: nominal ms, scale, full-scale count, settle wait (one integration + ±6 % + margin).
var (
	tsl2561IntegMs    = [3]uint16{14, 101, 402}
	tsl2561IntegScale = [3]float64{0.034, 0.252, 1.0}
	tsl2561FullScale  = [3]uint16{5047, 37177, 65535}
	tsl2561SettleMs   = [3]int{20, 120, 450}
	tsl2561Ranges     = [4][2]uint8{{16, 2}, {1, 2}, {1, 1}, {1, 0}} // (gain, INTEG), most to least sensitive
	tsl2561CoeffTFNCL = [4][3]float64{{0.50, 0.0304, 0.062}, {0.61, 0.0224, 0.031}, {0.80, 0.0128, 0.0153}, {1.30, 0.00146, 0.00112}}
	tsl2561CoeffCS    = [4][3]float64{{0.52, 0.0315, 0.0593}, {0.65, 0.0229, 0.0291}, {0.80, 0.0157, 0.0180}, {1.30, 0.00338, 0.00260}}
	tsl2561WaitScale  = 1.0 // multiplier for every integration wait; unit tests set it to 0
)

// ErrTSL2561Init is returned when CONTROL does not read back as powered up.
var ErrTSL2561Init = errors.New("tsl2561: CONTROL did not read back as powered up")

// ErrTSL2561Argument is returned for an out-of-range argument.
var ErrTSL2561Argument = errors.New("tsl2561: argument out of range")

// TSL2561SaturatedError is returned when a channel count is at or above the
// full-scale limit of the integration time.
type TSL2561SaturatedError struct {
	Ch0, Ch1 uint16
}

func (e *TSL2561SaturatedError) Error() string {
	return fmt.Sprintf("tsl2561: channel saturated (ch0=%d, ch1=%d)", e.Ch0, e.Ch1)
}

func tsl2561Sleep(ms int) {
	if d := time.Duration(float64(ms) * tsl2561WaitScale * float64(time.Millisecond)); d > 0 {
		time.Sleep(d)
	}
}

// TSL2561CalculateLux converts raw channel counts into lux with the datasheet's
// empirical formula. gain is 1 or 16, integrationMs 14, 101 or 402 (any other
// value is treated as 402 ms) and pkg "T", "FN", "CL" or "CS". It returns 0
// for ch0 = 0 or a ratio above 1.30.
func TSL2561CalculateLux(ch0, ch1 uint16, gain uint8, integrationMs uint16, pkg string) float32 {
	code := 2
	switch integrationMs {
	case 14:
		code = 0
	case 101:
		code = 1
	}
	g := 1.0
	if gain == 16 {
		g = 16
	}
	factor := (16 / g) / tsl2561IntegScale[code]
	ch0n := float64(ch0) * factor
	ch1n := float64(ch1) * factor
	if ch0n <= 0 {
		return 0
	}
	r := ch1n / ch0n
	c := &tsl2561CoeffTFNCL
	if pkg == "CS" {
		c = &tsl2561CoeffCS
	}
	var lux float64
	switch {
	case r <= c[0][0]:
		lux = c[0][1]*ch0n - c[0][2]*ch0n*math.Pow(r, 1.4)
	case r <= c[1][0]:
		lux = c[1][1]*ch0n - c[1][2]*ch1n
	case r <= c[2][0]:
		lux = c[2][1]*ch0n - c[2][2]*ch1n
	case r <= c[3][0]:
		lux = c[3][1]*ch0n - c[3][2]*ch1n
	}
	if lux < 0 {
		lux = 0
	}
	return float32(lux)
}

// TSL2561Minimal is the minimal TSL2561 driver: it powers the chip up with 1×
// gain and 402 ms integration and reads the illuminance in lux with the
// T/FN/CL package coefficients.
type TSL2561Minimal struct {
	conn  connection.RegisterConnection
	gain  uint8
	integ uint8
	pkg   string
}

// NewTSL2561Minimal runs the initialization sequence: CONTROL ← 0x03, read it
// back (ErrTSL2561Init if it is not 0x03), TIMING ← 0x02 (1×, 402 ms) and a
// 450 ms wait for the first integration.
func NewTSL2561Minimal(conn connection.RegisterConnection) (*TSL2561Minimal, error) {
	d := &TSL2561Minimal{conn: conn, gain: 1, integ: 2, pkg: "T"}
	if err := d.writeByte(tsl2561RegCtrl, tsl2561PowerOn); err != nil {
		return nil, err
	}
	v, err := d.readByte(tsl2561RegCtrl)
	if err != nil {
		return nil, err
	}
	if v&0x03 != tsl2561PowerOn {
		return nil, ErrTSL2561Init
	}
	if err := d.writeTiming(false); err != nil {
		return nil, err
	}
	tsl2561Sleep(d.settleMs())
	return d, nil
}

func (d *TSL2561Minimal) readByte(reg uint8) (uint8, error) {
	buf, err := d.conn.ReadReg(uint32(tsl2561Cmd|reg), 1)
	if err != nil {
		return 0, err
	}
	return buf[0], nil
}

func (d *TSL2561Minimal) writeByte(reg, value uint8) error {
	return d.conn.WriteReg(uint32(tsl2561Cmd|reg), []byte{value})
}

func (d *TSL2561Minimal) readWord(reg uint8) (uint16, error) {
	buf, err := d.conn.ReadReg(uint32(tsl2561CmdWord|reg), 2)
	if err != nil {
		return 0, err
	}
	return uint16(buf[0]) | uint16(buf[1])<<8, nil
}

func (d *TSL2561Minimal) writeWord(reg uint8, value uint16) error {
	return d.conn.WriteReg(uint32(tsl2561CmdWord|reg), []byte{byte(value), byte(value >> 8)})
}

func (d *TSL2561Minimal) writeTiming(manual bool) error {
	v := d.integ
	if d.gain == 16 {
		v |= 0x10
	}
	if manual {
		v |= 0x08
	}
	return d.writeByte(tsl2561RegTiming, v)
}

func (d *TSL2561Minimal) readChannels() (ch0, ch1 uint16, err error) {
	buf, err := d.conn.ReadReg(uint32(tsl2561CmdBlock), 4)
	if err != nil {
		return 0, 0, err
	}
	return uint16(buf[0]) | uint16(buf[1])<<8, uint16(buf[2]) | uint16(buf[3])<<8, nil
}

func (d *TSL2561Minimal) fullScale() uint16 {
	if d.integ < 3 {
		return tsl2561FullScale[d.integ]
	}
	return 65535
}

func (d *TSL2561Minimal) integrationMs() uint16 {
	if d.integ < 3 {
		return tsl2561IntegMs[d.integ]
	}
	return 0
}

func (d *TSL2561Minimal) settleMs() int {
	if d.integ < 3 {
		return tsl2561SettleMs[d.integ]
	}
	return 0
}

func (d *TSL2561Minimal) luxFrom(ch0, ch1 uint16) (float32, error) {
	limit := d.fullScale()
	if ch0 >= limit || ch1 >= limit {
		return 0, &TSL2561SaturatedError{Ch0: ch0, Ch1: ch1}
	}
	return TSL2561CalculateLux(ch0, ch1, d.gain, d.integrationMs(), d.pkg), nil
}

// ReadLux reads the illuminance in lux (T/FN/CL coefficients unless changed);
// 0 for a channel ratio above 1.30. A saturated channel returns a
// *TSL2561SaturatedError.
func (d *TSL2561Minimal) ReadLux() (float32, error) {
	ch0, ch1, err := d.readChannels()
	if err != nil {
		return 0, err
	}
	return d.luxFrom(ch0, ch1)
}

// TSL2561Full extends TSL2561Minimal with raw counts, gain and integration
// time, package coefficients, auto-ranging, power control, manual integration,
// the interrupt window and interrupt delivery.
type TSL2561Full struct {
	*TSL2561Minimal

	mu          sync.Mutex
	callback    func(ch0 uint16)
	unsubscribe func()
	pollPin     *connection.PollingInputPin
}

// NewTSL2561Full creates a TSL2561Full with the same initialization as NewTSL2561Minimal.
func NewTSL2561Full(conn connection.RegisterConnection) (*TSL2561Full, error) {
	m, err := NewTSL2561Minimal(conn)
	if err != nil {
		return nil, err
	}
	return &TSL2561Full{TSL2561Minimal: m}, nil
}

// ReadRaw reads both ADC channels in one block read: channel 0 (visible + IR)
// and channel 1 (IR) counts.
func (d *TSL2561Full) ReadRaw() (ch0, ch1 uint16, err error) {
	return d.readChannels()
}

// SetGain sets the ADC gain (1 or 16; ErrTSL2561Argument otherwise). Wait one
// integration before trusting the next reading.
func (d *TSL2561Full) SetGain(gain uint8) error {
	if gain != 1 && gain != 16 {
		return ErrTSL2561Argument
	}
	d.gain = gain
	return d.writeTiming(false)
}

// Gain returns the current ADC gain (1 or 16).
func (d *TSL2561Full) Gain() uint8 { return d.gain }

// SetIntegrationTime sets the integration time in ms (14 = 13.7 ms, 101 or
// 402; ErrTSL2561Argument otherwise). Wait one integration before trusting the
// next reading.
func (d *TSL2561Full) SetIntegrationTime(ms uint16) error {
	switch ms {
	case 14:
		d.integ = 0
	case 101:
		d.integ = 1
	case 402:
		d.integ = 2
	default:
		return ErrTSL2561Argument
	}
	return d.writeTiming(false)
}

// IntegrationTime returns the current integration time in ms (14, 101 or 402;
// 0 in manual timing mode).
func (d *TSL2561Full) IntegrationTime() uint16 { return d.integrationMs() }

// SetPackage selects the lux coefficient set: "T", "FN", "CL" (shared
// coefficients) or "CS" (ErrTSL2561Argument otherwise).
func (d *TSL2561Full) SetPackage(pkg string) error {
	switch pkg {
	case "T", "FN", "CL", "CS":
		d.pkg = pkg
		return nil
	}
	return ErrTSL2561Argument
}

// ReadLuxRaw reads lux and both channel counts from one sample.
func (d *TSL2561Full) ReadLuxRaw() (lux float32, ch0, ch1 uint16, err error) {
	ch0, ch1, err = d.readChannels()
	if err != nil {
		return 0, 0, 0, err
	}
	lux, err = d.luxFrom(ch0, ch1)
	return lux, ch0, ch1, err
}

func (d *TSL2561Full) applyRange(index int) error {
	oldWait := d.settleMs()
	d.gain, d.integ = tsl2561Ranges[index][0], tsl2561Ranges[index][1]
	if err := d.writeTiming(false); err != nil {
		return err
	}
	tsl2561Sleep(oldWait + d.settleMs())
	return nil
}

// AutoRange picks gain and integration time for the current light level and
// reads lux. It steps through 16×/402 ms, 1×/402 ms, 1×/101 ms and 1×/13.7 ms
// until neither channel saturates and channel 0 is above 100 counts, waiting
// for a fresh integration after every change; the chosen setting stays active.
// A channel that still saturates at 1×/13.7 ms returns a *TSL2561SaturatedError.
func (d *TSL2561Full) AutoRange() (float32, error) {
	index := -1
	for i, r := range tsl2561Ranges {
		if r[0] == d.gain && r[1] == d.integ {
			index = i
		}
	}
	if index < 0 {
		index = 1
		if err := d.applyRange(index); err != nil {
			return 0, err
		}
	}
	for step := 0; step < 2*len(tsl2561Ranges); step++ {
		ch0, ch1, err := d.readChannels()
		if err != nil {
			return 0, err
		}
		limit := d.fullScale()
		saturated := ch0 >= limit || ch1 >= limit
		switch {
		case saturated && index < len(tsl2561Ranges)-1:
			index++
		case !saturated && ch0 < tsl2561AutoMin && index > 0:
			index--
		default:
			return d.luxFrom(ch0, ch1)
		}
		if err := d.applyRange(index); err != nil {
			return 0, err
		}
	}
	return d.ReadLux()
}

// PowerUp powers the chip up (CONTROL ← 0x03); integration restarts.
func (d *TSL2561Full) PowerUp() error { return d.writeByte(tsl2561RegCtrl, tsl2561PowerOn) }

// PowerDown powers the chip down (CONTROL ← 0x00); the registers keep their values.
func (d *TSL2561Full) PowerDown() error { return d.writeByte(tsl2561RegCtrl, 0x00) }

// IsPowered reports whether CONTROL reads 0x03.
func (d *TSL2561Full) IsPowered() (bool, error) {
	v, err := d.readByte(tsl2561RegCtrl)
	return v&0x03 == tsl2561PowerOn, err
}

// StartManual starts a host-timed integration (INTEG = 11, MANUAL = 1);
// ReadLux then uses the 402 ms scale.
func (d *TSL2561Full) StartManual() error {
	d.integ = tsl2561IntegMan
	return d.writeTiming(true)
}

// StopManual stops the host-timed integration (INTEG = 11, MANUAL = 0); read
// the counts next.
func (d *TSL2561Full) StopManual() error {
	d.integ = tsl2561IntegMan
	return d.writeTiming(false)
}

// SetThresholds sets the channel-0 interrupt window in counts (word writes of
// 0x2–0x3 and 0x4–0x5).
func (d *TSL2561Full) SetThresholds(low, high uint16) error {
	if err := d.writeWord(tsl2561RegThLow, low); err != nil {
		return err
	}
	return d.writeWord(tsl2561RegThHigh, high)
}

// Thresholds reads the channel-0 interrupt window in counts.
func (d *TSL2561Full) Thresholds() (low, high uint16, err error) {
	if low, err = d.readWord(tsl2561RegThLow); err != nil {
		return 0, 0, err
	}
	high, err = d.readWord(tsl2561RegThHigh)
	return low, high, err
}

// ConfigureInterrupt writes the INTERRUPT register: mode 0 disabled, 1 level,
// 2 SMBAlert; persist 0 every cycle, 1 any value outside, N (2–15) N
// consecutive cycles outside (ErrTSL2561Argument when out of range).
func (d *TSL2561Full) ConfigureInterrupt(mode, persist uint8) error {
	if mode > 2 || persist > 15 {
		return ErrTSL2561Argument
	}
	return d.writeByte(tsl2561RegInt, mode<<4|persist)
}

// ReadID reads the ID register; (id >> 4) == 1 for the TSL2561, bits 3:0 the revision.
func (d *TSL2561Full) ReadID() (uint8, error) { return d.readByte(tsl2561RegID) }

// PollInterrupt reports whether channel 0 is ≤ the low or > the high threshold
// (the persistence filter is not modelled and the chip's interrupt is not cleared).
func (d *TSL2561Full) PollInterrupt() (bool, error) {
	ch0, _, err := d.readChannels()
	if err != nil {
		return false, err
	}
	low, high, err := d.Thresholds()
	if err != nil {
		return false, err
	}
	return ch0 <= low || ch0 > high, nil
}

// ClearInterrupt sends the command byte 0xC0 (CMD + CLEAR) — releases a level interrupt.
func (d *TSL2561Full) ClearInterrupt() error { return d.conn.Write([]byte{tsl2561CmdClear}) }

// OnInterrupt subscribes callback, which receives the current channel-0 count.
// With the connection's InputPin (falling edge on INT) the driver clears the
// level interrupt after the callback; without one a polling goroutine calls
// PollInterrupt once per integration time.
func (d *TSL2561Full) OnInterrupt(callback func(ch0 uint16)) error {
	_ = d.OffInterrupt()
	d.mu.Lock()
	d.callback = callback
	pin := d.conn.IntPin()
	handler := d.handleEdge
	if pin == nil {
		period := time.Duration(d.integrationMs()) * time.Millisecond
		if period < 14*time.Millisecond {
			period = 14 * time.Millisecond
		}
		poll := connection.NewPollingInputPin(period)
		d.pollPin = poll
		pin = poll
		handler = d.handlePoll
	}
	d.mu.Unlock()

	unsub := pin.OnEdge(connection.Falling, handler)
	d.mu.Lock()
	d.unsubscribe = unsub
	d.mu.Unlock()
	return nil
}

// OffInterrupt unsubscribes interrupt delivery.
func (d *TSL2561Full) OffInterrupt() error {
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

func (d *TSL2561Full) currentCallback() func(uint16) {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.callback
}

// handleEdge runs on an INT edge: read channel 0, dispatch, clear the level interrupt.
func (d *TSL2561Full) handleEdge() {
	ch0, _, err := d.readChannels()
	if err != nil {
		return
	}
	if cb := d.currentCallback(); cb != nil {
		cb(ch0)
	}
	_ = d.ClearInterrupt()
}

// handlePoll runs on each polling tick: dispatch only if channel 0 is outside the window.
func (d *TSL2561Full) handlePoll() {
	outside, err := d.PollInterrupt()
	if err != nil || !outside {
		return
	}
	ch0, _, err := d.readChannels()
	if err != nil {
		return
	}
	if cb := d.currentCallback(); cb != nil {
		cb(ch0)
	}
}
