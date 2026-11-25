package display

import (
	"bytes"
	"errors"
	"testing"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

func ucCommands(m *connection.DisplayConnectionMock) []connection.DisplayEvent {
	return m.Commands()
}

func ucHas(m *connection.DisplayConnectionMock, cmd uint8, data []byte) bool {
	for _, c := range m.Commands() {
		if c.Cmd == cmd && bytes.Equal(c.Data, data) {
			return true
		}
	}
	return false
}

func newUCFull(t *testing.T, mode connection.WireMode, w, h int) (*UC8276CFull, *connection.DisplayConnectionMock) {
	t.Helper()
	m := connection.NewDisplayConnectionMock(mode)
	d, err := NewUC8276CFull(m, w, h, 30*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	return d, m
}

func TestUC8276CInit(t *testing.T) {
	m := connection.NewDisplayConnectionMock(connection.WireModeSimplex)
	if _, err := NewUC8276CMinimal(m, 400, 300, 30*time.Second); err != nil {
		t.Fatal(err)
	}
	if m.Events[0].Kind != "reset" || m.Events[1].Kind != "waitBusy" || m.Events[1].BusyActiveHigh ||
		m.Events[1].Timeout != 30*time.Second {
		t.Fatalf("events = %+v", m.Events[:2])
	}
	c := m.Commands()
	if len(c) != 2 || c[0].Cmd != 0x00 || !bytes.Equal(c[0].Data, []byte{0x0F}) ||
		c[1].Cmd != 0x50 || !bytes.Equal(c[1].Data, []byte{0xD7}) {
		t.Fatalf("commands = %+v", c)
	}

	m2 := connection.NewDisplayConnectionMock(connection.WireModeSimplex)
	if _, err := NewUC8276CMinimal(m2, 200, 100, 0); err != nil {
		t.Fatal(err)
	}
	if !ucHas(m2, 0x61, []byte{0x00, 0xC8, 0x00, 0x64}) {
		t.Fatal("TRES missing for a smaller panel")
	}
}

func TestUC8276CInvalidArgsAndTimeout(t *testing.T) {
	m := connection.NewDisplayConnectionMock(connection.WireModeSimplex)
	for _, wh := range [][2]int{{401, 300}, {408, 300}, {400, 301}, {4, 10}} {
		if _, err := NewUC8276CMinimal(m, wh[0], wh[1], 0); err == nil {
			t.Errorf("size %v accepted", wh)
		}
	}
	m.Ready = false
	if _, err := NewUC8276CMinimal(m, 16, 2, time.Second); !errors.Is(err, ErrBusyTimeout) {
		t.Fatalf("err = %v", err)
	}
}

func TestUC8276CFramebuffer(t *testing.T) {
	d, _ := newUCFull(t, connection.WireModeSimplex, 16, 2)
	if d.GetPixel(0, 0) != UC8276CWhite {
		t.Fatal("not white after init")
	}
	d.SetPixel(0, 0, UC8276CBlack)
	d.SetPixel(9, 1, UC8276CRed)
	if d.GetPixel(0, 0) != UC8276CBlack || d.GetPixel(9, 1) != UC8276CRed {
		t.Fatal("pixel colours")
	}
	if d.bw[0] != 0x7F || d.red[3] != 0x40 || d.bw[3] != 0xBF {
		t.Fatalf("plane bytes bw0=%#x bw3=%#x red3=%#x", d.bw[0], d.bw[3], d.red[3])
	}
	d.SetPixel(-1, 0, UC8276CBlack)
	d.SetPixel(16, 0, UC8276CBlack)
	d.SetPixel(0, 2, UC8276CBlack)
	d.SetPixel(0, 0, UC8276CWhite)
	if d.GetPixel(0, 0) != UC8276CWhite {
		t.Fatal("white overwrite")
	}
}

func TestUC8276CShowSequence(t *testing.T) {
	m := connection.NewDisplayConnectionMock(connection.WireModeSimplex)
	d, _ := NewUC8276CMinimal(m, 16, 2, time.Second)
	d.SetPixel(0, 0, UC8276CRed)
	n := len(m.Events)
	if err := d.Show(); err != nil {
		t.Fatal(err)
	}
	tail := m.Events[n:]
	kinds := ""
	for _, e := range tail {
		kinds += e.Kind[:1]
	}
	if kinds != "cdcdcwcwcw" {
		t.Fatalf("event kinds = %q", kinds)
	}
	if tail[0].Cmd != 0x10 || !bytes.Equal(tail[1].Data, []byte{0x7F, 0xFF, 0xFF, 0xFF}) ||
		tail[2].Cmd != 0x13 || !bytes.Equal(tail[3].Data, []byte{0x80, 0, 0, 0}) {
		t.Fatalf("planes = %+v", tail[:4])
	}
	if tail[4].Cmd != 0x04 || tail[6].Cmd != 0x12 || tail[8].Cmd != 0x02 {
		t.Fatal("power cycle order")
	}

	m2 := connection.NewDisplayConnectionMock(connection.WireModeSimplex)
	d2, _ := NewUC8276CMinimal(m2, 16, 2, 0)
	n = len(m2.Events)
	_ = d2.Show()
	if !bytes.Equal(m2.Events[n+3].Data, make([]byte, 4)) {
		t.Fatal("no red plane must send zeros")
	}

	m2.Ready = false
	if err := d2.Show(); !errors.Is(err, ErrBusyTimeout) {
		t.Fatalf("err = %v", err)
	}
}

func TestUC8276CSleepWake(t *testing.T) {
	m := connection.NewDisplayConnectionMock(connection.WireModeSimplex)
	d, _ := NewUC8276CMinimal(m, 16, 2, 0)
	if err := d.Sleep(); err != nil {
		t.Fatal(err)
	}
	if !ucHas(m, 0x07, []byte{0xA5}) {
		t.Fatal("DSLP missing")
	}
	_ = d.Show()
	resets := 0
	for _, e := range m.Events {
		if e.Kind == "reset" {
			resets++
		}
	}
	if resets != 2 {
		t.Fatalf("resets = %d", resets)
	}
}

func TestUC8276CSettings(t *testing.T) {
	d, m := newUCFull(t, connection.WireModeSimplex, 16, 2)
	base := len(m.Commands())
	_ = d.SetMode(UC8276CModeKW)
	_ = d.SetFlip(true, false)
	_ = d.SetPower(10, 10, 10, 16)
	_ = d.SetPower(2.4, 11, 5, 13)
	_ = d.SetVcomDc(-1.0)
	_ = d.SetFrameRate(50)
	_ = d.SetFrameRate(200)
	_ = d.SetFrameRate(30)
	_ = d.SetSoftStart(0x17, 0x17, 0x17)
	_ = d.SetInterval(7, 3, 1)
	_ = d.SetTemperatureSensor(true, -3)
	want := [][]byte{
		{0x1F}, {0x1B}, {0x03, 0x00, 0x26, 0x26, 0x26}, {0x03, 0x03, 0x00, 0x2B, 0x0D}, {0x12},
		{0x3C}, {0x39}, {0x09}, {0x17, 0x17, 0x17}, {0xD7}, {0x8D},
	}
	wantCmd := []uint8{0x00, 0x00, 0x01, 0x01, 0x82, 0x30, 0x30, 0x30, 0x06, 0x50, 0x41}
	c := m.Commands()[base:]
	for i := range want {
		if c[i].Cmd != wantCmd[i] || !bytes.Equal(c[i].Data, want[i]) {
			t.Errorf("command %d = %#x % x, want %#x % x", i, c[i].Cmd, c[i].Data, wantCmd[i], want[i])
		}
	}
	tab := make([]byte, 56)
	_ = d.WriteLUT(tab, nil, tab, nil, nil)
	c = m.Commands()
	last := c[len(c)-3:]
	if last[0].Cmd != 0x20 || last[1].Cmd != 0x22 || last[2].Cmd != 0x00 || !bytes.Equal(last[2].Data, []byte{0x3B}) {
		t.Fatalf("LUT sequence = %+v", last)
	}
	_ = d.SendCommand(0xAB, []byte{1})
	if !ucHas(m, 0xAB, []byte{1}) {
		t.Fatal("raw command")
	}
}

func TestUC8276CDrawing(t *testing.T) {
	d, _ := newUCFull(t, connection.WireModeSimplex, 16, 2)
	d.DrawLine(0, 0, 1, 1, UC8276CBlack)
	if d.GetPixel(0, 0) != UC8276CBlack || d.GetPixel(1, 1) != UC8276CBlack {
		t.Fatal("line")
	}
	d.DrawRect(4, 0, 4, 2, UC8276CRed, true)
	if d.GetPixel(4, 0) != UC8276CRed || d.GetPixel(7, 1) != UC8276CRed {
		t.Fatal("filled rect")
	}
	d.Clear(UC8276CWhite)
	d.DrawRect(0, 0, 4, 2, UC8276CBlack, false)
	if d.GetPixel(3, 1) != UC8276CBlack || d.GetPixel(4, 0) != UC8276CWhite {
		t.Fatal("outline rect")
	}
	d.Clear(UC8276CWhite)
	d.Blit(8, 0, []byte{0xA0}, 4, 1, UC8276CBlack)
	if d.GetPixel(8, 0) != UC8276CBlack || d.GetPixel(9, 0) != UC8276CWhite ||
		d.GetPixel(10, 0) != UC8276CBlack || d.GetPixel(11, 0) != UC8276CWhite {
		t.Fatal("blit")
	}
	d.Clear(UC8276CRed)
	if d.GetPixel(5, 1) != UC8276CRed {
		t.Fatal("clear red")
	}
}

func TestUC8276CShowRegion(t *testing.T) {
	d, m := newUCFull(t, connection.WireModeSimplex, 32, 4)
	n := len(m.Events)
	if err := d.ShowRegion(9, 1, 6, 2); err != nil {
		t.Fatal(err)
	}
	tail := m.Events[n:]
	if tail[0].Cmd != 0x91 || tail[1].Cmd != 0x90 ||
		!bytes.Equal(tail[1].Data, []byte{0, 8, 0, 15, 0, 1, 0, 2, 0}) {
		t.Fatalf("PTIN/PTL = %+v", tail[:2])
	}
	if tail[2].Cmd != 0x10 || tail[3].Kind != "data" || tail[4].Kind != "data" {
		t.Fatal("window rows")
	}
	if last := tail[len(tail)-1]; last.Cmd != 0x92 {
		t.Fatal("PTOUT missing")
	}
	if err := d.ShowRegion(0, 0, 0, 1); !errors.Is(err, ErrInvalidWindow) {
		t.Fatal("empty window")
	}
	if err := d.ShowRegion(40, 0, 4, 4); !errors.Is(err, ErrInvalidWindow) {
		t.Fatal("off-panel window")
	}
}

func TestUC8276CAutoAndWaitBusy(t *testing.T) {
	d, m := newUCFull(t, connection.WireModeSimplex, 16, 2)
	_ = d.RefreshAuto(false)
	c := m.Commands()
	if c[len(c)-1].Cmd != 0x17 || !bytes.Equal(c[len(c)-1].Data, []byte{0xA5}) {
		t.Fatal("AUTO 0xA5")
	}
	_ = d.RefreshAuto(true)
	c = m.Commands()
	if !bytes.Equal(c[len(c)-1].Data, []byte{0xA7}) {
		t.Fatal("AUTO 0xA7")
	}
	ok, _ := d.WaitBusy(10 * time.Millisecond)
	e := m.Events[len(m.Events)-1]
	if !ok || e.Kind != "waitBusy" || e.BusyActiveHigh || e.Timeout != 10*time.Millisecond {
		t.Fatalf("WaitBusy event = %+v", e)
	}
}

func TestUC8276CReads(t *testing.T) {
	d, m := newUCFull(t, connection.WireModeHalfDuplexPin, 16, 2)
	m.QueueRead(0x05)
	if v, _ := d.ReadStatus(); v != 5 {
		t.Fatalf("status = %d", v)
	}
	m.QueueRead(0xE7, 0x00)
	if v, _ := d.ReadTemperature(); v != -25 {
		t.Fatalf("temperature = %d", v)
	}
	m.QueueRead(0x19)
	if _, has, lut, _ := d.ReadRevision(false); has || lut != 0x19 {
		t.Fatalf("rev short = %v %#x", has, lut)
	}
	m.QueueRead(0x00, 0x02, 0x01, 0x02, 0x03)
	if chip, has, lut, _ := d.ReadRevision(true); !has || chip != 2 || lut != 0x030201 {
		t.Fatalf("rev long = %d %v %#x", chip, has, lut)
	}
	m.QueueRead(0x0A)
	if v, _ := d.ReadVcom(); v > -0.59 || v < -0.61 {
		t.Fatalf("vcom = %v", v)
	}
	m.QueueRead(0x00)
	if v, _ := d.ReadLowPower(); !v {
		t.Fatal("low power")
	}
	m.QueueRead(0xFF, 0x11, 0x22)
	if v, _ := d.ReadOTP(2); !bytes.Equal(v, []byte{0x11, 0x22}) {
		t.Fatalf("otp = % x", v)
	}

	ds, _ := newUCFull(t, connection.WireModeSimplex, 16, 2)
	if _, err := ds.ReadStatus(); !errors.Is(err, connection.ErrReadUnavailable) {
		t.Fatalf("simplex read err = %v", err)
	}
}
