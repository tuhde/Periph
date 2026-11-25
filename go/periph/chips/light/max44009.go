package light

import (
	"errors"
	"sync"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// MAX44009 registers.
const (
	max44009RegIntStatus uint8 = 0x00
	max44009RegIntEnable uint8 = 0x01
	max44009RegConfig    uint8 = 0x02
	max44009RegLuxHigh   uint8 = 0x03
	max44009RegLuxLow    uint8 = 0x04
	max44009RegUpper     uint8 = 0x05
	max44009RegLower     uint8 = 0x06
	max44009RegTimer     uint8 = 0x07

	max44009CfgCont    uint8 = 0x80
	max44009CfgManual  uint8 = 0x40
	max44009CfgCDR     uint8 = 0x08
	max44009CfgTimMask uint8 = 0x07

	max44009LuxPerLSB float32 = 0.045
	max44009MaxLux    float32 = 188006.4
)

// MAX44009I2CAddress is the 7-bit I²C address with A0 = GND.
const MAX44009I2CAddress uint8 = 0x4A

// MAX44009I2CAddressA0High is the 7-bit I²C address with A0 = VCC.
const MAX44009I2CAddressA0High uint8 = 0x4B

// MAX44009SourceThreshold is status bit 0: the light level was outside the
// threshold window for longer than the threshold delay.
const MAX44009SourceThreshold uint8 = 0x01

// max44009InitWait is the wait after the initialization writes so the first
// Lux() returns a real measurement (one 800 ms cycle plus the 100 ms
// integration time). Tests set it to 0.
var max44009InitWait = 900 * time.Millisecond

var max44009IntegrationMs = [8]float32{800, 400, 200, 100, 50, 25, 12.5, 6.25}

// ErrMAX44009Thresholds is returned by SetThresholds when lowLux > highLux.
var ErrMAX44009Thresholds = errors.New("max44009: lowLux must not exceed highLux")

func max44009Pow2(exponent uint8) float32 { return float32(uint32(1) << exponent) }

func max44009LuxFrom(exponent, mantissa uint8) float32 {
	if exponent >= 15 {
		return max44009MaxLux
	}
	return max44009Pow2(exponent) * float32(mantissa) * max44009LuxPerLSB
}

func max44009Round(x float32) int {
	if x >= 0 {
		return int(x + 0.5)
	}
	return int(x - 0.5)
}

func max44009EncodeThreshold(lux float32) uint8 {
	if lux < 11.5 {
		m := max44009Round(lux / (16 * max44009LuxPerLSB))
		if m < 0 {
			m = 0
		}
		if m > 15 {
			m = 15
		}
		return uint8(m)
	}
	var exponent uint8 = 1
	var mantissa float32
	for ; exponent <= 14; exponent++ {
		mantissa = lux / (max44009Pow2(exponent) * max44009LuxPerLSB)
		if mantissa < 256 {
			break
		}
	}
	if exponent > 14 {
		exponent = 14
	}
	m := int(mantissa)
	if m < 128 {
		m = 128
	}
	if m > 255 {
		m = 255
	}
	code := exponent<<4 | uint8(m>>4)
	if code > 0xEF {
		code = 0xEF
	}
	return code
}

func max44009DecodeThreshold(code uint8, upper bool) float32 {
	exponent := code >> 4
	nibble := code & 0x0F
	var low uint8
	if upper {
		low = 0x0F
	}
	return max44009Pow2(exponent) * float32(uint16(nibble)<<4|uint16(low)) * max44009LuxPerLSB
}

// MAX44009Minimal reads the ambient illuminance of a MAX44009 ultra-low-power
// ambient light sensor (Maxim / Analog Devices). The illuminance is a 12-bit
// floating-point value (4-bit exponent, 8-bit mantissa): lux = 2^E · M · 0.045,
// from 0.045 lx to 188 006.4 lx.
//
// The constructor runs the initialization sequence (interrupt off, CONFIG 0x03,
// thresholds fully open, timer 0xFF, INT_STATUS read once) and waits 900 ms so
// the first Lux() returns a real measurement. A register read ends with a STOP,
// so the lux registers are read as high, low, high and repeated once if the
// high byte changed in between.
type MAX44009Minimal struct {
	conn         connection.RegisterConnection
	lastExponent uint8
}

// NewMAX44009Minimal creates a MAX44009Minimal and runs the initialization
// sequence.
func NewMAX44009Minimal(conn connection.RegisterConnection) (*MAX44009Minimal, error) {
	d := &MAX44009Minimal{conn: conn}
	for _, w := range [][2]uint8{
		{max44009RegIntEnable, 0x00}, {max44009RegConfig, 0x03},
		{max44009RegUpper, 0xFF}, {max44009RegLower, 0x00}, {max44009RegTimer, 0xFF},
	} {
		if err := d.writeReg(w[0], w[1]); err != nil {
			return nil, err
		}
	}
	if _, err := d.readReg(max44009RegIntStatus); err != nil {
		return nil, err
	}
	if max44009InitWait > 0 {
		time.Sleep(max44009InitWait)
	}
	return d, nil
}

func (d *MAX44009Minimal) readReg(reg uint8) (uint8, error) {
	buf, err := d.conn.ReadReg(uint32(reg), 1)
	if err != nil {
		return 0, err
	}
	return buf[0], nil
}

func (d *MAX44009Minimal) writeReg(reg, value uint8) error {
	return d.conn.WriteReg(uint32(reg), []byte{value})
}

func (d *MAX44009Minimal) readLuxRegs() (exponent, mantissa uint8, err error) {
	high, err := d.readReg(max44009RegLuxHigh)
	if err != nil {
		return 0, 0, err
	}
	low, err := d.readReg(max44009RegLuxLow)
	if err != nil {
		return 0, 0, err
	}
	high2, err := d.readReg(max44009RegLuxHigh)
	if err != nil {
		return 0, 0, err
	}
	if high2 != high { // a new conversion arrived: read once more
		high = high2
		if low, err = d.readReg(max44009RegLuxLow); err != nil {
			return 0, 0, err
		}
	}
	exponent = high >> 4
	mantissa = (high&0x0F)<<4 | low&0x0F
	d.lastExponent = exponent
	return exponent, mantissa, nil
}

// Lux reads the ambient illuminance in lux (2^E · M · 0.045); on overrange
// (E = 15) it returns the maximum 188006.4 lx.
func (d *MAX44009Minimal) Lux() (float32, error) {
	e, m, err := d.readLuxRegs()
	if err != nil {
		return 0, err
	}
	return max44009LuxFrom(e, m), nil
}

// MAX44009Config is the decoded CONFIG register.
type MAX44009Config struct {
	Continuous    bool
	Manual        bool
	DivideBy8     bool
	IntegrationMs float32
}

// MAX44009Full extends MAX44009Minimal with raw readings, measurement mode and
// range configuration, interrupt thresholds and delay, and interrupt delivery.
type MAX44009Full struct {
	*MAX44009Minimal

	mu          sync.Mutex
	callback    func(status uint8)
	unsubscribe func()
	pollPin     *connection.PollingInputPin
}

// NewMAX44009Full creates a MAX44009Full with the same initialization as
// NewMAX44009Minimal.
func NewMAX44009Full(conn connection.RegisterConnection) (*MAX44009Full, error) {
	m, err := NewMAX44009Minimal(conn)
	if err != nil {
		return nil, err
	}
	return &MAX44009Full{MAX44009Minimal: m}, nil
}

// ReadRaw reads the lux registers as one consistent sample: the exponent field
// (0–15), the 8-bit mantissa and whether the exponent field was 15 (overrange).
func (d *MAX44009Full) ReadRaw() (exponent, mantissa uint8, overrange bool, err error) {
	exponent, mantissa, err = d.readLuxRegs()
	return exponent, mantissa, exponent >= 15, err
}

// IsOverrange reports whether the last reading was overrange (E = 15).
func (d *MAX44009Full) IsOverrange() bool { return d.lastExponent >= 15 }

// Resolution returns the lux per mantissa LSB for the last reading, 2^E · 0.045.
func (d *MAX44009Full) Resolution() float32 {
	e := d.lastExponent
	if e > 14 {
		e = 14
	}
	return max44009Pow2(e) * max44009LuxPerLSB
}

func (d *MAX44009Full) updateConfig(setBits, clearBits uint8) error {
	cfg, err := d.readReg(max44009RegConfig)
	if err != nil {
		return err
	}
	return d.writeReg(max44009RegConfig, cfg&^clearBits|setBits)
}

// SetContinuous selects continuous mode (true: a new measurement as soon as one
// finishes) or low-power mode (false: one measurement every 800 ms).
func (d *MAX44009Full) SetContinuous(continuous bool) error {
	if continuous {
		return d.updateConfig(max44009CfgCont, max44009CfgCont)
	}
	return d.updateConfig(0, max44009CfgCont)
}

// SetAutomatic selects automatic mode (the chip chooses current divider and
// integration time).
func (d *MAX44009Full) SetAutomatic() error { return d.updateConfig(0, max44009CfgManual) }

// SetManual selects manual mode with the integration time nearest to
// integrationMs (800, 400, 200, 100, 50, 25, 12.5 or 6.25 ms) and an optional
// current divider of 8.
func (d *MAX44009Full) SetManual(integrationMs float32, divideBy8 bool) error {
	best := 0
	bestErr := float32(math32Abs(max44009IntegrationMs[0] - integrationMs))
	for tim := 1; tim < 8; tim++ {
		e := math32Abs(max44009IntegrationMs[tim] - integrationMs)
		if e < bestErr {
			best, bestErr = tim, e
		}
	}
	cfg, err := d.readReg(max44009RegConfig)
	if err != nil {
		return err
	}
	cfg = cfg&max44009CfgCont | max44009CfgManual | uint8(best)
	if divideBy8 {
		cfg |= max44009CfgCDR
	}
	return d.writeReg(max44009RegConfig, cfg)
}

func math32Abs(x float32) float32 {
	if x < 0 {
		return -x
	}
	return x
}

// GetConfig reads the CONFIG register.
func (d *MAX44009Full) GetConfig() (MAX44009Config, error) {
	cfg, err := d.readReg(max44009RegConfig)
	if err != nil {
		return MAX44009Config{}, err
	}
	return MAX44009Config{
		Continuous:    cfg&max44009CfgCont != 0,
		Manual:        cfg&max44009CfgManual != 0,
		DivideBy8:     cfg&max44009CfgCDR != 0,
		IntegrationMs: max44009IntegrationMs[cfg&max44009CfgTimMask],
	}, nil
}

// SetThresholds sets the interrupt threshold window; it returns
// ErrMAX44009Thresholds (and writes nothing) if lowLux > highLux.
func (d *MAX44009Full) SetThresholds(lowLux, highLux float32) error {
	if lowLux > highLux {
		return ErrMAX44009Thresholds
	}
	if err := d.writeReg(max44009RegLower, max44009EncodeThreshold(lowLux)); err != nil {
		return err
	}
	return d.writeReg(max44009RegUpper, max44009EncodeThreshold(highLux))
}

// GetThresholds reads the interrupt threshold window; the upper threshold
// carries the implied low nibble 1111.
func (d *MAX44009Full) GetThresholds() (lowLux, highLux float32, err error) {
	low, err := d.readReg(max44009RegLower)
	if err != nil {
		return 0, 0, err
	}
	high, err := d.readReg(max44009RegUpper)
	if err != nil {
		return 0, 0, err
	}
	return max44009DecodeThreshold(low, false), max44009DecodeThreshold(high, true), nil
}

// SetThresholdDelay sets how long the light must stay outside the window
// before the interrupt (0–25.5 s in 0.1 s steps).
func (d *MAX44009Full) SetThresholdDelay(seconds float32) error {
	v := max44009Round(seconds / 0.1)
	if v < 0 {
		v = 0
	}
	if v > 255 {
		v = 255
	}
	return d.writeReg(max44009RegTimer, uint8(v))
}

// GetThresholdDelay reads the threshold delay in seconds.
func (d *MAX44009Full) GetThresholdDelay() (float32, error) {
	v, err := d.readReg(max44009RegTimer)
	if err != nil {
		return 0, err
	}
	return float32(v) * 0.1, nil
}

// EnableInterrupt enables the interrupt (INT_ENABLE = 1).
func (d *MAX44009Full) EnableInterrupt() error { return d.writeReg(max44009RegIntEnable, 0x01) }

// DisableInterrupt disables the interrupt (INT_ENABLE = 0; also clears a
// pending status).
func (d *MAX44009Full) DisableInterrupt() error { return d.writeReg(max44009RegIntEnable, 0x00) }

// PollInterrupt reads INT_STATUS (clearing it); bit 0 (MAX44009SourceThreshold)
// is set if the light level was outside the window for longer than the delay.
func (d *MAX44009Full) PollInterrupt() (uint8, error) {
	v, err := d.readReg(max44009RegIntStatus)
	if err != nil {
		return 0, err
	}
	return v & 0x01, nil
}

// OnInterrupt subscribes callback to INT assertions (falling edge). The
// callback receives the status after the driver has read (and thereby cleared)
// INT_STATUS. It uses the connection's InputPin if wired, otherwise a polling
// goroutine.
func (d *MAX44009Full) OnInterrupt(callback func(status uint8)) error {
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
func (d *MAX44009Full) OffInterrupt() error {
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
// INT_STATUS and dispatches the callback if an event is pending.
func (d *MAX44009Full) handleEdge() {
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
