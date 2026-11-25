// Package accelerometer contains drivers for standalone accelerometers.
package accelerometer

import (
	"fmt"
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// BMA180 register addresses (0x00..0x3A).
const (
	bma180RegChipID         uint8 = 0x00
	bma180RegVersion        uint8 = 0x01
	bma180RegAccXLSB        uint8 = 0x02
	bma180RegAccYLSB        uint8 = 0x04
	bma180RegAccZLSB        uint8 = 0x06
	bma180RegTemp           uint8 = 0x08
	bma180RegStatusReg1     uint8 = 0x09
	bma180RegStatusReg2     uint8 = 0x0A
	bma180RegStatusReg3     uint8 = 0x0B
	bma180RegStatusReg4     uint8 = 0x0C
	bma180RegCtrlReg0       uint8 = 0x0D
	bma180RegCtrlReg1       uint8 = 0x0E
	bma180RegReset          uint8 = 0x10
	bma180RegBWTCS          uint8 = 0x20
	bma180RegCtrlReg3       uint8 = 0x21
	bma180RegCtrlReg4       uint8 = 0x22
	bma180RegHY             uint8 = 0x23
	bma180RegSlopeTapsens   uint8 = 0x24
	bma180RegHighLowInfo    uint8 = 0x25
	bma180RegLowDur         uint8 = 0x26
	bma180RegHighDur        uint8 = 0x27
	bma180RegTapsensTh      uint8 = 0x28
	bma180RegLowTh          uint8 = 0x29
	bma180RegHighTh         uint8 = 0x2A
	bma180RegSlopeTh        uint8 = 0x2B
	bma180RegCD1            uint8 = 0x2C
	bma180RegCD2            uint8 = 0x2D
	bma180RegTCOX           uint8 = 0x2E
	bma180RegTCOY           uint8 = 0x2F
	bma180RegTCOZ           uint8 = 0x30
	bma180RegGainT          uint8 = 0x31
	bma180RegGainY          uint8 = 0x33
	bma180RegGainZ          uint8 = 0x34
	bma180RegOffsetLSB1     uint8 = 0x35
	bma180RegOffsetT        uint8 = 0x37
)

const (
	bma180ChipIDValue uint8 = 0x03
	bma180ChipIDMask  uint8 = 0x07
)

// CTRL_REG0 bits.
const (
	bma180CtrlReg0EEW      uint8 = 0x10
	bma180CtrlReg0ResetInt uint8 = 0x40
	bma180CtrlReg0ST0      uint8 = 0x04
	bma180CtrlReg0Sleep    uint8 = 0x02
)

// Soft-reset code.
const bma180SoftResetCmd uint8 = 0xB6

// Range bits in OFFSET_LSB1 (0x35) bits 3:1 — 111 not authorised.
const (
	bma180Range1G   uint8 = 0x00
	bma180Range1_5G uint8 = 0x02
	bma180Range2G   uint8 = 0x04
	bma180Range3G   uint8 = 0x06
	bma180Range4G   uint8 = 0x08
	bma180Range8G   uint8 = 0x0A
	bma180Range16G  uint8 = 0x0C
)

// Sensitivity (LSB/g) by range.
const (
	bma180Scale1G   float32 = 8192.0
	bma180Scale1_5G float32 = 5460.0
	bma180Scale2G   float32 = 4096.0
	bma180Scale3G   float32 = 2730.0
	bma180Scale4G   float32 = 2048.0
	bma180Scale8G   float32 = 1024.0
	bma180Scale16G  float32 = 512.0
)

// Low-pass bandwidth codes (BW_TCS 0x20 bits 7:4).
const (
	bma180BW10    uint8 = 0x00
	bma180BW20    uint8 = 0x10
	bma180BW40    uint8 = 0x20
	bma180BW75    uint8 = 0x30
	bma180BW150   uint8 = 0x40
	bma180BW300   uint8 = 0x50
	bma180BW600   uint8 = 0x60
	bma180BW1200  uint8 = 0x70
	bma180BWHigh1 uint8 = 0x80
	bma180BWBand  uint8 = 0x90
)

// Duration time base: T_update = 417 µs, *dur = 5 * T_update ≈ 2.085 ms/LSB.
const bma180DurLSBms float32 = 2.085

// Interrupt source bits.
const (
	// BMA180SourceLowG is the free-fall interrupt source.
	BMA180SourceLowG uint8 = 0x01
	// BMA180SourceHighG is the high-g (shock) interrupt source.
	BMA180SourceHighG uint8 = 0x02
	// BMA180SourceSlope is the slope (any-motion) interrupt source.
	BMA180SourceSlope uint8 = 0x04
	// BMA180SourceAlert is the alert-mode interrupt source (exclusive with slope).
	BMA180SourceAlert uint8 = 0x08
	// BMA180SourceTap is the double-tap interrupt source.
	BMA180SourceTap uint8 = 0x10
	// BMA180SourceNewData fires when all three new_data_X/Y/Z bits are set.
	BMA180SourceNewData uint8 = 0x20
)

// STATUS_REG3 latched interrupt flag bits.
const (
	BMA180StatusHighG  uint8 = 0x80
	BMA180StatusLowG   uint8 = 0x40
	BMA180StatusSlope  uint8 = 0x20
	BMA180StatusTap    uint8 = 0x10
	BMA180StatusXFirst uint8 = 0x04
	BMA180StatusYFirst uint8 = 0x02
	BMA180StatusZFirst uint8 = 0x01
)

// CTRL_REG3 bits (interrupt enables).
const (
	bma180CR3SlopeAlert  uint8 = 0x80
	bma180CR3SlopeInt    uint8 = 0x40
	bma180CR3HighInt     uint8 = 0x20
	bma180CR3LowInt      uint8 = 0x10
	bma180CR3TapInt      uint8 = 0x08
	bma180CR3AdvInt      uint8 = 0x04
	bma180CR3NewDataInt  uint8 = 0x02
	bma180CR3LatInt      uint8 = 0x01
)

var bma180BandwidthTable = []struct {
	hz   uint16
	code uint8
}{
	{10, bma180BW10}, {20, bma180BW20}, {40, bma180BW40}, {75, bma180BW75},
	{150, bma180BW150}, {300, bma180BW300}, {600, bma180BW600}, {1200, bma180BW1200},
}

var bma180TapDurTable = []struct {
	ms   uint16
	code uint8
}{
	{50, 0x00}, {75, 0x01}, {100, 0x02}, {150, 0x03},
	{250, 0x04}, {500, 0x05}, {750, 0x06}, {1000, 0x07},
}

func bma180NearestBandwidth(bwHz uint16) uint8 {
	best := bma180BandwidthTable[0]
	bestDiff := absDiff(bwHz, bma180BandwidthTable[0].hz)
	for _, entry := range bma180BandwidthTable[1:] {
		if d := absDiff(bwHz, entry.hz); d < bestDiff {
			best = entry
			bestDiff = d
		}
	}
	return best.code
}

func bma180NearestTapDur(windowMs uint16) uint8 {
	// Snap to the smallest table entry >= windowMs; fall back to the largest below it.
	bestBelow := bma180TapDurTable[0]
	bestBelowDiff := absDiff(windowMs, bma180TapDurTable[0].ms)
	for _, entry := range bma180TapDurTable[1:] {
		if entry.ms >= windowMs && absDiff(windowMs, entry.ms) < absDiff(windowMs, bestBelow.ms) {
			return entry.code
		}
		if d := absDiff(windowMs, entry.ms); d < bestBelowDiff {
			bestBelow = entry
			bestBelowDiff = d
		}
	}
	return bestBelow.code
}

// BMA180Minimal is the BMA180 3-axis accelerometer — minimal interface.
type BMA180Minimal struct {
	conn      connection.RegisterConnection
	rangeG    float32
	rangeBits uint8
}

// NewBMA180Minimal creates a BMA180Minimal, verifies CHIP_ID, and applies defaults.
func NewBMA180Minimal(conn connection.RegisterConnection) (*BMA180Minimal, error) {
	c := &BMA180Minimal{conn: conn, rangeG: 2, rangeBits: bma180Range2G}
	// First transaction must be something other than an acc LSB read.
	id, err := c.readReg8(bma180RegChipID)
	if err != nil {
		return nil, err
	}
	if (id & bma180ChipIDMask) != bma180ChipIDValue {
		return nil, fmt.Errorf("BMA180 CHIP_ID: expected 0x%02X, got 0x%02X",
			bma180ChipIDValue, id&bma180ChipIDMask)
	}
	// Unlock image registers (0x20-0x3B) by setting ee_w = 1.
	ctrl0, err := c.readReg8(bma180RegCtrlReg0)
	if err != nil {
		return nil, err
	}
	if err := c.writeReg(bma180RegCtrlReg0, ctrl0|bma180CtrlReg0EEW); err != nil {
		return nil, err
	}
	// Set range = ±2 g (OFFSET_LSB1 bits 3:1 = 010), preserving cal/smp_skip.
	olsb1, err := c.readReg8(bma180RegOffsetLSB1)
	if err != nil {
		return nil, err
	}
	if err := c.writeReg(bma180RegOffsetLSB1, (olsb1&0xF1)|bma180Range2G); err != nil {
		return nil, err
	}
	// Set bw = 150 Hz (BW_TCS bits 7:4 = 0100), preserving tcs.
	bw, err := c.readReg8(bma180RegBWTCS)
	if err != nil {
		return nil, err
	}
	if err := c.writeReg(bma180RegBWTCS, (bw&0x0F)|bma180BW150); err != nil {
		return nil, err
	}
	time.Sleep(4 * time.Millisecond)
	return c, nil
}

func (c *BMA180Minimal) writeReg(reg, val uint8) error {
	return c.conn.WriteReg(uint32(reg), []byte{val})
}

func (c *BMA180Minimal) readReg8(reg uint8) (uint8, error) {
	b, err := c.conn.ReadReg(uint32(reg), 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

func (c *BMA180Minimal) readBurst(reg uint8, n int) ([]byte, error) {
	return c.conn.ReadReg(uint32(reg), n)
}

// Read returns 3-axis linear acceleration as (x, y, z) in *g*.
func (c *BMA180Minimal) Read() (float32, float32, float32, error) {
	raw, err := c.readBurst(bma180RegAccXLSB, 6)
	if err != nil {
		return 0, 0, 0, err
	}
	rx := int16(connection.ToSigned(uint32(raw[1])<<6|uint32(raw[0])>>2, 14))
	ry := int16(connection.ToSigned(uint32(raw[3])<<6|uint32(raw[2])>>2, 14))
	rz := int16(connection.ToSigned(uint32(raw[5])<<6|uint32(raw[4])>>2, 14))
	var scale float32
	switch c.rangeG {
	case 1:
		scale = bma180Scale1G
	case 1.5:
		scale = bma180Scale1_5G
	case 3:
		scale = bma180Scale3G
	case 4:
		scale = bma180Scale4G
	case 8:
		scale = bma180Scale8G
	case 16:
		scale = bma180Scale16G
	default:
		scale = bma180Scale2G
	}
	return float32(rx) / scale, float32(ry) / scale, float32(rz) / scale, nil
}

// BMA180Full extends BMA180Minimal with configuration, interrupt sources,
// low-g/high-g/slope/alert/tap logic, sleep, soft reset, and self-test.
type BMA180Full struct {
	*BMA180Minimal
	enabledSources uint8
	sleeping       bool
}

// NewBMA180Full creates a BMA180Full.
func NewBMA180Full(conn connection.RegisterConnection) (*BMA180Full, error) {
	m, err := NewBMA180Minimal(conn)
	if err != nil {
		return nil, err
	}
	return &BMA180Full{BMA180Minimal: m}, nil
}

// Read delegates to BMA180Minimal.Read.
func (c *BMA180Full) Read() (float32, float32, float32, error) {
	return c.BMA180Minimal.Read()
}

// SetRange sets the measurement range to ±1/±1.5/±2/±3/±4/±8/±16 *g*.
func (c *BMA180Full) SetRange(rangeG float32) error {
	var bits uint8
	switch rangeG {
	case 1:
		bits = bma180Range1G
	case 1.5:
		bits = bma180Range1_5G
	case 3:
		bits = bma180Range3G
	case 4:
		bits = bma180Range4G
	case 8:
		bits = bma180Range8G
	case 16:
		bits = bma180Range16G
	default:
		bits = bma180Range2G
	}
	c.rangeG = rangeG
	c.rangeBits = bits
	olsb1, err := c.readReg8(bma180RegOffsetLSB1)
	if err != nil {
		return err
	}
	return c.writeReg(bma180RegOffsetLSB1, (olsb1&0xF1)|bits)
}

// SetBandwidth sets the low-pass bandwidth to the nearest supported value (10..1200 Hz).
func (c *BMA180Full) SetBandwidth(bandwidthHz uint16) error {
	bwCode := bma180NearestBandwidth(bandwidthHz)
	bw, err := c.readReg8(bma180RegBWTCS)
	if err != nil {
		return err
	}
	if err := c.writeReg(bma180RegBWTCS, (bw&0x0F)|bwCode); err != nil {
		return err
	}
	time.Sleep(4 * time.Millisecond)
	return nil
}

// SetFilterMode sets the filter mode: 0 = low-pass (use SetBandwidth), 1 = high-pass 1 Hz, 2 = band-pass 0.2..300 Hz.
func (c *BMA180Full) SetFilterMode(mode uint8) error {
	var code uint8
	if mode == 1 {
		code = bma180BWHigh1
	} else if mode == 2 {
		code = bma180BWBand
	} else {
		return nil
	}
	bw, err := c.readReg8(bma180RegBWTCS)
	if err != nil {
		return err
	}
	if err := c.writeReg(bma180RegBWTCS, (bw&0x0F)|code); err != nil {
		return err
	}
	time.Sleep(4 * time.Millisecond)
	return nil
}

// SetMode sets the noise/power sub-mode (0..3).
func (c *BMA180Full) SetMode(mode uint8) error {
	if mode > 3 {
		return nil
	}
	tcoz, err := c.readReg8(bma180RegTCOZ)
	if err != nil {
		return err
	}
	return c.writeReg(bma180RegTCOZ, (tcoz&0xFC)|(mode&0x03))
}

// SetResolution sets the data resolution: 12 or 14 bits.
func (c *BMA180Full) SetResolution(bits uint8) error {
	ot, err := c.readReg8(bma180RegOffsetT)
	if err != nil {
		return err
	}
	out := ot
	if bits == 12 {
		out |= 0x01
	} else if bits == 14 {
		out &^= 0x01
	} else {
		return nil
	}
	return c.writeReg(bma180RegOffsetT, out)
}

// ReadRaw returns 14-bit two's-complement acceleration counts.
func (c *BMA180Full) ReadRaw() (int16, int16, int16, error) {
	raw, err := c.readBurst(bma180RegAccXLSB, 6)
	if err != nil {
		return 0, 0, 0, err
	}
	rx := int16(connection.ToSigned(uint32(raw[1])<<6|uint32(raw[0])>>2, 14))
	ry := int16(connection.ToSigned(uint32(raw[3])<<6|uint32(raw[2])>>2, 14))
	rz := int16(connection.ToSigned(uint32(raw[5])<<6|uint32(raw[4])>>2, 14))
	return rx, ry, rz, nil
}

// ReadTemperature returns the on-chip temperature in °C.
func (c *BMA180Full) ReadTemperature() (float32, error) {
	raw, err := c.readReg8(bma180RegTemp)
	if err != nil {
		return 0, err
	}
	signed := int32(raw)
	if signed > 127 {
		signed -= 256
	}
	return 25.0 + (float32(signed)-2.0)*0.5, nil
}

// NewDataAvailable returns true if all three new_data_X/Y/Z bits are set.
func (c *BMA180Full) NewDataAvailable() (bool, error) {
	x, err := c.readReg8(bma180RegAccXLSB)
	if err != nil {
		return false, err
	}
	y, err := c.readReg8(bma180RegAccYLSB)
	if err != nil {
		return false, err
	}
	z, err := c.readReg8(bma180RegAccZLSB)
	if err != nil {
		return false, err
	}
	return (x&0x01) != 0 && (y&0x01) != 0 && (z&0x01) != 0, nil
}

// SetShadow enables or disables MSB-only reads.
func (c *BMA180Full) SetShadow(enabled bool) error {
	gy, err := c.readReg8(bma180RegGainY)
	if err != nil {
		return err
	}
	out := gy
	if enabled {
		out &^= 0x01
	} else {
		out |= 0x01
	}
	return c.writeReg(bma180RegGainY, out)
}

// SetSampleSkip toggles the smp_skip bit (only useful with the new-data interrupt).
func (c *BMA180Full) SetSampleSkip(enabled bool) error {
	olsb1, err := c.readReg8(bma180RegOffsetLSB1)
	if err != nil {
		return err
	}
	out := olsb1
	if enabled {
		out |= 0x01
	} else {
		out &^= 0x01
	}
	return c.writeReg(bma180RegOffsetLSB1, out)
}

// SetLowG configures the low-g (free-fall) interrupt and enables it.
func (c *BMA180Full) SetLowG(thresholdG float32, durationMs uint16, hysteresisG float32, axes uint8, counter uint8, filtered bool) error {
	if err := c.writeThreshold(bma180RegLowTh, thresholdG); err != nil {
		return err
	}
	if err := c.writeLowDur(durationMs); err != nil {
		return err
	}
	if err := c.writeLowHy(hysteresisG); err != nil {
		return err
	}
	if err := c.writeLowAxes(axes); err != nil {
		return err
	}
	if err := c.writeFiltBit(bma180RegHighLowInfo, 0x01, filtered); err != nil {
		return err
	}
	if err := c.writeDebounce('l', counter); err != nil {
		return err
	}
	return c.enableSource(BMA180SourceLowG)
}

// SetHighG configures the high-g (shock) interrupt and enables it.
func (c *BMA180Full) SetHighG(thresholdG float32, durationMs uint16, hysteresisG float32, axes uint8, counter uint8, filtered bool) error {
	if err := c.writeThreshold(bma180RegHighTh, thresholdG); err != nil {
		return err
	}
	if err := c.writeHighDur(durationMs); err != nil {
		return err
	}
	if err := c.writeHighHy(hysteresisG); err != nil {
		return err
	}
	if err := c.writeHighAxes(axes); err != nil {
		return err
	}
	if err := c.writeFiltBit(bma180RegHighLowInfo, 0x10, filtered); err != nil {
		return err
	}
	if err := c.writeDebounce('h', counter); err != nil {
		return err
	}
	return c.enableSource(BMA180SourceHighG)
}

// SetSlope configures the slope (any-motion) interrupt and enables it.
func (c *BMA180Full) SetSlope(thresholdG float32, samples uint8, axes uint8, filtered bool) error {
	if err := c.writeSlopeThreshold(bma180RegSlopeTh, thresholdG); err != nil {
		return err
	}
	if err := c.writeSlopeDur(samples); err != nil {
		return err
	}
	if err := c.writeSlopeAxes(axes); err != nil {
		return err
	}
	if err := c.writeFiltBit(bma180RegSlopeTapsens, 0x10, filtered); err != nil {
		return err
	}
	if err := c.writeCR3Bit(bma180CR3SlopeInt, true); err != nil {
		return err
	}
	if err := c.writeCR3Bit(bma180CR3SlopeAlert, false); err != nil {
		return err
	}
	if err := c.writeCR3Bit(bma180CR3AdvInt, true); err != nil {
		return err
	}
	return c.enableSource(BMA180SourceSlope)
}

// SetAlert enables alert mode (exclusive with slope).
func (c *BMA180Full) SetAlert(enabled bool) error {
	if enabled {
		c.enabledSources &^= BMA180SourceSlope
		if err := c.writeCR3Bit(bma180CR3SlopeInt, false); err != nil {
			return err
		}
		if err := c.writeCR3Bit(bma180CR3SlopeAlert, true); err != nil {
			return err
		}
		if err := c.writeCR3Bit(bma180CR3AdvInt, true); err != nil {
			return err
		}
		return c.enableSource(BMA180SourceAlert)
	}
	if err := c.disableSource(BMA180SourceAlert); err != nil {
		return err
	}
	return c.writeCR3Bit(bma180CR3SlopeAlert, false)
}

// SetTap configures the double-tap interrupt and enables it.
func (c *BMA180Full) SetTap(thresholdG float32, windowMs uint16, axes uint8, filtered bool) error {
	if err := c.writeSlopeThreshold(bma180RegTapsensTh, thresholdG); err != nil {
		return err
	}
	if err := c.writeTapDur(windowMs); err != nil {
		return err
	}
	if err := c.writeTapAxes(axes); err != nil {
		return err
	}
	if err := c.writeFiltBit(bma180RegSlopeTapsens, 0x01, filtered); err != nil {
		return err
	}
	return c.enableSource(BMA180SourceTap)
}

// SetLatch enables latched interrupts (cleared by ClearInterrupt).
func (c *BMA180Full) SetLatch(enabled bool) error {
	return c.writeCR3Bit(bma180CR3LatInt, enabled)
}

// ClearInterrupt writes reset_INT to CTRL_REG0.
func (c *BMA180Full) ClearInterrupt() error {
	if c.sleeping {
		return nil
	}
	ctrl0, err := c.readReg8(bma180RegCtrlReg0)
	if err != nil {
		return err
	}
	return c.writeReg(bma180RegCtrlReg0, ctrl0|bma180CtrlReg0ResetInt)
}

// EnableInterrupt enables one interrupt source.
func (c *BMA180Full) EnableInterrupt(source uint8) error {
	if source == BMA180SourceNewData {
		if err := c.writeCR3Bit(bma180CR3NewDataInt, true); err != nil {
			return err
		}
	} else {
		if err := c.writeCR3Bit(bma180CR3NewDataInt, false); err != nil {
			return err
		}
		switch source {
		case BMA180SourceSlope:
			if err := c.writeCR3Bit(bma180CR3SlopeAlert, false); err != nil {
				return err
			}
			if err := c.writeCR3Bit(bma180CR3SlopeInt, true); err != nil {
				return err
			}
			if err := c.writeCR3Bit(bma180CR3AdvInt, true); err != nil {
				return err
			}
			if err := c.disableSource(BMA180SourceAlert); err != nil {
				return err
			}
		case BMA180SourceAlert:
			if err := c.writeCR3Bit(bma180CR3SlopeInt, false); err != nil {
				return err
			}
			if err := c.writeCR3Bit(bma180CR3SlopeAlert, true); err != nil {
				return err
			}
			if err := c.writeCR3Bit(bma180CR3AdvInt, true); err != nil {
				return err
			}
			if err := c.disableSource(BMA180SourceSlope); err != nil {
				return err
			}
		case BMA180SourceHighG:
			if err := c.writeCR3Bit(bma180CR3HighInt, true); err != nil {
				return err
			}
		case BMA180SourceLowG:
			if err := c.writeCR3Bit(bma180CR3LowInt, true); err != nil {
				return err
			}
		case BMA180SourceTap:
			if err := c.writeCR3Bit(bma180CR3TapInt, true); err != nil {
				return err
			}
		}
	}
	return c.enableSource(source)
}

// DisableInterrupt disables one interrupt source.
func (c *BMA180Full) DisableInterrupt(source uint8) error {
	switch source {
	case BMA180SourceNewData:
		if err := c.writeCR3Bit(bma180CR3NewDataInt, false); err != nil {
			return err
		}
	case BMA180SourceSlope:
		if err := c.writeCR3Bit(bma180CR3SlopeInt, false); err != nil {
			return err
		}
	case BMA180SourceAlert:
		if err := c.writeCR3Bit(bma180CR3SlopeAlert, false); err != nil {
			return err
		}
		if err := c.writeCR3Bit(bma180CR3AdvInt, false); err != nil {
			return err
		}
	case BMA180SourceHighG:
		if err := c.writeCR3Bit(bma180CR3HighInt, false); err != nil {
			return err
		}
	case BMA180SourceLowG:
		if err := c.writeCR3Bit(bma180CR3LowInt, false); err != nil {
			return err
		}
	case BMA180SourceTap:
		if err := c.writeCR3Bit(bma180CR3TapInt, false); err != nil {
			return err
		}
	}
	return c.disableSource(source)
}

// PollInterrupt reads STATUS_REG3 (latched flags) without clearing.
func (c *BMA180Full) PollInterrupt() (uint8, error) {
	return c.readReg8(bma180RegStatusReg3)
}

// ReadStatus reads all four status registers.
func (c *BMA180Full) ReadStatus() (s1, s2, s3, s4 uint8, err error) {
	if s1, err = c.readReg8(bma180RegStatusReg1); err != nil {
		return
	}
	if s2, err = c.readReg8(bma180RegStatusReg2); err != nil {
		return
	}
	if s3, err = c.readReg8(bma180RegStatusReg3); err != nil {
		return
	}
	s4, err = c.readReg8(bma180RegStatusReg4)
	return
}

// SetWakeUp configures self-wake-up mode.
func (c *BMA180Full) SetWakeUp(enabled bool, pauseMs uint16) error {
	var code uint8
	switch pauseMs {
	case 80:
		code = 0x01
	case 320:
		code = 0x02
	case 2560:
		code = 0x03
	default:
		code = 0x00
	}
	tcoy, err := c.readReg8(bma180RegTCOY)
	if err != nil {
		return err
	}
	if err := c.writeReg(bma180RegTCOY, (tcoy&0xFC)|code); err != nil {
		return err
	}
	gz, err := c.readReg8(bma180RegGainZ)
	if err != nil {
		return err
	}
	out := gz
	if enabled {
		out |= 0x01
	} else {
		out &^= 0x01
	}
	return c.writeReg(bma180RegGainZ, out)
}

// Sleep enters sleep mode.
func (c *BMA180Full) Sleep() error {
	if c.sleeping {
		return nil
	}
	ctrl0, err := c.readReg8(bma180RegCtrlReg0)
	if err != nil {
		return err
	}
	if err := c.writeReg(bma180RegCtrlReg0, ctrl0|bma180CtrlReg0Sleep); err != nil {
		return err
	}
	c.sleeping = true
	return nil
}

// Wake leaves sleep mode.
func (c *BMA180Full) Wake() error {
	if !c.sleeping {
		return nil
	}
	ctrl0, err := c.readReg8(bma180RegCtrlReg0)
	if err != nil {
		return err
	}
	if err := c.writeReg(bma180RegCtrlReg0, ctrl0&^bma180CtrlReg0Sleep); err != nil {
		return err
	}
	time.Sleep(2 * time.Millisecond)
	c.sleeping = false
	return nil
}

// SoftReset issues a power-on-equivalent reset; restores defaults.
func (c *BMA180Full) SoftReset() error {
	if err := c.writeReg(bma180RegReset, bma180SoftResetCmd); err != nil {
		return err
	}
	time.Sleep(30 * time.Millisecond)
	c.rangeG = 2
	c.rangeBits = bma180Range2G
	id, err := c.readReg8(bma180RegChipID)
	if err != nil {
		return err
	}
	if (id & bma180ChipIDMask) != bma180ChipIDValue {
		return fmt.Errorf("BMA180 CHIP_ID after reset: expected 0x%02X, got 0x%02X",
			bma180ChipIDValue, id&bma180ChipIDMask)
	}
	ctrl0, err := c.readReg8(bma180RegCtrlReg0)
	if err != nil {
		return err
	}
	if err := c.writeReg(bma180RegCtrlReg0, ctrl0|bma180CtrlReg0EEW); err != nil {
		return err
	}
	olsb1, err := c.readReg8(bma180RegOffsetLSB1)
	if err != nil {
		return err
	}
	if err := c.writeReg(bma180RegOffsetLSB1, (olsb1&0xF1)|bma180Range2G); err != nil {
		return err
	}
	bw, err := c.readReg8(bma180RegBWTCS)
	if err != nil {
		return err
	}
	if err := c.writeReg(bma180RegBWTCS, (bw&0x0F)|bma180BW150); err != nil {
		return err
	}
	time.Sleep(4 * time.Millisecond)
	c.sleeping = false
	return nil
}

// SelfTest runs the electrostatic self-test, returns true if every axis responds > 200 LSB.
func (c *BMA180Full) SelfTest() (bool, error) {
	ctrl0, err := c.readReg8(bma180RegCtrlReg0)
	if err != nil {
		return false, err
	}
	if err := c.writeReg(bma180RegCtrlReg0, ctrl0|bma180CtrlReg0ST0); err != nil {
		return false, err
	}
	time.Sleep(10 * time.Millisecond)
	x, y, z, err := c.ReadRaw()
	if err != nil {
		return false, err
	}
	if err := c.writeReg(bma180RegCtrlReg0, ctrl0); err != nil {
		return false, err
	}
	passed := absInt(x) > 200 && absInt(y) > 200 && absInt(z) > 200
	if err := c.SoftReset(); err != nil {
		return false, err
	}
	return passed, nil
}

// CalibrateOffset runs the in-field zero-g calibration (volatile).
func (c *BMA180Full) CalibrateOffset(axes uint8, mode uint8) error {
	if mode > 3 {
		return nil
	}
	cr4, err := c.readReg8(bma180RegCtrlReg4)
	if err != nil {
		return err
	}
	if err := c.writeReg(bma180RegCtrlReg4, (cr4&0xFC)|(mode&0x03)); err != nil {
		return err
	}
	ax := []struct{ mask, ctrl1Bit uint8 }{
		{0x01, 0x80}, {0x02, 0x40}, {0x04, 0x20},
	}
	for _, a := range ax {
		if axes&a.mask == 0 {
			continue
		}
		ctrl1, err := c.readReg8(bma180RegCtrlReg1)
		if err != nil {
			return err
		}
		if err := c.writeReg(bma180RegCtrlReg1, ctrl1|a.ctrl1Bit); err != nil {
			return err
		}
		for t := 0; t < 100; t++ {
			s1, err := c.readReg8(bma180RegStatusReg1)
			if err != nil {
				return err
			}
			if s1&0x02 != 0 {
				break
			}
			time.Sleep(100 * time.Millisecond)
		}
		ctrl1, err = c.readReg8(bma180RegCtrlReg1)
		if err != nil {
			return err
		}
		if err := c.writeReg(bma180RegCtrlReg1, ctrl1&^a.ctrl1Bit); err != nil {
			return err
		}
	}
	cr4, err = c.readReg8(bma180RegCtrlReg4)
	if err != nil {
		return err
	}
	return c.writeReg(bma180RegCtrlReg4, cr4&0xFC)
}

// ReadVersion reads VERSION split into (al_version, ml_version).
func (c *BMA180Full) ReadVersion() (uint8, uint8, error) {
	raw, err := c.readReg8(bma180RegVersion)
	if err != nil {
		return 0, 0, err
	}
	return (raw >> 4) & 0x0F, raw & 0x0F, nil
}

// ReadCustomer reads one of the two CUSTOMER scratch bytes.
func (c *BMA180Full) ReadCustomer(index uint8) (uint8, error) {
	reg := bma180RegCD1
	if index != 0 {
		reg = bma180RegCD2
	}
	return c.readReg8(reg)
}

// WriteCustomer writes one of the two CUSTOMER scratch bytes.
func (c *BMA180Full) WriteCustomer(index, value uint8) error {
	reg := bma180RegCD1
	if index != 0 {
		reg = bma180RegCD2
	}
	return c.writeReg(reg, value)
}

func (c *BMA180Full) writeThreshold(reg uint8, thresholdG float32) error {
	code := int32(math.Round(float64(thresholdG / c.rangeG * 255.0)))
	if code < 0 {
		code = 0
	}
	if code > 255 {
		code = 255
	}
	return c.writeReg(reg, uint8(code))
}

func (c *BMA180Full) writeSlopeThreshold(reg uint8, thresholdG float32) error {
	code := int32(math.Round(float64(thresholdG / (0.0156 * c.rangeG / 2.0))))
	if code < 0 {
		code = 0
	}
	if code > 255 {
		code = 255
	}
	return c.writeReg(reg, uint8(code))
}

func (c *BMA180Full) writeLowDur(durationMs uint16) error {
	code := int32(math.Round(float64(float32(durationMs) / bma180DurLSBms)))
	if code < 0 {
		code = 0
	}
	if code > 127 {
		code = 127
	}
	ld, err := c.readReg8(bma180RegLowDur)
	if err != nil {
		return err
	}
	return c.writeReg(bma180RegLowDur, (ld&0x01)|((uint8(code)&0x7F)<<1))
}

func (c *BMA180Full) writeHighDur(durationMs uint16) error {
	code := int32(math.Round(float64(float32(durationMs) / bma180DurLSBms)))
	if code < 0 {
		code = 0
	}
	if code > 127 {
		code = 127
	}
	hd, err := c.readReg8(bma180RegHighDur)
	if err != nil {
		return err
	}
	return c.writeReg(bma180RegHighDur, (hd&0x01)|((uint8(code)&0x7F)<<1))
}

func (c *BMA180Full) writeLowHy(hysteresisG float32) error {
	code := int32(math.Round(float64(hysteresisG / c.rangeG * 255.0 / 32.0)))
	if code < 0 {
		code = 0
	}
	if code > 31 {
		code = 31
	}
	hy, err := c.readReg8(bma180RegHY)
	if err != nil {
		return err
	}
	if err := c.writeReg(bma180RegHY, (hy&0xF8)|(uint8(code)&0x07)); err != nil {
		return err
	}
	cr4, err := c.readReg8(bma180RegCtrlReg4)
	if err != nil {
		return err
	}
	return c.writeReg(bma180RegCtrlReg4, (cr4&^(0x03<<6))|((uint8(code>>3)&0x03)<<6))
}

func (c *BMA180Full) writeHighHy(hysteresisG float32) error {
	code := int32(math.Round(float64(hysteresisG / c.rangeG * 255.0 / 32.0)))
	if code < 0 {
		code = 0
	}
	if code > 31 {
		code = 31
	}
	hy, err := c.readReg8(bma180RegHY)
	if err != nil {
		return err
	}
	return c.writeReg(bma180RegHY, (hy&0x07)|((uint8(code)&0x1F)<<3))
}

func (c *BMA180Full) writeLowAxes(axes uint8) error {
	hli, err := c.readReg8(bma180RegHighLowInfo)
	if err != nil {
		return err
	}
	return c.writeReg(bma180RegHighLowInfo, (hli&0xF1)|((axes&0x07)<<1))
}

func (c *BMA180Full) writeHighAxes(axes uint8) error {
	hli, err := c.readReg8(bma180RegHighLowInfo)
	if err != nil {
		return err
	}
	return c.writeReg(bma180RegHighLowInfo, (hli&0x0F)|((axes&0x07)<<5))
}

func (c *BMA180Full) writeSlopeAxes(axes uint8) error {
	st, err := c.readReg8(bma180RegSlopeTapsens)
	if err != nil {
		return err
	}
	return c.writeReg(bma180RegSlopeTapsens, (st&0x0F)|((axes&0x07)<<5))
}

func (c *BMA180Full) writeTapAxes(axes uint8) error {
	st, err := c.readReg8(bma180RegSlopeTapsens)
	if err != nil {
		return err
	}
	return c.writeReg(bma180RegSlopeTapsens, (st&0xF1)|((axes&0x07)<<1))
}

func (c *BMA180Full) writeFiltBit(reg uint8, bit uint8, enabled bool) error {
	v, err := c.readReg8(reg)
	if err != nil {
		return err
	}
	out := v
	if enabled {
		out |= bit
	} else {
		out &^= bit
	}
	return c.writeReg(reg, out)
}

func (c *BMA180Full) writeDebounce(kind byte, counter uint8) error {
	if counter > 3 {
		return nil
	}
	code := (counter & 0x03) << 2
	cr4, err := c.readReg8(bma180RegCtrlReg4)
	if err != nil {
		return err
	}
	out := cr4
	if kind == 'l' {
		out = (cr4 &^ (0x03 << 2)) | (code & (0x03 << 2))
	} else {
		out = (cr4 &^ (0x03 << 4)) | ((code << 2) & (0x03 << 4))
	}
	return c.writeReg(bma180RegCtrlReg4, out)
}

func (c *BMA180Full) writeSlopeDur(samples uint8) error {
	var code uint8
	switch samples {
	case 3:
		code = 0x01
	case 5:
		code = 0x02
	case 7:
		code = 0x03
	default:
		code = 0x00
	}
	tcox, err := c.readReg8(bma180RegTCOX)
	if err != nil {
		return err
	}
	return c.writeReg(bma180RegTCOX, (tcox&0xFC)|code)
}

func (c *BMA180Full) writeTapDur(windowMs uint16) error {
	code := bma180NearestTapDur(windowMs)
	gt, err := c.readReg8(bma180RegGainT)
	if err != nil {
		return err
	}
	return c.writeReg(bma180RegGainT, (gt&0xF8)|code)
}

func (c *BMA180Full) writeCR3Bit(bit uint8, enabled bool) error {
	if c.sleeping {
		return nil
	}
	cr3, err := c.readReg8(bma180RegCtrlReg3)
	if err != nil {
		return err
	}
	out := cr3
	if enabled {
		out |= bit
	} else {
		out &^= bit
	}
	return c.writeReg(bma180RegCtrlReg3, out)
}

func (c *BMA180Full) enableSource(source uint8) error {
	if c.sleeping {
		return nil
	}
	c.enabledSources |= source
	return nil
}

func (c *BMA180Full) disableSource(source uint8) error {
	c.enabledSources &^= source
	return nil
}

func absInt(v int16) int16 {
	if v < 0 {
		return -v
	}
	return v
}