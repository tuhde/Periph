package display

import (
	"bytes"
	"fmt"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// ssd1306Mock is an in-memory fake connection.Connection: every write is logged, reads pop a FIFO queue.
type ssd1306Mock struct {
	writes [][]byte
	reads  [][]byte
}

func (m *ssd1306Mock) Write(data []byte) error {
	m.writes = append(m.writes, append([]byte(nil), data...))
	return nil
}

func (m *ssd1306Mock) Read(n int) ([]byte, error) {
	out := make([]byte, n)
	if len(m.reads) > 0 {
		copy(out, m.reads[0])
		m.reads = m.reads[1:]
	}
	return out, nil
}

func (m *ssd1306Mock) WriteRead(d []byte, n int) ([]byte, error) { return m.Read(n) }
func (m *ssd1306Mock) Close() error                              { return nil }
func (m *ssd1306Mock) Enable()                                   {}
func (m *ssd1306Mock) Disable()                                  {}
func (m *ssd1306Mock) IsEnabled() bool                           { return true }
func (m *ssd1306Mock) IntPin() connection.InputPin               { return nil }
func (m *ssd1306Mock) EnPin() connection.OutputPin               { return nil }
func (m *ssd1306Mock) last() []byte                              { return m.writes[len(m.writes)-1] }

var ssd1306Setup = []byte{0x00, 0xAE, 0xD5, 0x80, 0xA8, 0x3F, 0xD3, 0x00, 0x40, 0x8D, 0x14, 0x20, 0x02, 0xA0, 0xC0,
	0xDA, 0x12, 0x81, 0x7F, 0xD9, 0x22, 0xDB, 0x20, 0xA4, 0xA6}

func ssd1306NewFull(t *testing.T) (*ssd1306Mock, *SSD1306Full) {
	m := &ssd1306Mock{}
	chip, err := NewSSD1306Full(m, 0, 0, false)
	if err != nil {
		t.Fatalf("NewSSD1306Full: %v", err)
	}
	return m, chip
}

func TestSSD1306Init(t *testing.T) {
	m := &ssd1306Mock{}
	lcd, err := NewSSD1306Minimal(m, 0, 0, false)
	if err != nil {
		t.Fatal(err)
	}
	if lcd.Width() != 128 || lcd.Height() != 64 {
		t.Errorf("geometry %dx%d", lcd.Width(), lcd.Height())
	}
	eq(t, "setup stream", m.writes[0], ssd1306Setup...)
	eq(t, "page0 address", m.writes[1], 0x00, 0xB0, 0x00, 0x10)
	sizes := []int{}
	for _, w := range m.writes[2:7] {
		if w[0] != 0x40 || bytes.Count(w[1:], []byte{0}) != len(w)-1 {
			t.Errorf("clear chunk not a zero 0x40 chunk: % X", w)
		}
		sizes = append(sizes, len(w)-1)
	}
	if want := []int{31, 31, 31, 31, 4}; fmt.Sprint(sizes) != fmt.Sprint(want) {
		t.Errorf("chunk sizes %v, want %v", sizes, want)
	}
	if len(m.writes) != 1+8*6+1 {
		t.Errorf("init writes = %d, want %d", len(m.writes), 1+8*6+1)
	}
	eq(t, "page7 address", m.writes[1+7*6], 0x00, 0xB7, 0x00, 0x10)
	eq(t, "display on last", m.last(), 0x00, 0xAF)
}

func TestSSD1306PixelsAndShow(t *testing.T) {
	m := &ssd1306Mock{}
	lcd, _ := NewSSD1306Minimal(m, 0, 0, false)
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
	eq(t, "page1 address", m.writes[n0+6], 0x00, 0xB1, 0x00, 0x10)
	if m.writes[n0+7][1+3] != 0x04 {
		t.Error("pixel (3,10) should be page 1 col 3 bit 2")
	}
	lastChunk := m.writes[n0+47]
	if lastChunk[len(lastChunk)-1] != 0x80 {
		t.Error("pixel (127,63) should be page 7 col 127 bit 7")
	}
	lcd.SetPixel(3, 10, false)
	lcd.Clear()
	n0 = len(m.writes)
	lcd.Show()
	for _, w := range m.writes[n0:] {
		if w[0] == 0x40 && bytes.Count(w[1:], []byte{0}) != len(w)-1 {
			t.Error("Clear did not zero the framebuffer")
		}
	}
}

func TestSSD1306OptionsAndValidation(t *testing.T) {
	m := &ssd1306Mock{}
	if _, err := NewSSD1306Minimal(m, 128, 32, true); err != nil {
		t.Fatal(err)
	}
	if m.writes[0][9] != 0x8D || m.writes[0][10] != 0x10 {
		t.Error("external VCC must keep the charge pump off")
	}
	if m.writes[0][15] != 0xDA || m.writes[0][16] != 0x02 {
		t.Error("height 32 should use sequential COM wiring")
	}
	if m.writes[0][4] != 0xA8 || m.writes[0][5] != 0x1F {
		t.Error("multiplex for height 32 should be 0x1F")
	}
	for _, a := range [][2]int{{129, 64}, {128, 60}, {128, 8}, {128, 72}, {-1, 64}} {
		bad := &ssd1306Mock{}
		if _, err := NewSSD1306Minimal(bad, a[0], a[1], false); err != ErrSSD1306Argument {
			t.Errorf("%v: err = %v, want ErrSSD1306Argument", a, err)
		}
		if len(bad.writes) != 0 {
			t.Errorf("%v: bus traffic on invalid arguments", a)
		}
	}
}

func TestSSD1306Commands(t *testing.T) {
	m, c := ssd1306NewFull(t)
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
		{"vcomh masked", func() error { return c.SetVcomh(0xFF) }, []byte{0x00, 0xDB, 0x70}},
		{"charge pump on", func() error { return c.SetChargePump(true) }, []byte{0x00, 0x8D, 0x14}},
		{"charge pump off", func() error { return c.SetChargePump(false) }, []byte{0x00, 0x8D, 0x10}},
		{"scroll h right", func() error { return c.SetScrollHorizontal(true, 0, 7, 7) }, []byte{0x00, 0x26, 0x00, 0, 7, 7, 0x00, 0xFF}},
		{"scroll h left", func() error { return c.SetScrollHorizontal(false, 1, 3, 4) }, []byte{0x00, 0x27, 0x00, 1, 4, 3, 0x00, 0xFF}},
		{"scroll diagonal", func() error { return c.SetScrollVertical(true, 0, 7, 6, 5) }, []byte{0x00, 0x29, 0x00, 0, 6, 7, 5}},
		{"scroll area", func() error { return c.SetScrollArea(8, 40) }, []byte{0x00, 0xA3, 8, 40}},
		{"raw", func() error { return c.SendCommand(0xE3, 0xE3) }, []byte{0x00, 0xE3, 0xE3}},
		{"scroll start", c.ScrollStart, []byte{0x00, 0x2F}},
	}
	for _, tc := range cases {
		if err := tc.fn(); err != nil {
			t.Fatalf("%s: %v", tc.name, err)
		}
		eq(t, tc.name, m.last(), tc.want...)
	}

	// Scrolling blocks RAM access; ScrollStop rewrites the RAM.
	n0 := len(m.writes)
	if c.Show() != ErrSSD1306ScrollActive || c.ShowPage(0) != ErrSSD1306ScrollActive {
		t.Error("Show/ShowPage must be refused while scrolling")
	}
	if len(m.writes) != n0 {
		t.Error("refused calls must not touch the bus")
	}
	c.ScrollStop()
	eq(t, "scroll stop", m.writes[n0], 0x00, 0x2E)
	if len(m.writes)-n0 != 1+48 {
		t.Errorf("scroll stop must rewrite the RAM, writes = %d", len(m.writes)-n0)
	}

	c.SetAddressingMode(SSD1306AddrHorizontal)
	eq(t, "addr mode horizontal", m.last(), 0x00, 0x20, 0x00)
	n0 = len(m.writes)
	c.Show()
	eq(t, "horizontal ranges", m.writes[n0], 0x00, 0x21, 0, 127, 0x22, 0, 7)
	total := 0
	for _, w := range m.writes[n0+1:] {
		total += len(w) - 1
	}
	if total != 128*8 {
		t.Errorf("horizontal data stream = %d bytes", total)
	}
	c.SetAddressingMode(SSD1306AddrVertical)
	eq(t, "addr mode vertical", m.last(), 0x00, 0x20, 0x01)
	c.SetPixel(0, 9, true)
	n0 = len(m.writes)
	c.Show()
	if m.writes[n0+1][1] != 0x00 || m.writes[n0+1][2] != 0x02 {
		t.Error("vertical mode must stream column-major")
	}
	c.SetPixel(0, 9, false)
	n0 = len(m.writes)
	c.ShowPage(2)
	eq(t, "show_page vertical range", m.writes[n0], 0x00, 0x21, 0, 127, 0x22, 2, 2)
	c.SetAddressingMode(SSD1306AddrPage)
	eq(t, "addr mode page", m.last(), 0x00, 0x20, 0x02)
	if c.SetAddressingMode(3) != ErrSSD1306Argument {
		t.Error("invalid addressing mode must be rejected")
	}
	n0 = len(m.writes)
	c.ShowPage(2)
	eq(t, "show_page 2", m.writes[n0], 0x00, 0xB2, 0x00, 0x10)
	if len(m.writes)-n0 != 6 {
		t.Errorf("show_page writes = %d, want 6", len(m.writes)-n0)
	}
	c.ShowPage(8)
	if len(m.writes)-n0 != 6 {
		t.Error("out-of-range page must be ignored")
	}
}

func TestSSD1306Drawing(t *testing.T) {
	_, c := ssd1306NewFull(t)
	c.Fill(true)
	if !c.GetPixel(0, 0) || !c.GetPixel(127, 63) {
		t.Error("Fill(true)")
	}
	c.Fill(false)
	if c.GetPixel(0, 0) || c.GetPixel(-1, 0) || c.GetPixel(0, 64) {
		t.Error("Fill(false) / out of range")
	}
	c.DrawLine(0, 0, 9, 9, true)
	if !c.GetPixel(0, 0) || !c.GetPixel(5, 5) || !c.GetPixel(9, 9) || c.GetPixel(5, 6) {
		t.Error("DrawLine diagonal")
	}
	c.Fill(false)
	c.DrawRect(10, 20, 5, 4, true, false)
	if !c.GetPixel(10, 20) || !c.GetPixel(14, 23) || !c.GetPixel(12, 20) || c.GetPixel(12, 21) {
		t.Error("DrawRect outline")
	}
	c.Fill(false)
	c.DrawRect(10, 20, 3, 3, true, true)
	if !c.GetPixel(11, 21) || !c.GetPixel(12, 22) || c.GetPixel(13, 22) {
		t.Error("DrawRect filled")
	}
	c.Fill(false)
	if err := c.Blit(4, 8, []byte{0xFF, 0x0F, 0x01, 0x80}, 2); err != nil {
		t.Fatal(err)
	}
	if !c.GetPixel(4, 8) || !c.GetPixel(4, 15) || !c.GetPixel(5, 8) || c.GetPixel(5, 12) ||
		!c.GetPixel(5, 11) || !c.GetPixel(4, 16) || !c.GetPixel(5, 23) || c.GetPixel(4, 17) {
		t.Error("Blit two page rows")
	}
	if c.Blit(4, 3, []byte{1, 2}, 1) != ErrSSD1306Argument {
		t.Error("Blit with unaligned y must fail")
	}
}

func TestSSD1306SPI(t *testing.T) {
	dc := connection.NewDisplayConnectionMock(connection.WireModeSimplex)
	lcd, err := NewSSD1306SpiMinimal(dc, 0, 0, false)
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
			if len(e.Data) != 128 {
				t.Errorf("clear data len %d", len(e.Data))
			}
		}
	}
	eq(t, "setup stream", cmds[:24], ssd1306Setup[1:]...)
	if cmds[len(cmds)-1] != 0xAF || datas != 8 {
		t.Errorf("last cmd %#x, data writes %d", cmds[len(cmds)-1], datas)
	}
	n := len(dc.Events)
	lcd.SetPixel(5, 0, true)
	lcd.Show()
	ev := dc.Events[n:]
	if ev[0].Cmd != 0xB0 || ev[1].Cmd != 0x00 || ev[2].Cmd != 0x10 {
		t.Error("show page 0 address")
	}
	if ev[3].Kind != "data" || len(ev[3].Data) != 128 || ev[3].Data[5] != 1 {
		t.Error("show page 0 data")
	}
	dc2 := connection.NewDisplayConnectionMock(connection.WireModeSimplex)
	f, _ := NewSSD1306SpiFull(dc2, 0, 0, false)
	m := len(dc2.Events)
	f.SetContrast(0x20)
	if e := dc2.Events[m:]; len(e) != 2 || e[0].Cmd != 0x81 || e[1].Cmd != 0x20 {
		t.Error("SPI contrast should be two commands")
	}
	f.ScrollStart()
	if f.Show() != ErrSSD1306ScrollActive {
		t.Error("SPI Show must be refused while scrolling")
	}
}
