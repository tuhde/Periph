// Package accelerometer contains drivers for standalone accelerometers.
package accelerometer

import (
	"fmt"
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// BMA150 register addresses (0x00..0x15).
const (
	bma150RegChipID         uint8 = 0x00
	bma150RegVersion        uint8 = 0x01
	bma150RegAccXLSB        uint8 = 0x02
	bma150RegAccXMSB        uint8 = 0x03
	bma150RegAccYLSB        uint8 = 0x04
	bma150RegAccYMSB        uint8 = 0x05
	bma150RegAccZLSB        uint8 = 0x06
	bma150RegAccZMSB        uint8 = 0x07
	bma150RegTemp           uint8 = 0x08
	bma150RegStatus         uint8 = 0x09
	bma150RegCtrl           uint8 = 0x0A
	bma150RegIntCtrl        uint8 = 0x0B
	bma150RegLGThres        uint8 = 0x0C
	bma150RegLGDur          uint8 = 0x0D
	bma150RegHGThres        uint8 = 0x0E
	bma150RegHGDur          uint8 = 0x0F
	bma150RegAnyMotionThres uint8 = 0x10
	bma150RegHystDur        uint8 = 0x11
	bma150RegCustomer1      uint8 = 0x12
	bma150RegCustomer2      uint8 = 0x13
	bma150RegRangeBW        uint8 = 0x14
	bma150RegConfig         uint8 = 0x15
)

const (
	bma150ChipIDValue uint8 = 0x02
	bma150ChipIDMask  uint8 = 0x07
)

const (
	bma150Range2G uint8 = 0x00
	bma150Range4G uint8 = 0x08
	bma150Range8G uint8 = 0x10
)

const (
	bma150BW25   uint8 = 0x00
	bma150BW50   uint8 = 0x01
	bma150BW100  uint8 = 0x02
	bma150BW190  uint8 = 0x03
	bma150BW375  uint8 = 0x04
	bma150BW750  uint8 = 0x05
	bma150BW1500 uint8 = 0x06
)

const (
	bma150Scale2G float32 = 256.0
	bma150Scale4G float32 = 128.0
	bma150Scale8G float32 = 64.0
)

// Interrupt source bits — match the layout the driver uses when enabling /
// disabling via the LSB bytes of INT_CTRL.
const (
	// BMA150SourceLowG is the free-fall interrupt source.
	BMA150SourceLowG uint8 = 0x01
	// BMA150SourceHighG is the high-g (shock) interrupt source.
	BMA150SourceHighG uint8 = 0x02
	// BMA150SourceAnyMotion is the any-motion interrupt source.
	BMA150SourceAnyMotion uint8 = 0x04
	// BMA150SourceAlert is the alert-mode interrupt source.
	BMA150SourceAlert uint8 = 0x08
	// BMA150SourceNewData fires when all three new_data_X/Y/Z bits are set.
	BMA150SourceNewData uint8 = 0x10
)

// STATUS register latched bits.
const (
	BMA150StatusLGLatched uint8 = 0x08
	BMA150StatusHGLatched uint8 = 0x04
)

var bma150BandwidthTable = []struct {
	hz   uint16
	code uint8
}{
	{25, bma150BW25}, {50, bma150BW50}, {100, bma150BW100}, {190, bma150BW190},
	{375, bma150BW375}, {750, bma150BW750}, {1500, bma150BW1500},
}

func bma150EncodeOffset(offsetG float32) int32 {
	raw := int32(math.Round(float64(offsetG / 0.0156)))
	if raw > 127 {
		raw = 127
	}
	if raw < -128 {
		raw = -128
	}
	return raw
}

func bma150NearestBandwidth(bwHz uint16) uint8 {
	best := bma150BandwidthTable[0]
	bestDiff := absDiff(bwHz, bma150BandwidthTable[0].hz)
	for _, entry := range bma150BandwidthTable[1:] {
		if d := absDiff(bwHz, entry.hz); d < bestDiff {
			best = entry
			bestDiff = d
		}
	}
	return best.code
}

func absDiff(a, b uint16) uint16 {
	if a >= b {
		return a - b
	}
	return b - a
}

// BMA150Minimal is the BMA150 3-axis accelerometer — minimal interface.
//
// Reads X, Y, Z acceleration in *g* with sensible defaults; no
// configuration is required beyond the connection.
//
// Default configuration (baked in at construction):
// - Range ±2 *g* (256 LSB/g)
// - Bandwidth 100 Hz
// - Calibration bits 7:5 of RANGE_BW (0x14) preserved
// - shadow_dis = 0 (LSB-then-MSB ordering enforced)
type BMA150Minimal struct {
	conn    connection.RegisterConnection
	range_g uint8
}

// NewBMA150Minimal creates a BMA150Minimal, verifies CHIP_ID, and applies
// defaults.
func NewBMA150Minimal(conn connection.RegisterConnection) (*BMA150Minimal, error) {
	c := &BMA150Minimal{conn: conn, range_g: 2}
	if err := c.writeReg(bma150RegRangeBW, bma150Range2G|bma150BW100); err != nil {
		return nil, err
	}
	id, err := c.readReg8(bma150RegChipID)
	if err != nil {
		return nil, err
	}
	if (id & bma150ChipIDMask) != bma150ChipIDValue {
		return nil, fmt.Errorf("BMA150 CHIP_ID: expected 0x%02X, got 0x%02X",
			bma150ChipIDValue, id&bma150ChipIDMask)
	}
	time.Sleep(5 * time.Millisecond)
	return c, nil
}

func (c *BMA150Minimal) writeReg(reg, val uint8) error {
	return c.conn.WriteReg(uint32(reg), []byte{val})
}

func (c *BMA150Minimal) readReg8(reg uint8) (uint8, error) {
	b, err := c.conn.ReadReg(uint32(reg), 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

func (c *BMA150Minimal) readBurst(reg uint8, n int) ([]byte, error) {
	return c.conn.ReadReg(uint32(reg), n)
}

// Read returns 3-axis linear acceleration as (x, y, z) in *g*.
//
// Reads all six LSB-then-MSB data bytes (0x02..0x07) in one burst so the
// X, Y, Z samples are guaranteed to come from a single measurement.
func (c *BMA150Minimal) Read() (float32, float32, float32, error) {
	raw, err := c.readBurst(bma150RegAccXLSB, 6)
	if err != nil {
		return 0, 0, 0, err
	}
	rx := int16(connection.ToSigned(uint32(raw[1])<<2|uint32(raw[0]&0xC0)>>6, 10))
	ry := int16(connection.ToSigned(uint32(raw[3])<<2|uint32(raw[2]&0xC0)>>6, 10))
	rz := int16(connection.ToSigned(uint32(raw[5])<<2|uint32(raw[4]&0xC0)>>6, 10))
	var scale float32
	switch c.range_g {
	case 4:
		scale = bma150Scale4G
	case 8:
		scale = bma150Scale8G
	default:
		scale = bma150Scale2G
	}
	return float32(rx) / scale, float32(ry) / scale, float32(rz) / scale, nil
}

// BMA150Full extends BMA150Minimal with configuration, interrupt
// sources, low-g / high-g / any-motion / alert logic, sleep, soft reset,
// and self-test.
type BMA150Full struct {
	*BMA150Minimal
	enabledSources uint8
	sleeping       bool
}

// NewBMA150Full creates a BMA150Full.
func NewBMA150Full(conn connection.RegisterConnection) (*BMA150Full, error) {
	m, err := NewBMA150Minimal(conn)
	if err != nil {
		return nil, err
	}
	return &BMA150Full{BMA150Minimal: m}, nil
}

// SetRange sets the measurement range to ±2/±4/±8 *g*.
func (c *BMA150Full) SetRange(rangeG uint8) error {
	var rangeMask uint8
	switch rangeG {
	case 4:
		rangeMask = bma150Range4G
		c.range_g = 4
	case 8:
		rangeMask = bma150Range8G
		c.range_g = 8
	default:
		rangeMask = bma150Range2G
		c.range_g = 2
	}
	rb, err := c.readReg8(bma150RegRangeBW)
	if err != nil {
		return err
	}
	return c.writeReg(bma150RegRangeBW, (rb&0xE0)|rangeMask|(rb&0x07))
}

// SetBandwidth sets the digital low-pass bandwidth to the nearest supported value.
func (c *BMA150Full) SetBandwidth(bandwidthHz uint16) error {
	bwCode := bma150NearestBandwidth(bandwidthHz)
	rb, err := c.readReg8(bma150RegRangeBW)
	if err != nil {
		return err
	}
	return c.writeReg(bma150RegRangeBW, (rb&0xF8)|bwCode)
}

// ReadRaw returns 10-bit two's-complement acceleration counts.
func (c *BMA150Full) ReadRaw() (int16, int16, int16, error) {
	raw, err := c.readBurst(bma150RegAccXLSB, 6)
	if err != nil {
		return 0, 0, 0, err
	}
	rx := int16(connection.ToSigned(uint32(raw[1])<<2|uint32(raw[0]&0xC0)>>6, 10))
	ry := int16(connection.ToSigned(uint32(raw[3])<<2|uint32(raw[2]&0xC0)>>6, 10))
	rz := int16(connection.ToSigned(uint32(raw[5])<<2|uint32(raw[4]&0xC0)>>6, 10))
	return rx, ry, rz, nil
}

// ReadTemperature returns the on-chip temperature in °C.
func (c *BMA150Full) ReadTemperature() (float32, error) {
	raw, err := c.readReg8(bma150RegTemp)
	if err != nil {
		return 0, err
	}
	return float32(raw)*0.5 - 30.0, nil
}

// NewDataAvailable returns true if all three new_data_X/Y/Z bits are set.
func (c *BMA150Full) NewDataAvailable() (bool, error) {
	x, err := c.readReg8(bma150RegAccXLSB)
	if err != nil {
		return false, err
	}
	y, err := c.readReg8(bma150RegAccYLSB)
	if err != nil {
		return false, err
	}
	z, err := c.readReg8(bma150RegAccZLSB)
	if err != nil {
		return false, err
	}
	return (x&0x01) != 0 && (y&0x01) != 0 && (z&0x01) != 0, nil
}

// SetShadow enables or disables MSB-only reads.
func (c *BMA150Full) SetShadow(enabled bool) error {
	cfg, err := c.readReg8(bma150RegConfig)
	if err != nil {
		return err
	}
	if enabled {
		cfg |= 0x08
	} else {
		cfg &^= 0x08
	}
	return c.writeReg(bma150RegConfig, cfg)
}

// SetLowG configures the low-g (free-fall) interrupt and enables it.
func (c *BMA150Full) SetLowG(thresholdG float32, durationMs uint16, hysteresisG float32, counter uint8) error {
	if err := c.writeThreshold(bma150RegLGThres, thresholdG); err != nil {
		return err
	}
	dur := durationMs
	if dur > 255 {
		dur = 255
	}
	if err := c.writeReg(bma150RegLGDur, uint8(dur)); err != nil {
		return err
	}
	if err := c.writeHyst('l', hysteresisG); err != nil {
		return err
	}
	if err := c.writeIntCounter('l', counter); err != nil {
		return err
	}
	return c.enableSource(BMA150SourceLowG)
}

// SetHighG configures the high-g (shock) interrupt and enables it.
func (c *BMA150Full) SetHighG(thresholdG float32, durationMs uint16, hysteresisG float32, counter uint8) error {
	if err := c.writeThreshold(bma150RegHGThres, thresholdG); err != nil {
		return err
	}
	dur := durationMs
	if dur > 255 {
		dur = 255
	}
	if err := c.writeReg(bma150RegHGDur, uint8(dur)); err != nil {
		return err
	}
	if err := c.writeHyst('h', hysteresisG); err != nil {
		return err
	}
	if err := c.writeIntCounter('h', counter); err != nil {
		return err
	}
	return c.enableSource(BMA150SourceHighG)
}

// SetAnyMotion configures the any-motion interrupt and enables it.
func (c *BMA150Full) SetAnyMotion(thresholdG float32, samples uint8) error {
	var scale float32
	switch c.range_g {
	case 4:
		scale = bma150Scale4G / 256.0
	case 8:
		scale = bma150Scale8G / 256.0
	default:
		scale = bma150Scale2G / 256.0
	}
	code := int32(math.Round(float64(thresholdG / (0.0156 * scale))))
	if code < 0 {
		code = 0
	}
	if code > 255 {
		code = 255
	}
	if err := c.writeReg(bma150RegAnyMotionThres, uint8(code)); err != nil {
		return err
	}
	var durCode uint8
	switch samples {
	case 3:
		durCode = 0x40
	case 5:
		durCode = 0x80
	case 7:
		durCode = 0xC0
	default:
		durCode = 0x00
	}
	hd, err := c.readReg8(bma150RegHystDur)
	if err != nil {
		return err
	}
	if err := c.writeReg(bma150RegHystDur, (hd&0x3F)|durCode); err != nil {
		return err
	}
	cfg, err := c.readReg8(bma150RegConfig)
	if err != nil {
		return err
	}
	if err := c.writeReg(bma150RegConfig, cfg|0x40); err != nil {
		return err
	}
	return c.enableSource(BMA150SourceAnyMotion)
}

// SetAlert toggles alert mode (mutually exclusive with any-motion).
func (c *BMA150Full) SetAlert(enabled bool) error {
	if enabled {
		c.enabledSources &^= BMA150SourceAnyMotion
		cfg, err := c.readReg8(bma150RegConfig)
		if err != nil {
			return err
		}
		if err := c.writeReg(bma150RegConfig, cfg|0x40); err != nil {
			return err
		}
		return c.enableSource(BMA150SourceAlert)
	}
	return c.disableSource(BMA150SourceAlert)
}

// SetLatch enables latched interrupts.
func (c *BMA150Full) SetLatch(enabled bool) error {
	cfg, err := c.readReg8(bma150RegConfig)
	if err != nil {
		return err
	}
	if enabled {
		cfg |= 0x10
	} else {
		cfg &^= 0x10
	}
	return c.writeReg(bma150RegConfig, cfg)
}

// ClearInterrupt writes reset_INT to CTRL.
func (c *BMA150Full) ClearInterrupt() error {
	if c.sleeping {
		return nil
	}
	ctrl, err := c.readReg8(bma150RegCtrl)
	if err != nil {
		return err
	}
	return c.writeReg(bma150RegCtrl, ctrl|0x40)
}

// EnableInterrupt enables one interrupt source.
func (c *BMA150Full) EnableInterrupt(source uint8) error {
	if source == BMA150SourceNewData {
		c.enabledSources &= 0x0F
	} else {
		c.enabledSources &^= BMA150SourceNewData
		if source == BMA150SourceAnyMotion {
			c.enabledSources &^= BMA150SourceAlert
		} else if source == BMA150SourceAlert {
			c.enabledSources &^= BMA150SourceAnyMotion
		}
	}
	return c.enableSource(source)
}

// DisableInterrupt disables one interrupt source.
func (c *BMA150Full) DisableInterrupt(source uint8) error {
	return c.disableSource(source)
}

// PollInterrupt reads STATUS without clearing latched bits.
func (c *BMA150Full) PollInterrupt() (uint8, error) {
	return c.readReg8(bma150RegStatus)
}

// SetWakeUp configures self-wake-up mode.
func (c *BMA150Full) SetWakeUp(enabled bool, pauseMs uint16) error {
	var pauseCode uint8
	switch pauseMs {
	case 80:
		pauseCode = 0x02
	case 320:
		pauseCode = 0x04
	case 2560:
		pauseCode = 0x06
	default:
		pauseCode = 0x00
	}
	cfg, err := c.readReg8(bma150RegConfig)
	if err != nil {
		return err
	}
	out := (cfg & 0xF8) | pauseCode
	if enabled {
		out |= 0x01
	} else {
		out &^= 0x01
	}
	return c.writeReg(bma150RegConfig, out)
}

// Sleep enters sleep mode.
func (c *BMA150Full) Sleep() error {
	if c.sleeping {
		return nil
	}
	ctrl, err := c.readReg8(bma150RegCtrl)
	if err != nil {
		return err
	}
	if err := c.writeReg(bma150RegCtrl, ctrl|0x01); err != nil {
		return err
	}
	c.sleeping = true
	return nil
}

// Wake leaves sleep mode.
func (c *BMA150Full) Wake() error {
	if !c.sleeping {
		return nil
	}
	ctrl, err := c.readReg8(bma150RegCtrl)
	if err != nil {
		return err
	}
	if err := c.writeReg(bma150RegCtrl, ctrl&^0x01); err != nil {
		return err
	}
	time.Sleep(2 * time.Millisecond)
	c.sleeping = false
	return nil
}

// SoftReset issues a power-on-equivalent reset; range/bandwidth restored.
func (c *BMA150Full) SoftReset() error {
	ctrl, err := c.readReg8(bma150RegCtrl)
	if err != nil {
		return err
	}
	if err := c.writeReg(bma150RegCtrl, ctrl|0x02); err != nil {
		return err
	}
	time.Sleep(30 * time.Millisecond)
	var rangeMask uint8
	switch c.range_g {
	case 4:
		rangeMask = bma150Range4G
	case 8:
		rangeMask = bma150Range8G
	default:
		rangeMask = bma150Range2G
	}
	rb, err := c.readReg8(bma150RegRangeBW)
	if err != nil {
		return err
	}
	if err := c.writeReg(bma150RegRangeBW, (rb&0xE0)|rangeMask|bma150BW100); err != nil {
		return err
	}
	c.sleeping = false
	return nil
}

// SelfTest runs the electrostatic self-test, returns true on pass.
func (c *BMA150Full) SelfTest() (bool, error) {
	ctrl, err := c.readReg8(bma150RegCtrl)
	if err != nil {
		return false, err
	}
	if err := c.writeReg(bma150RegCtrl, ctrl|0x04); err != nil {
		return false, err
	}
	time.Sleep(100 * time.Millisecond)
	status, err := c.readReg8(bma150RegStatus)
	if err != nil {
		return false, err
	}
	if err := c.writeReg(bma150RegCtrl, ctrl); err != nil {
		return false, err
	}
	return (status & 0x80) != 0, nil
}

// ReadStatus reads the STATUS register.
func (c *BMA150Full) ReadStatus() (uint8, error) {
	return c.readReg8(bma150RegStatus)
}

// ReadVersion reads the VERSION register split into (al_version, ml_version).
func (c *BMA150Full) ReadVersion() (uint8, uint8, error) {
	raw, err := c.readReg8(bma150RegVersion)
	if err != nil {
		return 0, 0, err
	}
	return (raw >> 4) & 0x0F, raw & 0x0F, nil
}

// ReadCustomer reads one of the two CUSTOMER scratch bytes.
func (c *BMA150Full) ReadCustomer(index uint8) (uint8, error) {
	reg := bma150RegCustomer1
	if index != 0 {
		reg = bma150RegCustomer2
	}
	return c.readReg8(reg)
}

// WriteCustomer writes one of the two CUSTOMER scratch bytes.
func (c *BMA150Full) WriteCustomer(index, value uint8) error {
	reg := bma150RegCustomer1
	if index != 0 {
		reg = bma150RegCustomer2
	}
	return c.writeReg(reg, value)
}

func (c *BMA150Full) writeThreshold(reg uint8, thresholdG float32) error {
	code := int32(math.Round(float64(thresholdG * 255.0 / float32(c.range_g))))
	if code < 0 {
		code = 0
	}
	if code > 255 {
		code = 255
	}
	return c.writeReg(reg, uint8(code))
}

func (c *BMA150Full) writeHyst(kind byte, hysteresisG float32) error {
	if hysteresisG < 0 {
		return nil
	}
	code := int32(math.Round(float64(hysteresisG * 255.0 / float32(c.range_g) / 32.0)))
	if code < 0 {
		code = 0
	}
	if code > 7 {
		code = 7
	}
	hd, err := c.readReg8(bma150RegHystDur)
	if err != nil {
		return err
	}
	var out uint8
	if kind == 'l' {
		out = (hd & 0xF8) | uint8(code)
	} else {
		out = (hd & 0xC7) | (uint8(code) << 3)
	}
	return c.writeReg(bma150RegHystDur, out)
}

func (c *BMA150Full) writeIntCounter(kind byte, counter uint8) error {
	if counter > 3 {
		return nil
	}
	code := (counter & 0x03) << 2 // LG bits 3:2; HG shifts 2 more (bits 5:4)
	ic, err := c.readReg8(bma150RegIntCtrl)
	if err != nil {
		return err
	}
	var out uint8
	if kind == 'l' {
		out = (ic & 0xF3) | code
	} else {
		out = (ic & 0xCF) | (code << 2)
	}
	return c.writeReg(bma150RegIntCtrl, out)
}

func (c *BMA150Full) enableSource(source uint8) error {
	if c.sleeping {
		return nil
	}
	c.enabledSources |= source
	if source == BMA150SourceNewData {
		cfg, err := c.readReg8(bma150RegConfig)
		if err != nil {
			return err
		}
		return c.writeReg(bma150RegConfig, cfg|0x20)
	}
	ic, err := c.readReg8(bma150RegIntCtrl)
	if err != nil {
		return err
	}
	out := ic
	if source == BMA150SourceLowG {
		out |= 0x01
	}
	if source == BMA150SourceHighG {
		out |= 0x02
	}
	if source == BMA150SourceAnyMotion {
		out |= 0x40
	}
	if source == BMA150SourceAlert {
		out |= 0x80
	}
	return c.writeReg(bma150RegIntCtrl, out)
}

func (c *BMA150Full) disableSource(source uint8) error {
	c.enabledSources &^= source
	if source == BMA150SourceNewData {
		cfg, err := c.readReg8(bma150RegConfig)
		if err != nil {
			return err
		}
		return c.writeReg(bma150RegConfig, cfg&^0x20)
	}
	ic, err := c.readReg8(bma150RegIntCtrl)
	if err != nil {
		return err
	}
	out := ic
	if source == BMA150SourceLowG {
		out &^= 0x01
	}
	if source == BMA150SourceHighG {
		out &^= 0x02
	}
	if source == BMA150SourceAnyMotion {
		out &^= 0x40
	}
	if source == BMA150SourceAlert {
		out &^= 0x80
	}
	return c.writeReg(bma150RegIntCtrl, out)
}
