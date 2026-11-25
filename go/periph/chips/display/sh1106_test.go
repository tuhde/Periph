package display

import (
	"bytes"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// sh1106Mock is an in-memory fake connection.Connection: every write is logged, reads pop a FIFO queue.
type sh1106Mock struct {
	writes [][]byte
	reads  [][]byte
}

func (m *sh1106Mock) Write(data []byte) error {
	m.writes = append(m.writes, append([]byte(nil), data...))
	return nil
}

func (m *sh1106Mock) Read(n int) ([]byte, error) {
	out := make([]byte, n)
	if len(m.reads) > 0 {
		copy(out, m.reads[0])
		m.reads = m.reads[1:]
	}
	return out, nil
}

func (m *sh1106Mock) queue(b ...byte)                           { m.reads = append(m.reads, b) }
func (m *sh1106Mock) WriteRead(d []byte, n int) ([]byte, error) { return m.Read(n) }
func (m *sh1106Mock) Close() error                              { return nil }
func (m *sh1106Mock) Enable()                                   {}
func (m *sh1106Mock) Disable()                                  {}
func (m *sh1106Mock) IsEnabled() bool                           { return true }
func (m *sh1106Mock) IntPin() connection.InputPin               { return nil }
func (m *sh1106Mock) EnPin() connection.OutputPin               { return nil }
func (m *sh1106Mock) last() []byte                              { return m.writes[len(m.writes)-1] }

var sh1106Setup = []byte{0x00, 0xAE, 0xD5, 0x50, 0xA8, 0x3F, 0xD3, 0x00, 0x40, 0xA0, 0xC0, 0xDA, 0x12, 0x81, 0x80,
	0xD9, 0x22, 0xDB, 0x35, 0x30, 0xA4, 0xA6}

func sh1106NewFull(t *testing.T) (*sh1106Mock, *SH1106Full) {
	m := &sh1106Mock{}
	chip, err := NewSH1106Full(m, 0, 0, 2, true)
	if err != nil {
		t.Fatalf("NewSH1106Full: %v", err)
	}
	return m, chip
}

func TestSH1106Init(t *testing.T) {
	m := &sh1106Mock{}
	lcd, err := NewSH1106Minimal(m, 0, 0, 2, true)
	if err != nil {
		t.Fatal(err)
	}
	if lcd.Width() != 128 || lcd.Height() != 64 {
		t.Errorf("geometry %dx%d", lcd.Width(), lcd.Height())
	}
	eq(t, "setup stream", m.writes[0], sh1106Setup...)
	eq(t, "dcdc on", m.writes[1], 0x00, 0xAD, 0x8B)
	eq(t, "page0 address", m.writes[2], 0x00, 0xB0, 0x00, 0x10)
	sizes := []int{}
	for _, w := range m.writes[3:8] {
		if w[0] != 0x40 || bytes.Count(w[1:], []byte{0}) != len(w)-1 {
			t.Errorf("clear chunk not a zero 0x40 chunk: % X", w)
		}
		sizes = append(sizes, len(w)-1)
	}
	if want := []int{31, 31, 31, 31, 8}; !equalInts(sizes, want) {
		t.Errorf("chunk sizes %v, want %v", sizes, want)
	}
	if len(m.writes) != 2+8*6+1 {
		t.Errorf("init writes = %d, want %d", len(m.writes), 2+8*6+1)
	}
	eq(t, "page7 address", m.writes[2+7*6], 0x00, 0xB7, 0x00, 0x10)
	eq(t, "display on last", m.last(), 0x00, 0xAF)
}

func equalInts(a, b []int) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

func TestSH1106PixelsAndShow(t *testing.T) {
	m := &sh1106Mock{}
	lcd, _ := NewSH1106Minimal(m, 0, 0, 2, true)
	n0 := len(m.writes)
	lcd.SetPixel(3, 10, true)
	lcd.SetPixel(127, 63, true)
	lcd.SetPixel(128, 0, true)
	lcd.SetPixel(-1, 5, true)
	if len(m.writes) != n0 {
		t.Error("SetPixel must not touch the bus")
	}
	if err := lcd.Show(); err != nil {
		t.Fatal(err)
	}
	if len(m.writes)-n0 != 48 {
		t.Fatalf("show writes = %d, want 48", len(m.writes)-n0)
	}
	eq(t, "page1 address", m.writes[n0+6], 0x00, 0xB1, 0x02, 0x10)
	if m.writes[n0+7][1+3] != 0x04 {
		t.Error("pixel (3,10) not at page1 col3 bit2")
	}
	if w := m.writes[n0+47]; w[len(w)-1] != 0x80 {
		t.Error("pixel (127,63) not at page7 col127 bit7")
	}
	lcd.Clear()
	n0 = len(m.writes)
	lcd.Show()
	for _, w := range m.writes[n0:] {
		if w[0] == 0x40 && bytes.Count(w[1:], []byte{0}) != len(w)-1 {
			t.Error("Clear did not zero the framebuffer")
		}
	}
}

func TestSH1106OptionsAndValidation(t *testing.T) {
	m := &sh1106Mock{}
	if _, err := NewSH1106Minimal(m, 128, 32, 0, false); err != nil {
		t.Fatal(err)
	}
	eq(t, "external dcdc", m.writes[1], 0x00, 0xAD, 0x8A)
	if m.writes[0][4] != 0xA8 || m.writes[0][5] != 0x1F {
		t.Error("multiplex for height 32 should be 0x1F")
	}
	m2 := &sh1106Mock{}
	narrow, _ := NewSH1106Minimal(m2, 100, 64, 16, true)
	n0 := len(m2.writes)
	narrow.Show()
	eq(t, "column offset 16", m2.writes[n0], 0x00, 0xB0, 0x00, 0x11)
	for _, a := range [][3]int{{133, 64, 0}, {128, 60, 0}, {128, 72, 0}, {128, 64, 5}, {-1, 64, 0}} {
		bad := &sh1106Mock{}
		if _, err := NewSH1106Minimal(bad, a[0], a[1], a[2], true); err != ErrSH1106Argument {
			t.Errorf("%v: err = %v, want ErrSH1106Argument", a, err)
		}
		if len(bad.writes) != 0 {
			t.Errorf("%v: bus traffic on invalid arguments", a)
		}
	}
}

func TestSH1106Commands(t *testing.T) {
	m, c := sh1106NewFull(t)
	cases := []struct {
		name string
		fn   func() error
		want []byte
	}{
		{"contrast", func() error { return c.SetContrast(0x55) }, []byte{0x00, 0x81, 0x55}},
		{"display off", func() error { return c.SetDisplay(false) }, []byte{0x00, 0xAE}},
		{"display on", func() error { return c.SetDisplay(true) }, []byte{0x00, 0xAF}},
		{"sleep", c.Sleep, []byte{0x00, 0xAE}},
		{"wake", c.Wake, []byte{0x00, 0xAF}},
		{"invert on", func() error { return c.SetInvert(true) }, []byte{0x00, 0xA7}},
		{"invert off", func() error { return c.SetInvert(false) }, []byte{0x00, 0xA6}},
		{"all on", func() error { return c.SetAllOn(true) }, []byte{0x00, 0xA5}},
		{"all off", func() error { return c.SetAllOn(false) }, []byte{0x00, 0xA4}},
		{"flip both", func() error { return c.SetFlip(true, true) }, []byte{0x00, 0xA1, 0xC8}},
		{"flip vertical", func() error { return c.SetFlip(false, true) }, []byte{0x00, 0xA0, 0xC8}},
		{"flip none", func() error { return c.SetFlip(false, false) }, []byte{0x00, 0xA0, 0xC0}},
		{"start line", func() error { return c.SetStartLine(40) }, []byte{0x00, 0x68}},
		{"display offset", func() error { return c.SetDisplayOffset(12) }, []byte{0x00, 0xD3, 12}},
		{"multiplex", func() error { return c.SetMultiplex(48) }, []byte{0x00, 0xA8, 47}},
		{"clock", func() error { return c.SetClock(3, 8) }, []byte{0x00, 0xD5, 0x82}},
		{"precharge", func() error { return c.SetPrecharge(2, 5) }, []byte{0x00, 0xD9, 0x52}},
		{"com sequential", func() error { return c.SetComConfig(false) }, []byte{0x00, 0xDA, 0x02}},
		{"com alternative", func() error { return c.SetComConfig(true) }, []byte{0x00, 0xDA, 0x12}},
		{"vcom", func() error { return c.SetVcomDeselect(0x40) }, []byte{0x00, 0xDB, 0x40}},
		{"pump 9V", func() error { return c.SetPumpVoltage(3) }, []byte{0x00, 0x33}},
		{"dcdc off", func() error { return c.SetDCDC(false) }, []byte{0x00, 0xAD, 0x8A}},
		{"dcdc on", func() error { return c.SetDCDC(true) }, []byte{0x00, 0xAD, 0x8B}},
		{"raw", func() error { return c.SendCommand(0xE3, 0xE3) }, []byte{0x00, 0xE3, 0xE3}},
	}
	for _, tc := range cases {
		if err := tc.fn(); err != nil {
			t.Fatalf("%s: %v", tc.name, err)
		}
		eq(t, tc.name, m.last(), tc.want...)
	}
	n0 := len(m.writes)
	c.ShowPage(2)
	eq(t, "show_page 2", m.writes[n0], 0x00, 0xB2, 0x02, 0x10)
	if len(m.writes)-n0 != 6 {
		t.Errorf("show_page writes = %d, want 6", len(m.writes)-n0)
	}
	c.ShowPage(8)
	if len(m.writes)-n0 != 6 {
		t.Error("out-of-range page must be ignored")
	}
}

func TestSH1106Drawing(t *testing.T) {
	_, c := sh1106NewFull(t)
	c.Fill(true)
	if !c.GetPixel(0, 0) || !c.GetPixel(127, 63) {
		t.Error("fill on")
	}
	c.Fill(false)
	if c.GetPixel(0, 0) || c.GetPixel(-1, 0) || c.GetPixel(0, 64) {
		t.Error("fill off / out of range")
	}
	c.DrawLine(0, 0, 9, 9, true)
	if !c.GetPixel(0, 0) || !c.GetPixel(5, 5) || !c.GetPixel(9, 9) || c.GetPixel(5, 6) {
		t.Error("diagonal")
	}
	c.Fill(false)
	c.DrawRect(10, 20, 5, 4, true, false)
	if !c.GetPixel(10, 20) || !c.GetPixel(14, 23) || !c.GetPixel(12, 20) || c.GetPixel(12, 21) {
		t.Error("outline")
	}
	c.Fill(false)
	c.DrawRect(10, 20, 3, 3, true, true)
	if !c.GetPixel(11, 21) || !c.GetPixel(12, 22) || c.GetPixel(13, 22) {
		t.Error("filled")
	}
	c.Fill(false)
	if err := c.Blit(4, 8, []byte{0xFF, 0x0F, 0x01, 0x80}, 2); err != nil {
		t.Fatal(err)
	}
	if !c.GetPixel(4, 8) || !c.GetPixel(4, 15) || !c.GetPixel(5, 8) || c.GetPixel(5, 12) ||
		!c.GetPixel(5, 11) || !c.GetPixel(4, 16) || !c.GetPixel(5, 23) || c.GetPixel(4, 17) {
		t.Error("blit two page rows")
	}
	if err := c.Blit(4, 3, []byte{1, 2}, 1); err != ErrSH1106Argument {
		t.Errorf("unaligned blit err = %v", err)
	}
}

func TestSH1106Reads(t *testing.T) {
	m, c := sh1106NewFull(t)
	m.queue(0x40)
	s, err := c.ReadStatus()
	if err != nil || s != 0x40 {
		t.Errorf("ReadStatus = %#x, %v", s, err)
	}
	eq(t, "status control byte", m.last(), 0x00)
	m.queue(0xEE, 0x11, 0x22, 0x33)
	k := len(m.writes)
	ram, err := c.ReadPage(2, 5, 3)
	if err != nil {
		t.Fatal(err)
	}
	eq(t, "ram", ram, 0x11, 0x22, 0x33)
	eq(t, "read address", m.writes[k], 0x00, 0xB2, 0x05, 0x10)
	eq(t, "read data control byte", m.writes[k+1], 0x40)
}

func TestSH1106SPI(t *testing.T) {
	dc := connection.NewDisplayConnectionMock(connection.WireModeSimplex)
	lcd, err := NewSH1106SpiMinimal(dc, 0, 0, 2, true)
	if err != nil {
		t.Fatal(err)
	}
	if dc.Events[0].Kind != "reset" {
		t.Error("reset must come first")
	}
	var cmds []byte
	datas := 0
	for _, e := range dc.Events {
		switch e.Kind {
		case "command":
			if len(e.Data) != 0 {
				t.Error("arguments must be sent as commands")
			}
			cmds = append(cmds, e.Cmd)
		case "data":
			datas++
			if len(e.Data) != 132 {
				t.Errorf("clear data len %d", len(e.Data))
			}
		}
	}
	eq(t, "setup stream", cmds[:21], sh1106Setup[1:]...)
	eq(t, "dcdc", cmds[21:23], 0xAD, 0x8B)
	if cmds[len(cmds)-1] != 0xAF || datas != 8 {
		t.Errorf("last cmd %#x, data writes %d", cmds[len(cmds)-1], datas)
	}
	n := len(dc.Events)
	lcd.SetPixel(5, 0, true)
	lcd.Show()
	ev := dc.Events[n:]
	if ev[0].Cmd != 0xB0 || ev[1].Cmd != 0x02 || ev[2].Cmd != 0x10 {
		t.Error("show page 0 address")
	}
	if ev[3].Kind != "data" || len(ev[3].Data) != 128 || ev[3].Data[5] != 1 {
		t.Error("show page 0 data")
	}
	dc2 := connection.NewDisplayConnectionMock(connection.WireModeSimplex)
	f, _ := NewSH1106SpiFull(dc2, 0, 0, 2, true)
	if _, err := f.ReadStatus(); err != ErrSH1106Unsupported {
		t.Errorf("SPI ReadStatus err = %v", err)
	}
	if _, err := f.ReadPage(0, 0, 1); err != ErrSH1106Unsupported {
		t.Errorf("SPI ReadPage err = %v", err)
	}
	m := len(dc2.Events)
	f.SetContrast(0x20)
	if e := dc2.Events[m:]; len(e) != 2 || e[0].Cmd != 0x81 || e[1].Cmd != 0x20 {
		t.Error("SPI contrast should be two commands")
	}
}
