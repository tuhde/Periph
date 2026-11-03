package display

import (
	"fmt"
	"strings"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// st7036Trace formats the events from index `from`: R reset, I<hh> instruction,
// D<hh> data byte, d<ms> millisecond delay, u<us> microsecond delay.
func st7036Trace(t *testing.T, m *connection.DisplayConnectionMock, from int) string {
	t.Helper()
	var out []string
	for _, e := range m.Events[from:] {
		switch e.Kind {
		case "reset":
			out = append(out, "R")
		case "command":
			if len(e.Data) != 0 {
				t.Fatalf("instructions are single bytes, got %#x % x", e.Cmd, e.Data)
			}
			out = append(out, fmt.Sprintf("I%02X", e.Cmd))
		case "data":
			s := fmt.Sprintf("D%02X", e.Data[0])
			if len(e.Data) != 1 {
				s += "!"
			}
			out = append(out, s)
		case "delay":
			out = append(out, fmt.Sprintf("d%d", e.Timeout.Milliseconds()))
		case "delayMicros":
			out = append(out, fmt.Sprintf("u%d", e.Timeout.Microseconds()))
		default:
			out = append(out, "?")
		}
	}
	return strings.Join(out, " ")
}

func newST7036Mock() *connection.DisplayConnectionMock {
	return connection.NewDisplayConnectionMock(connection.WireModeSimplex)
}

func newST7036FullT(t *testing.T, lines int, supply ST7036Supply) (*ST7036Full, *connection.DisplayConnectionMock) {
	t.Helper()
	m := newST7036Mock()
	d, err := NewST7036Full(m, lines, 0, supply, -1)
	if err != nil {
		t.Fatal(err)
	}
	return d, m
}

const st7036Init162 = "R d40 I39 u30 I14 u30 I78 u30 I55 u30 I6D u30 d200 I38 u30 I0C u30 I01 d2 I06 u30"

func expectST7036(t *testing.T, label string, m *connection.DisplayConnectionMock, from int, want string) {
	t.Helper()
	if got := st7036Trace(t, m, from); got != want {
		t.Errorf("%s:\n got  %s\n want %s", label, got, want)
	}
}

func TestST7036MinimalInit(t *testing.T) {
	m := newST7036Mock()
	d, err := NewST7036Minimal(m)
	if err != nil {
		t.Fatal(err)
	}
	expectST7036(t, "init", m, 0, st7036Init162)
	if d.Columns() != 16 || d.Rows() != 2 {
		t.Errorf("geometry = %dx%d", d.Columns(), d.Rows())
	}
}

func TestST7036MinimalOperations(t *testing.T) {
	m := newST7036Mock()
	d, _ := NewST7036Minimal(m)
	step := func(label, want string, f func() error) {
		t.Helper()
		n := len(m.Events)
		if err := f(); err != nil {
			t.Fatal(err)
		}
		expectST7036(t, label, m, n, want)
	}
	step("clear", "I01 d2", d.Clear)
	step("home", "I02 d2", d.Home)
	step("set_cursor row1 col3", "IC3 u30", func() error { return d.SetCursor(3, 1) })
	step("set_cursor origin", "I80 u30", func() error { return d.SetCursor(0, 0) })
	step("out of range ignored", "", func() error {
		_ = d.SetCursor(16, 0)
		_ = d.SetCursor(0, 2)
		return d.SetCursor(-1, 0)
	})
	step("write", "D48 u30 D69 u30", func() error { return d.Write("Hi") })
	step("non-ASCII", "D61 u30 D3F u30 D7A u30 D3F u30", func() error { return d.Write("a\u00e4z~") })
	step("write_char", "D05 u30", func() error { return d.WriteChar(0x05) })
	step("print_at", "IC2 u30 D78 u30", func() error { return d.PrintAt(2, 1, "x") })
}

func TestST7036Presets(t *testing.T) {
	cases := []struct {
		lines  int
		supply ST7036Supply
		want   string
	}{
		{1, ST7036Supply3V3, "R d40 I31 u30 I14 u30 I7C u30 I55 u30 I6D u30 d200 I30 u30 I0C u30 I01 d2 I06 u30"},
		{1, ST7036Supply5V, "R d40 I31 u30 I1C u30 I74 u30 I51 u30 I6A u30 d200 I30 u30 I0C u30 I01 d2 I06 u30"},
		{2, ST7036Supply3V3, st7036Init162},
		{2, ST7036Supply5V, "R d40 I39 u30 I1C u30 I74 u30 I52 u30 I69 u30 d200 I38 u30 I0C u30 I01 d2 I06 u30"},
		{3, ST7036Supply3V3, "R d40 I39 u30 I15 u30 I72 u30 I55 u30 I6E u30 d200 I38 u30 I0C u30 I01 d2 I06 u30"},
		{3, ST7036Supply5V, "R d40 I39 u30 I1D u30 I7C u30 I50 u30 I6C u30 d200 I38 u30 I0C u30 I01 d2 I06 u30"},
	}
	for _, c := range cases {
		_, m := newST7036FullT(t, c.lines, c.supply)
		expectST7036(t, fmt.Sprintf("lines %d supply %d", c.lines, c.supply), m, 0, c.want)
	}
}

func TestST7036GeometryAndOverrides(t *testing.T) {
	d, m := newST7036FullT(t, 3, ST7036Supply3V3)
	if d.Columns() != 16 || d.Rows() != 3 {
		t.Errorf("dogm163 geometry = %dx%d", d.Columns(), d.Rows())
	}
	n := len(m.Events)
	_ = d.SetCursor(0, 2)
	expectST7036(t, "row 2 address", m, n, "IA0 u30")
	one, _ := newST7036FullT(t, 1, ST7036Supply3V3)
	if one.Columns() != 8 || one.Rows() != 1 {
		t.Errorf("dogm081 geometry = %dx%d", one.Columns(), one.Rows())
	}
	m2 := newST7036Mock()
	if _, err := NewST7036Full(m2, 2, 0, ST7036Supply3V3, 0x2A); err != nil {
		t.Fatal(err)
	}
	expectST7036(t, "contrast 0x2A", m2, 0, "R d40 I39 u30 I14 u30 I7A u30 I56 u30 I6D u30 d200 I38 u30 I0C u30 I01 d2 I06 u30")
	wide, err := NewST7036Full(newST7036Mock(), 2, 20, ST7036Supply3V3, -1)
	if err != nil || wide.Columns() != 20 {
		t.Errorf("columns override failed: %v", err)
	}
	if _, err := NewST7036Full(newST7036Mock(), 9, 0, ST7036Supply3V3, -1); err != ErrST7036Lines {
		t.Errorf("invalid lines error = %v", err)
	}
}

func TestST7036ControlInstructions(t *testing.T) {
	d, m := newST7036FullT(t, 2, ST7036Supply3V3)
	step := func(label, want string, f func() error) {
		t.Helper()
		n := len(m.Events)
		if err := f(); err != nil {
			t.Fatal(err)
		}
		expectST7036(t, label, m, n, want)
	}
	step("display off", "I08 u30", func() error { return d.Display(false) })
	step("display on", "I0C u30", func() error { return d.Display(true) })
	step("cursor+blink", "I0E u30 I0F u30", func() error { return d.Cursor(true, true) })
	step("cursor off", "I0D u30 I0C u30", func() error { return d.Cursor(false, false) })
	step("entry decrement shift", "I05 u30", func() error { return d.SetEntryMode(false, true) })
	step("entry increment", "I06 u30", func() error { return d.SetEntryMode(true, false) })
	step("shift cursor", "I14 u30 I10 u30", func() error { _ = d.ShiftCursor(true); return d.ShiftCursor(false) })
	step("shift display", "I1C u30 I18 u30", func() error { _ = d.ShiftDisplay(true); return d.ShiftDisplay(false) })
	step("return home", "I02 d2", d.ReturnHome)
	step("ddram address", "IC5 u30", func() error { return d.SetDDRAMAddress(0x45) })
	step("raw", "I1F u30 D41 u30", func() error { _ = d.SendCommand(0x1F); return d.SendData(0x41) })
}

func TestST7036ExtensionInstructions(t *testing.T) {
	d, m := newST7036FullT(t, 2, ST7036Supply3V3)
	step := func(label, want string, f func() error) {
		t.Helper()
		n := len(m.Events)
		if err := f(); err != nil {
			t.Fatal(err)
		}
		expectST7036(t, label, m, n, want)
	}
	step("contrast 0x2D", "I39 u30 I56 u30 I7D u30 I38 u30", func() error { return d.SetContrast(0x2D) })
	step("booster off", "I39 u30 I52 u30 I38 u30", func() error { return d.SetBooster(false) })
	step("booster on", "I39 u30 I56 u30 I38 u30", func() error { return d.SetBooster(true) })
	step("follower on 3", "I39 u30 I6B u30 I38 u30", func() error { return d.SetFollower(true, 3) })
	step("follower off 7", "I39 u30 I67 u30 I38 u30", func() error { return d.SetFollower(false, 7) })
	step("bias 1/4", "I39 u30 I1C u30 I38 u30", func() error { return d.SetBias(true) })
	step("bias 1/5", "I39 u30 I14 u30 I38 u30", func() error { return d.SetBias(false) })
	step("icon on", "I39 u30 I5E u30 I38 u30", func() error { return d.Icon(true) })
	step("write icon", "I39 u30 I43 u30 D15 u30 I38 u30 I80 u30", func() error { return d.WriteIcon(3, 0x15) })
	d3, m3 := newST7036FullT(t, 3, ST7036Supply3V3)
	n := len(m3.Events)
	_ = d3.SetBias(false)
	expectST7036(t, "FX kept on 3-line", m3, n, "I39 u30 I15 u30 I38 u30")
}

func TestST7036CustomCharsAndDoubleHeight(t *testing.T) {
	d, m := newST7036FullT(t, 2, ST7036Supply3V3)
	_ = d.SetCursor(5, 1)
	n := len(m.Events)
	glyph := [8]uint8{0x1F, 0x00, 0xFF, 0x01, 0x02, 0x04, 0x08, 0x10}
	_ = d.CreateChar(2, glyph)
	expectST7036(t, "create_char", m, n, "I50 u30 D1F u30 D00 u30 D1F u30 D01 u30 D02 u30 D04 u30 D08 u30 D10 u30 IC5 u30")
	n = len(m.Events)
	_ = d.CreateChar(9, glyph)
	if !strings.HasPrefix(st7036Trace(t, m, n), "I48 u30") {
		t.Errorf("index not masked: %s", st7036Trace(t, m, n))
	}
	n = len(m.Events)
	_ = d.SetDoubleHeight(true, true)
	expectST7036(t, "2-line ignored", m, n, "")
	one, m1 := newST7036FullT(t, 1, ST7036Supply3V3)
	n = len(m1.Events)
	_ = one.SetDoubleHeight(true, true)
	expectST7036(t, "1-line on", m1, n, "I34 u30 I34 u30")
	n = len(m1.Events)
	_ = one.SetDoubleHeight(false, true)
	expectST7036(t, "1-line off", m1, n, "I30 u30 I30 u30")
	three, m3 := newST7036FullT(t, 3, ST7036Supply3V3)
	n = len(m3.Events)
	_ = three.SetDoubleHeight(true, true)
	expectST7036(t, "3-line upper", m3, n, "I3E u30 I18 u30 I3C u30")
	n = len(m3.Events)
	_ = three.SetDoubleHeight(true, false)
	expectST7036(t, "3-line lower", m3, n, "I3E u30 I10 u30 I3C u30")
}
