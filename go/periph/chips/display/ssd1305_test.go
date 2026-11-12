package display

import (
	"bytes"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// ssd1305Mock is an in-memory fake connection.Connection: every write is logged, reads pop a FIFO queue.
type ssd1305Mock struct {
	writes [][]byte
	reads  [][]byte
}

func (m *ssd1305Mock) Write(data []byte) error {
	m.writes = append(m.writes, append([]byte(nil), data...))
	return nil
}

func (m *ssd1305Mock) Read(n int) ([]byte, error) {
	out := make([]byte, n)
	if len(m.reads) > 0 {
		copy(out, m.reads[0])
		m.reads = m.reads[1:]
	}
	return out, nil
}

func (m *ssd1305Mock) queue(b ...byte)                           { m.reads = append(m.reads, b) }
func (m *ssd1305Mock) WriteRead(d []byte, n int) ([]byte, error) { return m.Read(n) }
func (m *ssd1305Mock) Close() error                              { return nil }
func (m *ssd1305Mock) Enable()                                   {}
func (m *ssd1305Mock) Disable()                                  {}
func (m *ssd1305Mock) IsEnabled() bool                           { return true }
func (m *ssd1305Mock) IntPin() connection.InputPin               { return nil }
func (m *ssd1305Mock) EnPin() connection.OutputPin               { return nil }
func (m *ssd1305Mock) last() []byte                              { return m.writes[len(m.writes)-1] }

var ssd1305Setup = []byte{0x00, 0xAE, 0xD5, 0x70, 0xA8, 0x3F, 0xD3, 0x00, 0x40, 0xA0, 0xC0, 0xDA, 0x12, 0x81, 0x80,
	0xD9, 0x22, 0xDB, 0x34, 0xAD, 0x8E, 0xA4, 0xA6, 0x20, 0x02}

func ssd1305NewFull(t *testing.T) (*ssd1305Mock, *SSD1305Full) {
	m := &ssd1305Mock{}
	chip, err := NewSSD1305Full(m, 0, 0, 4)
	if err != nil {
		t.Fatalf("NewSSD1305Full: %v", err)
	}
	return m, chip
}

func TestSSD1305Init(t *testing.T) {
	m := &ssd1305Mock{}
	lcd, err := NewSSD1305Minimal(m, 0, 0, 4)
	if err != nil {
		t.Fatal(err)
	}
	if lcd.Width() != 128 || lcd.Height() != 64 {
		t.Errorf("geometry %dx%d", lcd.Width(), lcd.Height())
	}
	eq(t, "setup stream", m.writes[0], ssd1305Setup...)
	eq(t, "page0 address", m.writes[1], 0x00, 0xB0, 0x00, 0x10)
	sizes := []int{}
	for _, w := range m.writes[2:7] {
		if w[0] != 0x40 || bytes.Count(w[1:], []byte{0}) != len(w)-1 {
			t.Errorf("clear chunk not a zero 0x40 chunk: % X", w)
		}
		sizes = append(sizes, len(w)-1)
	}
	if want := []int{31, 31, 31, 31, 8}; !ssd1305EqualInts(sizes, want) {
		t.Errorf("chunk sizes %v, want %v", sizes, want)
	}
	if len(m.writes) != 1+8*6+1 {
		t.Errorf("init writes = %d, want %d", len(m.writes), 1+8*6+1)
	}
	eq(t, "page7 address", m.writes[1+7*6], 0x00, 0xB7, 0x00, 0x10)
	eq(t, "display on last", m.last(), 0x00, 0xAF)
}

func ssd1305EqualInts(a, b []int) bool {
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

func TestSSD1305PixelsAndShow(t *testing.T) {
	m := &ssd1305Mock{}
	lcd, _ := NewSSD1305Minimal(m, 0, 0, 4)
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
	eq(t, "page1 address", m.writes[n0+6], 0x00, 0xB1, 0x04, 0x10)
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

func TestSSD1305OptionsAndValidation(t *testing.T) {
	m := &ssd1305Mock{}
	if _, err := NewSSD1305Minimal(m, 128, 32, 0); err != nil {
		t.Fatal(err)
	}
	if m.writes[0][11] != 0xDA || m.writes[0][12] != 0x02 {
		t.Error("height 32 should use sequential COM wiring")
	}
	if m.writes[0][4] != 0xA8 || m.writes[0][5] != 0x1F {
		t.Error("multiplex for height 32 should be 0x1F")
	}
	m2 := &ssd1305Mock{}
	narrow, _ := NewSSD1305Minimal(m2, 100, 64, 16)
	n0 := len(m2.writes)
	narrow.Show()
	eq(t, "column offset 16", m2.writes[n0], 0x00, 0xB0, 0x00, 0x11)
	for _, a := range [][3]int{{133, 64, 0}, {128, 60, 0}, {128, 8, 0}, {128, 72, 0}, {128, 64, 5}, {-1, 64, 0}} {
		bad := &ssd1305Mock{}
		if _, err := NewSSD1305Minimal(bad, a[0], a[1], a[2]); err != ErrSSD1305Argument {
			t.Errorf("%v: err = %v, want ErrSSD1305Argument", a, err)
		}
		if len(bad.writes) != 0 {
			t.Errorf("%v: bus traffic on invalid arguments", a)
		}
	}
}

func TestSSD1305Commands(t *testing.T) {
	m, c := ssd1305NewFull(t)
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
		{"com sequential", func() error { return c.SetComConfig(false, false) }, []byte{0x00, 0xDA, 0x02}},
		{"com alternative", func() error { return c.SetComConfig(true, false) }, []byte{0x00, 0xDA, 0x12}},
		{"com alternative remap", func() error { return c.SetComConfig(true, true) }, []byte{0x00, 0xDA, 0x32}},
		{"vcomh masked", func() error { return c.SetVcomh(0xFF) }, []byte{0x00, 0xDB, 0x3C}},
		{"dim mode", func() error { return c.SetDimMode(0x40, 0x20) }, []byte{0x00, 0xAB, 0x00, 0x40, 0x20}},
		{"dim on", func() error { return c.SetDim(true) }, []byte{0x00, 0xAC}},
		{"dim off", func() error { return c.SetDim(false) }, []byte{0x00, 0xAF}},
		{"brightness", func() error { return c.SetBrightness(0x33) }, []byte{0x00, 0x82, 0x33}},
		{"lut", func() error { return c.SetLUT(49, 63, 40, 31) }, []byte{0x00, 0x91, 49, 63, 40, 31}},
		{"bank colors 1-16", func() error { return c.SetBankColors([]byte{1, 2, 3, 4}, nil) }, []byte{0x00, 0x92, 1, 2, 3, 4}},
		{"bank colors 17-32", func() error { return c.SetBankColors([]byte{1, 2, 3, 4}, []byte{5, 6, 7, 8}) }, []byte{0x00, 0x93, 5, 6, 7, 8}},
		{"area color", func() error { return c.SetAreaColor(true, true) }, []byte{0x00, 0xD8, 0x35}},
		{"scroll h right", func() error { return c.SetScrollHorizontal(true, 0, 7, 0, 1) }, []byte{0x00, 0x26, 1, 0, 0, 7}},
		{"scroll h left 3 frames", func() error { return c.SetScrollHorizontal(false, 1, 3, 4, 2) }, []byte{0x00, 0x27, 2, 1, 4, 3}},
		{"scroll v", func() error { return c.SetScrollVertical(true, 0, 7, 6, 1, 5) }, []byte{0x00, 0x29, 1, 0, 6, 7, 5}},
		{"scroll area", func() error { return c.SetScrollArea(8, 40) }, []byte{0x00, 0xA3, 8, 40}},
		{"scroll start", c.ScrollStart, []byte{0x00, 0x2F}},
		{"raw", func() error { return c.SendCommand(0xE3, 0xE3) }, []byte{0x00, 0xE3, 0xE3}},
	}
	for _, tc := range cases {
		if err := tc.fn(); err != nil {
			t.Fatalf("%s: %v", tc.name, err)
		}
		eq(t, tc.name, m.last(), tc.want...)
	}
	n0 := len(m.writes)
	c.ScrollStop()
	eq(t, "scroll stop", m.writes[n0], 0x00, 0x2E)
	if len(m.writes)-n0 != 1+48 {
		t.Errorf("scroll stop must rewrite the RAM, writes = %d", len(m.writes)-n0)
	}
	c.SetAddressingMode(SSD1305AddrHorizontal)
	eq(t, "addr mode horizontal", m.last(), 0x00, 0x20, 0x00)
	n0 = len(m.writes)
	c.Show()
	eq(t, "horizontal ranges", m.writes[n0], 0x00, 0x21, 4, 131, 0x22, 0, 7)
	total := 0
	for _, w := range m.writes[n0+1:] {
		total += len(w) - 1
	}
	if total != 128*8 {
		t.Errorf("horizontal data stream = %d bytes", total)
	}
	c.SetAddressingMode(SSD1305AddrVertical)
	eq(t, "addr mode vertical", m.last(), 0x00, 0x20, 0x01)
	c.SetPixel(0, 9, true)
	n0 = len(m.writes)
	c.Show()
	if m.writes[n0+1][1] != 0x00 || m.writes[n0+1][2] != 0x02 {
		t.Error("vertical mode must stream column-major")
	}
	c.SetPixel(0, 9, false)
	c.SetAddressingMode(SSD1305AddrPage)
	eq(t, "addr mode page", m.last(), 0x00, 0x20, 0x02)
	if c.SetAddressingMode(3) != ErrSSD1305Argument {
		t.Error("invalid addressing mode must be rejected")
	}
	n0 = len(m.writes)
	c.ShowPage(2)
	eq(t, "show_page 2", m.writes[n0], 0x00, 0xB2, 0x04, 0x10)
	if len(m.writes)-n0 != 6 {
		t.Errorf("show_page writes = %d, want 6", len(m.writes)-n0)
	}
	c.ShowPage(8)
	if len(m.writes)-n0 != 6 {
		t.Error("out-of-range page must be ignored")
	}
}

func TestSSD1305Drawing(t *testing.T) {
	_, c := ssd1305NewFull(t)
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
	if err := c.Blit(4, 3, []byte{1, 2}, 1); err != ErrSSD1305Argument {
		t.Errorf("unaligned blit err = %v", err)
	}
}

func TestSSD1305Reads(t *testing.T) {
	m, c := ssd1305NewFull(t)
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

func TestSSD1305SPI(t *testing.T) {
	dc := connection.NewDisplayConnectionMock(connection.WireModeSimplex)
	lcd, err := NewSSD1305SpiMinimal(dc, 0, 0, 4)
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
	eq(t, "setup stream", cmds[:24], ssd1305Setup[1:]...)
	if cmds[len(cmds)-1] != 0xAF || datas != 8 {
		t.Errorf("last cmd %#x, data writes %d", cmds[len(cmds)-1], datas)
	}
	n := len(dc.Events)
	lcd.SetPixel(5, 0, true)
	lcd.Show()
	ev := dc.Events[n:]
	if ev[0].Cmd != 0xB0 || ev[1].Cmd != 0x04 || ev[2].Cmd != 0x10 {
		t.Error("show page 0 address")
	}
	if ev[3].Kind != "data" || len(ev[3].Data) != 128 || ev[3].Data[5] != 1 {
		t.Error("show page 0 data")
	}
	dc2 := connection.NewDisplayConnectionMock(connection.WireModeSimplex)
	f, _ := NewSSD1305SpiFull(dc2, 0, 0, 4)
	if _, err := f.ReadStatus(); err != ErrSSD1305Unsupported {
		t.Errorf("SPI ReadStatus err = %v", err)
	}
	if _, err := f.ReadPage(0, 0, 1); err != ErrSSD1305Unsupported {
		t.Errorf("SPI ReadPage err = %v", err)
	}
	m := len(dc2.Events)
	f.SetContrast(0x20)
	if e := dc2.Events[m:]; len(e) != 2 || e[0].Cmd != 0x81 || e[1].Cmd != 0x20 {
		t.Error("SPI contrast should be two commands")
	}
}
