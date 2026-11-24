package led

import (
	"bytes"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// saa1064Mock is an in-memory fake connection.Connection for unit tests —
// no hardware, no bus. Every Write is logged; reads pop from a FIFO queue.
type saa1064Mock struct {
	connection.Connection // embedded nil: only the methods overridden below are used
	writes                [][]byte
	reads                 [][]byte
}

func (m *saa1064Mock) Write(data []byte) error {
	m.writes = append(m.writes, append([]byte(nil), data...))
	return nil
}

func (m *saa1064Mock) Read(n int) ([]byte, error) {
	if len(m.reads) == 0 {
		return make([]byte, n), nil
	}
	r := m.reads[0]
	m.reads = m.reads[1:]
	return r, nil
}

func (m *saa1064Mock) Close() error                { return nil }
func (m *saa1064Mock) IntPin() connection.InputPin { return nil }

func (m *saa1064Mock) last() []byte { return m.writes[len(m.writes)-1] }

func TestSAA1064(t *testing.T) {
	m := &saa1064Mock{}
	d, err := NewSAA1064Full(m)
	if err != nil {
		t.Fatal(err)
	}
	eq := func(label string, got []byte, want ...byte) {
		t.Helper()
		if !bytes.Equal(got, want) {
			t.Errorf("%s: got % X, want % X", label, got, want)
		}
	}
	rangeErr := func(label string, err error) {
		t.Helper()
		if err != ErrSAA1064Range {
			t.Errorf("%s: want range error, got %v", label, err)
		}
	}
	eq("init", m.writes[0], 0x00, 0x47, 0, 0, 0, 0)

	_ = d.ShowDigits([4]int{1, 2, 3, 4})
	eq("show_digits", m.last(), 0x01, 0x06, 0x5B, 0x4F, 0x66)
	_ = d.ShowDigits([4]int{SAA1064Blank, 0, 9, 8})
	eq("show_digits_blank", m.last(), 0x01, 0x00, 0x3F, 0x6F, 0x7F)
	rangeErr("show_digits_range", d.ShowDigits([4]int{10, 0, 0, 0}))

	_ = d.ShowNumber(42)
	eq("show_number", m.last(), 0x01, 0x00, 0x00, 0x66, 0x5B)
	_ = d.ShowNumber(0)
	eq("show_number_zero", m.last(), 0x01, 0x00, 0x00, 0x00, 0x3F)
	_ = d.ShowNumber(9999)
	eq("show_number_max", m.last(), 0x01, 0x6F, 0x6F, 0x6F, 0x6F)
	rangeErr("show_number_high", d.ShowNumber(10000))
	rangeErr("show_number_low", d.ShowNumber(-1))

	_ = d.Clear()
	eq("clear", m.last(), 0x01, 0, 0, 0, 0)
	_ = d.WriteRaw([]byte{0xAA, 0x55}, 2)
	eq("write_raw", m.last(), 0x02, 0xAA, 0x55)
	rangeErr("write_raw_overflow", d.WriteRaw([]byte{1, 2, 3}, 3))
	rangeErr("write_raw_first", d.WriteRaw([]byte{1}, 0))
	rangeErr("write_raw_empty", d.WriteRaw(nil, 1))

	_ = d.SetMode(false)
	eq("mode_static", m.last(), 0x00, 0x46)
	_ = d.SetMode(true)
	eq("mode_dynamic", m.last(), 0x00, 0x47)
	_ = d.Blank(true, false)
	eq("blank_13", m.last(), 0x00, 0x45)
	_ = d.Blank(true, true)
	eq("blank_both", m.last(), 0x00, 0x41)
	_ = d.Blank(false, false)
	eq("blank_none", m.last(), 0x00, 0x47)
	_ = d.SetCurrent(21)
	eq("current_21", m.last(), 0x00, 0x77)
	_ = d.SetCurrent(0)
	eq("current_0", m.last(), 0x00, 0x07)
	_ = d.SetCurrent(12)
	eq("current_12", m.last(), 0x00, 0x47)
	rangeErr("current_invalid", d.SetCurrent(10))
	_ = d.SegmentTest(true)
	eq("test_on", m.last(), 0x00, 0x4F)
	_ = d.SegmentTest(false)
	eq("test_off", m.last(), 0x00, 0x47)
	_ = d.WriteControl(0xFF)
	eq("write_control_bit7", m.last(), 0x00, 0x7F)
	_ = d.WriteControl(0x47)

	_ = d.ShowDigit(2, 7, true)
	eq("show_digit_dp", m.last(), 0x02, 0x87)
	_ = d.SetDecimalPoint(2, false)
	eq("dp_off", m.last(), 0x02, 0x07)
	_ = d.SetDecimalPoint(3, true)
	eq("dp_on", m.last(), 0x03, 0xD5)
	rangeErr("show_digit_position", d.ShowDigit(5, 1, false))
	rangeErr("dp_position", d.SetDecimalPoint(0, true))

	if err := d.SetSegmentMap([8]uint8{7, 6, 5, 4, 3, 2, 1, 0}); err != nil {
		t.Fatal(err)
	}
	_ = d.ShowDigits([4]int{1, 1, 1, 1})
	eq("segment_map", m.last(), 0x01, 0x60, 0x60, 0x60, 0x60)
	rangeErr("segment_map_range", d.SetSegmentMap([8]uint8{8, 1, 2, 3, 4, 5, 6, 7}))

	m.reads = [][]byte{{0x80}, {0x80}, {0x00}}
	if s, _ := d.ReadStatus(); s != 0x80 {
		t.Errorf("read_status: got %02X", s)
	}
	if f, _ := d.PowerResetFlag(); !f {
		t.Error("power_reset_flag set")
	}
	if f, _ := d.PowerResetFlag(); f {
		t.Error("power_reset_flag clear")
	}
}
