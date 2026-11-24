package led

import (
	"errors"

	"github.com/tuhde/Periph/go/periph/connection"
)

// SAA1064 subaddress and control bit definitions.
const (
	saa1064SubControl  uint8 = 0x00
	saa1064Dynamic     uint8 = 0x01
	saa1064Unblank13   uint8 = 0x02
	saa1064Unblank24   uint8 = 0x04
	saa1064Test        uint8 = 0x08
	saa1064CurrentMask uint8 = 0x70
)

// SAA1064Digits is the number of digits driven by the chip.
const SAA1064Digits = 4

// SAA1064Blank is the value that blanks a digit in ShowDigits and ShowDigit.
const SAA1064Blank = -1

// ErrSAA1064Range is returned when an argument is outside its documented range.
var ErrSAA1064Range = errors.New("saa1064: argument out of range")

var saa1064Segments = [10][]int{
	0: {0, 1, 2, 3, 4, 5}, 1: {1, 2}, 2: {0, 1, 3, 4, 6}, 3: {0, 1, 2, 3, 6}, 4: {1, 2, 5, 6},
	5: {0, 2, 3, 5, 6}, 6: {0, 2, 3, 4, 5, 6}, 7: {0, 1, 2}, 8: {0, 1, 2, 3, 4, 5, 6}, 9: {0, 1, 2, 3, 5, 6},
}

// SAA1064Minimal is the 4-digit LED driver — minimal interface.
//
// Drives four 7-segment digits (multiplexed in pairs, dynamic mode) with
// decimal points. The I²C address is 0x38–0x3B, selected by the voltage on
// the ADR pin.
//
// Defaults baked in at construction: control byte 0x47 (dynamic mode, all
// digits unblanked, 12 mA segment current), digits blank, default segment
// mapping a = bit 0 … g = bit 6, dp = bit 7.
type SAA1064Minimal struct {
	connection connection.Connection
	control    uint8
	patterns   [4]uint8
	segMap     [8]uint8
	glyphs     [10]uint8
}

// NewSAA1064Minimal creates the driver, writes control 0x47 and blanks the digits.
//
// t must be a configured I²C connection bound to the chip's 7-bit address.
func NewSAA1064Minimal(t connection.Connection) (*SAA1064Minimal, error) {
	d := &SAA1064Minimal{connection: t, control: 0x47, segMap: [8]uint8{0, 1, 2, 3, 4, 5, 6, 7}}
	d.rebuildGlyphs()
	if err := t.Write([]byte{saa1064SubControl, d.control, 0, 0, 0, 0}); err != nil {
		return nil, err
	}
	return d, nil
}

func (d *SAA1064Minimal) rebuildGlyphs() {
	for v, segs := range saa1064Segments {
		var p uint8
		for _, s := range segs {
			p |= 1 << d.segMap[s]
		}
		d.glyphs[v] = p
	}
}

func (d *SAA1064Minimal) pattern(value int) (uint8, error) {
	if value == SAA1064Blank {
		return 0, nil
	}
	if value < 0 || value > 9 {
		return 0, ErrSAA1064Range
	}
	return d.glyphs[value], nil
}

func (d *SAA1064Minimal) writeDigits(first int) error {
	buf := append([]byte{byte(first)}, d.patterns[first-1:]...)
	return d.connection.Write(buf)
}

// ShowDigits shows four digits using the segment mapping.
//
// Each value is 0–9 or SAA1064Blank. Returns ErrSAA1064Range (without bus
// access) for any other value.
func (d *SAA1064Minimal) ShowDigits(digits [4]int) error {
	var p [4]uint8
	for i, v := range digits {
		x, err := d.pattern(v)
		if err != nil {
			return err
		}
		p[i] = x
	}
	d.patterns = p
	return d.writeDigits(1)
}

// ShowNumber shows a right-aligned decimal number 0–9999 with leading zeros blanked.
func (d *SAA1064Minimal) ShowNumber(value int) error {
	if value < 0 || value > 9999 {
		return ErrSAA1064Range
	}
	digits := [4]int{SAA1064Blank, SAA1064Blank, SAA1064Blank, value % 10}
	v := value / 10
	for i := 2; i >= 0; i-- {
		if v > 0 {
			digits[i] = v % 10
			v /= 10
		}
	}
	return d.ShowDigits(digits)
}

// Clear blanks all four digits.
func (d *SAA1064Minimal) Clear() error {
	d.patterns = [4]uint8{}
	return d.writeDigits(1)
}

// WriteRaw writes 1–4 raw segment bytes starting at digit firstDigit (1–4).
func (d *SAA1064Minimal) WriteRaw(patterns []byte, firstDigit uint8) error {
	if firstDigit < 1 || firstDigit > SAA1064Digits || len(patterns) < 1 || int(firstDigit)-1+len(patterns) > SAA1064Digits {
		return ErrSAA1064Range
	}
	for i, p := range patterns {
		d.patterns[int(firstDigit)-1+i] = p
	}
	return d.connection.Write(append([]byte{firstDigit}, patterns...))
}

// SAA1064Full is the 4-digit LED driver — full interface: mode, blanking,
// current, segment test, segment mapping and status. It embeds
// SAA1064Minimal.
type SAA1064Full struct {
	*SAA1064Minimal
}

// NewSAA1064Full creates the driver with the same initialization as the minimal driver.
func NewSAA1064Full(t connection.Connection) (*SAA1064Full, error) {
	m, err := NewSAA1064Minimal(t)
	if err != nil {
		return nil, err
	}
	return &SAA1064Full{SAA1064Minimal: m}, nil
}

func (d *SAA1064Full) writeControl() error {
	return d.connection.Write([]byte{saa1064SubControl, d.control})
}

func (d *SAA1064Full) setBit(mask uint8, on bool) error {
	if on {
		d.control |= mask
	} else {
		d.control &^= mask
	}
	d.control &= 0x7F
	return d.writeControl()
}

// SetMode selects dynamic (all four digits) or static (digits 1 and 2) mode.
func (d *SAA1064Full) SetMode(dynamic bool) error { return d.setBit(saa1064Dynamic, dynamic) }

// Blank blanks digit pairs: true blanks digits 1+3 / digits 2+4.
func (d *SAA1064Full) Blank(digits13, digits24 bool) error {
	if digits13 {
		d.control &^= saa1064Unblank13
	} else {
		d.control |= saa1064Unblank13
	}
	if digits24 {
		d.control &^= saa1064Unblank24
	} else {
		d.control |= saa1064Unblank24
	}
	d.control &= 0x7F
	return d.writeControl()
}

// SetCurrent sets the segment output current: 0, 3, 6, 9, 12, 15, 18 or 21 mA.
func (d *SAA1064Full) SetCurrent(ma int) error {
	if ma < 0 || ma > 21 || ma%3 != 0 {
		return ErrSAA1064Range
	}
	d.control = (d.control &^ saa1064CurrentMask & 0x7F) | uint8(ma/3)<<4
	return d.writeControl()
}

// SegmentTest switches all segment outputs on (segment test).
func (d *SAA1064Full) SegmentTest(on bool) error { return d.setBit(saa1064Test, on) }

// SetSegmentMap remaps the helper's segment bits: m holds the bit numbers
// (0–7) for a…g and dp. It does not touch the bus.
func (d *SAA1064Full) SetSegmentMap(m [8]uint8) error {
	for _, b := range m {
		if b > 7 {
			return ErrSAA1064Range
		}
	}
	d.segMap = m
	d.rebuildGlyphs()
	return nil
}

// ShowDigit shows one digit (0–9 or SAA1064Blank) with an optional decimal point.
func (d *SAA1064Full) ShowDigit(position uint8, value int, dp bool) error {
	if position < 1 || position > SAA1064Digits {
		return ErrSAA1064Range
	}
	p, err := d.pattern(value)
	if err != nil {
		return err
	}
	if dp {
		p |= 1 << d.segMap[7]
	}
	d.patterns[position-1] = p
	return d.connection.Write([]byte{position, p})
}

// SetDecimalPoint switches a decimal point, keeping the cached segments.
func (d *SAA1064Full) SetDecimalPoint(position uint8, on bool) error {
	if position < 1 || position > SAA1064Digits {
		return ErrSAA1064Range
	}
	bit := uint8(1) << d.segMap[7]
	if on {
		d.patterns[position-1] |= bit
	} else {
		d.patterns[position-1] &^= bit
	}
	return d.connection.Write([]byte{position, d.patterns[position-1]})
}

// ReadStatus reads the status byte (bit 7 = PR power-reset flag, cleared by the read).
func (d *SAA1064Full) ReadStatus() (uint8, error) {
	b, err := d.connection.Read(1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

// PowerResetFlag reads the status byte and returns its PR flag.
func (d *SAA1064Full) PowerResetFlag() (bool, error) {
	s, err := d.ReadStatus()
	return s&0x80 != 0, err
}

// WriteControl writes a raw control byte (bit 7 forced to 0).
func (d *SAA1064Full) WriteControl(value uint8) error {
	d.control = value & 0x7F
	return d.writeControl()
}
