package display

import (
	"bytes"
	"errors"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

var errNack = errors.New("i2c nack (mock)")

// uc1611sMock is an in-memory fake connection.Connection with NACK injection: every write is logged,
// reads pop a FIFO queue (zeros when empty).
type uc1611sMock struct {
	writes     [][]byte
	reads      [][]byte
	failWrites int
	failSkip   int
	failReads  int
}

func (m *uc1611sMock) Write(data []byte) error {
	m.writes = append(m.writes, append([]byte(nil), data...))
	if m.failSkip > 0 {
		m.failSkip--
	} else if m.failWrites > 0 {
		m.failWrites--
		return errNack
	}
	return nil
}

func (m *uc1611sMock) Read(n int) ([]byte, error) {
	if m.failReads > 0 {
		m.failReads--
		return nil, errNack
	}
	out := make([]byte, n)
	if len(m.reads) > 0 {
		copy(out, m.reads[0])
		m.reads = m.reads[1:]
	}
	return out, nil
}

func (m *uc1611sMock) queue(b ...byte)                           { m.reads = append(m.reads, b) }
func (m *uc1611sMock) WriteRead(d []byte, n int) ([]byte, error) { return m.Read(n) }
func (m *uc1611sMock) Close() error                              { return nil }
func (m *uc1611sMock) Enable()                                   {}
func (m *uc1611sMock) Disable()                                  {}
func (m *uc1611sMock) IsEnabled() bool                           { return true }
func (m *uc1611sMock) IntPin() connection.InputPin               { return nil }
func (m *uc1611sMock) EnPin() connection.OutputPin               { return nil }
func (m *uc1611sMock) last() []byte                              { return m.writes[len(m.writes)-1] }

var uc1611sDE = []byte{0xDA, 0x80, 0x10} // Status1 with DE set

func uc1611sPair() (*uc1611sMock, *uc1611sMock) {
	cmd, dat := &uc1611sMock{}, &uc1611sMock{}
	cmd.queue(uc1611sDE...) // DE set: init does not wait for the pump
	return cmd, dat
}

func uc1611sFull(t *testing.T) (*uc1611sMock, *uc1611sMock, *UC1611sFull) {
	cmd, dat := uc1611sPair()
	chip, err := NewUC1611sFull(cmd, dat, 0, 0, false, 0x8F)
	if err != nil {
		t.Fatalf("NewUC1611sFull: %v", err)
	}
	return cmd, dat, chip
}

func eq(t *testing.T, label string, got []byte, want ...byte) {
	t.Helper()
	if !bytes.Equal(got, want) {
		t.Errorf("%s = % X, want % X", label, got, want)
	}
}

func TestUC1611sInit(t *testing.T) {
	cmd, dat := uc1611sPair()
	lcd, err := NewUC1611sMinimal(cmd, dat, 0, 0)
	if err != nil {
		t.Fatal(err)
	}
	if lcd.Width() != 240 || lcd.Height() != 128 {
		t.Errorf("geometry %dx%d", lcd.Width(), lcd.Height())
	}
	if len(cmd.writes) != 24 { // reset, config, 20 pages, enable, NOP before the status poll
		t.Fatalf("command transactions = %d, want 24", len(cmd.writes))
	}
	eq(t, "reset", cmd.writes[0], 0xE2)
	eq(t, "configuration", cmd.writes[1], 0x25, 0xC0, 0x02, 0xA3, 0xF1, 0x7F, 0xF2, 0x00, 0xF3, 0x7F, 0x81, 0x8F, 0x89, 0xD1)
	eq(t, "page0", cmd.writes[2], 0x60, 0x70, 0x00, 0x10)
	eq(t, "page19", cmd.writes[21], 0x63, 0x71, 0x00, 0x10)
	eq(t, "enable", cmd.writes[22], 0xA9)
	eq(t, "status poll NOP", cmd.writes[23], 0xE3)
	if len(dat.writes) != 160 {
		t.Fatalf("data chunks = %d, want 160", len(dat.writes))
	}
	for i, w := range dat.writes {
		want := 32
		if i%8 == 7 {
			want = 16
		}
		if len(w) != want || !bytes.Equal(w, make([]byte, want)) {
			t.Errorf("chunk %d = %d bytes (zero=%v), want %d zero bytes", i, len(w), bytes.Equal(w, make([]byte, len(w))), want)
		}
	}
}

func TestUC1611sInvalidSizes(t *testing.T) {
	for _, s := range [][2]int{{241, 128}, {240, 130}, {240, 136}, {-1, 128}} {
		cmd, dat := uc1611sPair()
		if _, err := NewUC1611sMinimal(cmd, dat, s[0], s[1]); !errors.Is(err, ErrUC1611sArgument) {
			t.Errorf("%v: err = %v", s, err)
		}
		if len(cmd.writes) != 0 {
			t.Errorf("%v: bus traffic for an invalid size", s)
		}
	}
}

func TestUC1611sPixelsAndShow(t *testing.T) {
	cmd, dat := uc1611sPair()
	lcd, _ := NewUC1611sMinimal(cmd, dat, 0, 0)
	c0, d0 := len(cmd.writes), len(dat.writes)
	lcd.SetPixel(3, 10, true)
	lcd.SetPixel(239, 127, true)
	lcd.SetPixel(240, 0, true) // out of range, ignored
	lcd.SetPixel(-1, 5, true)
	if len(cmd.writes) != c0 || len(dat.writes) != d0 {
		t.Fatal("SetPixel touched the bus")
	}
	if err := lcd.Show(); err != nil {
		t.Fatal(err)
	}
	if len(cmd.writes)-c0 != 16 || len(dat.writes)-d0 != 128 {
		t.Fatalf("show used %d command / %d data transactions", len(cmd.writes)-c0, len(dat.writes)-d0)
	}
	eq(t, "page1 address", cmd.writes[c0+1], 0x61, 0x70, 0x00, 0x10)
	if dat.writes[d0+8][3] != 0x04 {
		t.Errorf("pixel (3,10) = %02X, want 04", dat.writes[d0+8][3])
	}
	if w := dat.writes[d0+127]; len(w) != 16 || w[15] != 0x80 {
		t.Errorf("pixel (239,127): len %d last %02X", len(w), w[len(w)-1])
	}
	lcd.Clear()
	d0 = len(dat.writes)
	_ = lcd.Show()
	for _, w := range dat.writes[d0:] {
		if !bytes.Equal(w, make([]byte, len(w))) {
			t.Fatal("Clear left pixels in the framebuffer")
		}
	}
}

func TestUC1611sCommands(t *testing.T) {
	cmd, _, chip := uc1611sFull(t)
	steps := []struct {
		name string
		do   func() error
		want []byte
	}{
		{"set_contrast", func() error { return chip.SetContrast(0x55) }, []byte{0x81, 0x55}},
		{"display off", func() error { return chip.SetDisplay(false) }, []byte{0xA8}},
		{"sleep", chip.Sleep, []byte{0xA8}},
		{"invert on", func() error { return chip.SetInvert(true) }, []byte{0xA7}},
		{"invert off", func() error { return chip.SetInvert(false) }, []byte{0xA6}},
		{"all on", func() error { return chip.SetAllOn(true) }, []byte{0xA5}},
		{"all off", func() error { return chip.SetAllOn(false) }, []byte{0xA4}},
		{"flip mx my", func() error { return chip.SetFlip(true, true) }, []byte{0xC0, 0x06}},
		{"flip my", func() error { return chip.SetFlip(false, true) }, []byte{0xC0, 0x04}},
		{"scroll 40", func() error { return chip.SetScrollLine(40) }, []byte{0x48, 0x52}},
		{"fixed lines", func() error { return chip.SetFixedLines(3) }, []byte{0x93}},
		{"bias", func() error { return chip.SetBiasRatio(3) }, []byte{0xEB}},
		{"temp comp", func() error { return chip.SetTemperatureCompensation(2) }, []byte{0x26}},
		{"line rate", func() error { return chip.SetLineRate(1) }, []byte{0xA1}},
		{"pump external", func() error { return chip.SetPump(false) }, []byte{0x2C}},
		{"pump internal", func() error { return chip.SetPump(true) }, []byte{0x2F}},
		{"panel high", func() error { return chip.SetPanelLoading(true) }, []byte{0x2B}},
		{"panel low", func() error { return chip.SetPanelLoading(false) }, []byte{0x28}},
		{"partial on", func() error { return chip.SetPartialDisplay(true, 10, 100) }, []byte{0xF2, 10, 0xF3, 100, 0x87}},
		{"partial default end", func() error { return chip.SetPartialDisplay(true, 4, -1) }, []byte{0xF2, 4, 0xF3, 127, 0x87}},
		{"partial off", func() error { return chip.SetPartialDisplay(false, 0, -1) }, []byte{0x84}},
		{"nline xor", func() error { return chip.SetNLineInversion(5, true) }, []byte{0xC8, 0x45}},
		{"pattern 2", func() error { return chip.SetDisplayPattern(2) }, []byte{0xD5}},
		{"pattern 0", func() error { return chip.SetDisplayPattern(0) }, []byte{0xD1}},
		{"raw command", func() error { return chip.SendCommand(0xE3, 0xE3) }, []byte{0xE3, 0xE3}},
	}
	for _, s := range steps {
		if err := s.do(); err != nil {
			t.Fatalf("%s: %v", s.name, err)
		}
		eq(t, s.name, cmd.last(), s.want...)
	}
	n := len(cmd.writes)
	if err := chip.SetIsolationClock(2, 3); err != nil {
		t.Fatal(err)
	}
	if len(cmd.writes)-n != 2 {
		t.Fatalf("isolation clock transactions = %d", len(cmd.writes)-n)
	}
	eq(t, "isolation front", cmd.writes[n], 0x82, 0x13, 0x02)
	eq(t, "isolation back", cmd.writes[n+1], 0x82, 0x14, 0x03)
}

func TestUC1611sWritePatternWakeResetAndRawData(t *testing.T) {
	cmd, dat, chip := uc1611sFull(t)
	d0 := len(dat.writes)
	chip.SetWritePattern(1)
	_ = chip.ShowPage(0)
	eq(t, "pattern 1 page 0", cmd.last(), 0x60, 0x72, 0x00, 0x10)
	if len(dat.writes)-d0 != 8 {
		t.Errorf("show page = %d chunks", len(dat.writes)-d0)
	}
	chip.SetWritePattern(0)
	n := len(dat.writes)
	_ = chip.ShowPage(16)
	if len(dat.writes) != n {
		t.Error("out-of-range page produced traffic")
	}
	n = len(dat.writes)
	if err := chip.SendData(make([]byte, 70)); err != nil {
		t.Fatal(err)
	}
	if len(dat.writes)-n != 3 || len(dat.writes[len(dat.writes)-1]) != 6 {
		t.Error("SendData is not chunked 32/32/6")
	}
	cmd.queue(uc1611sDE...)
	k := len(cmd.writes)
	_ = chip.Sleep()
	_ = chip.Wake()
	if cmd.writes[len(cmd.writes)-2][0] != 0xA9 || cmd.last()[0] != 0xE3 {
		t.Errorf("wake did not enable then poll: % X / % X", cmd.writes[len(cmd.writes)-2], cmd.last())
	}
	_ = k
	_ = chip.SetContrast(0x55)
	_ = chip.SetFlip(false, true)
	cmd.queue(uc1611sDE...)
	k = len(cmd.writes)
	if err := chip.Reset(); err != nil {
		t.Fatal(err)
	}
	if len(cmd.writes)-k != 4 {
		t.Fatalf("reset used %d transactions", len(cmd.writes)-k)
	}
	eq(t, "reset", cmd.writes[k], 0xE2)
	eq(t, "reconfigure keeps contrast and mapping", cmd.writes[k+1], 0x25, 0xC0, 0x04, 0xA3, 0xF1, 0x7F, 0xF2, 0x00, 0xF3, 0x7F, 0x81, 0x55, 0x89, 0xD1)
	eq(t, "enable", cmd.writes[k+2], 0xA9)
}

func TestUC1611sDrawing(t *testing.T) {
	_, _, c := uc1611sFull(t)
	c.Fill(true)
	if !c.GetPixel(0, 0) || !c.GetPixel(239, 127) {
		t.Error("Fill(true)")
	}
	c.Fill(false)
	if c.GetPixel(0, 0) || c.GetPixel(239, 127) || c.GetPixel(-1, 0) || c.GetPixel(0, 128) {
		t.Error("Fill(false) / out of range")
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
	if !c.GetPixel(4, 8) || !c.GetPixel(4, 15) || !c.GetPixel(5, 8) || c.GetPixel(5, 12) || !c.GetPixel(5, 11) ||
		!c.GetPixel(4, 16) || !c.GetPixel(5, 23) || c.GetPixel(4, 17) {
		t.Error("blit")
	}
	if err := c.Blit(4, 3, []byte{1, 2}, 1); !errors.Is(err, ErrUC1611sArgument) {
		t.Error("unaligned blit must fail")
	}
}

func TestUC1611sTopViewAndCustomSize(t *testing.T) {
	cmd, dat := uc1611sPair()
	if _, err := NewUC1611sFull(cmd, dat, 240, 128, true, 0x60); err != nil {
		t.Fatal(err)
	}
	eq(t, "top view", cmd.writes[1], 0x25, 0xC0, 0x04, 0xA3, 0xF1, 0x7F, 0xF2, 0x00, 0xF3, 0x7F, 0x81, 0x60, 0x89, 0xD1)
	cmd, dat = uc1611sPair()
	if _, err := NewUC1611sMinimal(cmd, dat, 100, 64); err != nil {
		t.Fatal(err)
	}
	eq(t, "64 rows", cmd.writes[1], 0x25, 0xC0, 0x02, 0xA3, 0xF1, 0x3F, 0xF2, 0x00, 0xF3, 0x3F, 0x81, 0x8F, 0x89, 0xD1)
}

func TestUC1611sReads(t *testing.T) {
	cmd, dat, chip := uc1611sFull(t)
	cmd.queue(0xDA, 0x80, 0x10)
	s, err := chip.ReadStatus()
	if err != nil {
		t.Fatal(err)
	}
	eq(t, "status", s, 0xDA, 0x80, 0x10)
	eq(t, "NOP before the read", cmd.last(), 0xE3)
	dat.queue(0xEE, 0x11, 0x22, 0x33)
	k := len(cmd.writes)
	ram, err := chip.ReadRAM(2, 5, 3)
	if err != nil {
		t.Fatal(err)
	}
	eq(t, "ram", ram, 0x11, 0x22, 0x33)
	eq(t, "address", cmd.writes[k], 0x62, 0x70, 0x05, 0x10)
}

func TestUC1611sRetryAndErrors(t *testing.T) {
	cmd, dat, chip := uc1611sFull(t)
	if !chip.OK() || chip.RetryCount() != 0 {
		t.Fatal("not clean at start")
	}
	e0 := len(cmd.writes)
	cmd.failWrites = 2
	if err := chip.SetContrast(0x66); err != nil {
		t.Fatalf("retried command failed: %v", err)
	}
	if len(cmd.writes)-e0 != 3 || chip.RetryCount() != 2 || !chip.OK() {
		t.Errorf("writes %d retries %d ok %v", len(cmd.writes)-e0, chip.RetryCount(), chip.OK())
	}
	cmd.failWrites = 100
	e0 = len(cmd.writes)
	if err := chip.SetInvert(true); !errors.Is(err, errNack) {
		t.Errorf("err = %v", err)
	}
	if len(cmd.writes)-e0 != UC1611sMaxAttempts || chip.OK() || chip.ErrorCount() != 1 || chip.RetryCount() != 2+UC1611sMaxAttempts {
		t.Errorf("attempts %d ok %v errors %d retries %d", len(cmd.writes)-e0, chip.OK(), chip.ErrorCount(), chip.RetryCount())
	}
	cmd.failWrites = 0
	chip.ClearErrors()
	if !chip.OK() || chip.RetryCount() != 0 {
		t.Error("ClearErrors")
	}

	// a failed data chunk repeats the whole page (address command + all chunks)
	e0, d0 := len(cmd.writes), len(dat.writes)
	dat.failWrites = 1
	if err := chip.ShowPage(1); err != nil {
		t.Fatal(err)
	}
	if len(cmd.writes)-e0 != 2 || len(dat.writes)-d0 != 1+8 || chip.RetryCount() != 1 {
		t.Errorf("page retry: %d cmd %d data %d retries", len(cmd.writes)-e0, len(dat.writes)-d0, chip.RetryCount())
	}
	e0, d0 = len(cmd.writes), len(dat.writes)
	dat.failWrites, dat.failSkip = 1, 2 // third chunk fails
	_ = chip.ShowPage(2)
	if len(cmd.writes)-e0 != 2 || len(dat.writes)-d0 != 3+8 {
		t.Errorf("partial page retry: %d cmd %d data", len(cmd.writes)-e0, len(dat.writes)-d0)
	}
}

func TestUC1611sDeadChipFailsAtInit(t *testing.T) {
	cmd, dat := &uc1611sMock{failWrites: 1000}, &uc1611sMock{}
	if _, err := NewUC1611sMinimal(cmd, dat, 0, 0); !errors.Is(err, errNack) {
		t.Errorf("err = %v", err)
	}
	if len(cmd.writes) != UC1611sMaxAttempts || len(dat.writes) != 0 {
		t.Errorf("dead chip: %d command writes, %d data writes", len(cmd.writes), len(dat.writes))
	}
}

func TestUC1611sReadRetries(t *testing.T) {
	cmd, dat, chip := uc1611sFull(t)
	cmd.failReads = 2
	cmd.queue(0xDA, 0x80, 0x10)
	s, err := chip.ReadStatus()
	if err != nil {
		t.Fatal(err)
	}
	eq(t, "status after retries", s, 0xDA, 0x80, 0x10)
	if chip.RetryCount() < 2 {
		t.Errorf("retries = %d", chip.RetryCount())
	}
	cmd.failReads = 100
	if _, err := chip.ReadStatus(); !errors.Is(err, errNack) || chip.OK() {
		t.Errorf("exhausted read: err %v ok %v", err, chip.OK())
	}
	chip.ClearErrors()
	cmd.failReads = 0
	dat.failReads = 1
	dat.queue(0xEE, 0x0A, 0x0B)
	k := len(cmd.writes)
	ram, err := chip.ReadRAM(1, 4, 2)
	if err != nil {
		t.Fatal(err)
	}
	eq(t, "ram after retry", ram, 0x0A, 0x0B)
	if len(cmd.writes)-k != 2 {
		t.Errorf("address commands = %d, want 2", len(cmd.writes)-k)
	}
}

// ---- SPI (4-wire) via the display connection ----------------------------------------------

func TestUC1611sSpiInit(t *testing.T) {
	dc := connection.NewDisplayConnectionMock(connection.WireModeSimplex)
	chip, err := NewUC1611sSpiMinimal(dc, 0, 0)
	if err != nil {
		t.Fatal(err)
	}
	if chip.Width() != 240 || chip.Height() != 128 {
		t.Errorf("geometry %dx%d", chip.Width(), chip.Height())
	}
	resets := 0
	for _, e := range dc.Events {
		if e.Kind == "reset" {
			resets++
		}
	}
	if resets != 1 || dc.Events[0].Kind != "reset" {
		t.Errorf("hardware reset first: resets=%d", resets)
	}
	var cmds []byte
	var datas [][]byte
	for _, e := range dc.Events {
		switch e.Kind {
		case "command":
			if len(e.Data) != 0 {
				t.Errorf("command 0x%02X carries parameter bytes", e.Cmd)
			}
			cmds = append(cmds, e.Cmd)
		case "data":
			datas = append(datas, e.Data)
		}
	}
	if !bytes.Equal(cmds[:13], []byte{0xE2, 0x25, 0xC0, 0x02, 0xA3, 0xF1, 0x7F, 0xF2, 0x00, 0xF3, 0x7F, 0x81, 0x8F}) {
		t.Errorf("init stream % X", cmds[:13])
	}
	if len(datas) != 20 {
		t.Errorf("clear transfers = %d, want 20", len(datas))
	}
	for _, d := range datas {
		if len(d) != 240 {
			t.Errorf("clear transfer of %d bytes", len(d))
		}
	}
	if !chip.OK() || chip.RetryCount() != 0 {
		t.Errorf("ok %v retries %d", chip.OK(), chip.RetryCount())
	}
}

func TestUC1611sSpiShowAndUnsupportedReads(t *testing.T) {
	dc := connection.NewDisplayConnectionMock(connection.WireModeSimplex)
	chip, err := NewUC1611sSpiFull(dc, 0, 0, false, 0x8F)
	if err != nil {
		t.Fatal(err)
	}
	n := len(dc.Events)
	chip.SetPixel(0, 0, true)
	if err := chip.Show(); err != nil {
		t.Fatal(err)
	}
	var shown [][]byte
	for _, e := range dc.Events[n:] {
		if e.Kind == "data" {
			shown = append(shown, e.Data)
		}
	}
	if len(shown) != 16 || len(shown[0]) != 240 || shown[0][0] != 1 {
		t.Errorf("show: %d pages", len(shown))
	}
	if _, err := chip.ReadStatus(); !errors.Is(err, ErrUC1611sUnsupported) {
		t.Errorf("ReadStatus err %v", err)
	}
	if _, err := chip.ReadRAM(0, 0, 4); !errors.Is(err, ErrUC1611sUnsupported) {
		t.Errorf("ReadRAM err %v", err)
	}
	if !chip.OK() || chip.ErrorCount() != 0 {
		t.Errorf("unsupported read counted as error")
	}
	n = len(dc.Events)
	if err := chip.SetContrast(0x50); err != nil {
		t.Fatal(err)
	}
	var c []byte
	for _, e := range dc.Events[n:] {
		c = append(c, e.Cmd)
	}
	if !bytes.Equal(c, []byte{0x81, 0x50}) {
		t.Errorf("contrast commands % X", c)
	}
	n = len(dc.Events)
	if err := chip.Wake(); err != nil {
		t.Fatal(err)
	}
	if len(dc.Events)-n != 1 || dc.Events[n].Cmd != 0xA9 {
		t.Errorf("wake events %v", dc.Events[n:])
	}
}
