package display

import (
	"bytes"
	"errors"
	"testing"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

func ilHas(m *connection.DisplayConnectionMock, cmd uint8, data []byte) bool {
	for _, c := range m.Commands() {
		if c.Cmd == cmd && bytes.Equal(c.Data, data) {
			return true
		}
	}
	return false
}

func newILFull(t *testing.T, mode connection.WireMode, w, h int) (*IL0373Full, *connection.DisplayConnectionMock) {
	t.Helper()
	m := connection.NewDisplayConnectionMock(mode)
	d, err := NewIL0373Full(m, w, h, 30*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	return d, m
}

func TestIL0373Init(t *testing.T) {
	m := connection.NewDisplayConnectionMock(connection.WireModeSimplex)
	d, err := NewIL0373Minimal(m, IL0373DefaultWidth, IL0373DefaultHeight, 30*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	if m.Events[0].Kind != "reset" || m.Events[1].Kind != "waitBusy" || m.Events[1].BusyActiveHigh ||
		m.Events[1].Timeout != 30*time.Second {
		t.Fatalf("events = %+v", m.Events[:2])
	}
	c := m.Commands()
	wantCmd := []uint8{0x01, 0x06, 0x04, 0x00, 0x61, 0x50}
	wantData := [][]byte{{0x03, 0x00, 0x2B, 0x2B, 0x03}, {0x17, 0x17, 0x17}, nil, {0x1F}, {0x68, 0x00, 0xD4}, {0x97}}
	if len(c) != len(wantCmd) {
		t.Fatalf("commands = %+v", c)
	}
	for i := range wantCmd {
		if c[i].Cmd != wantCmd[i] || !bytes.Equal(c[i].Data, wantData[i]) {
			t.Errorf("command %d = %#x % x", i, c[i].Cmd, c[i].Data)
		}
	}
	if m.Events[5].Kind != "waitBusy" || m.Events[5].Timeout != 200*time.Millisecond {
		t.Fatal("PON must settle for 200 ms")
	}
	if d.Width() != 104 || d.Height() != 212 {
		t.Fatal("geometry")
	}
	m2 := connection.NewDisplayConnectionMock(connection.WireModeSimplex)
	if _, err := NewIL0373Minimal(m2, 160, 296, 0); err != nil {
		t.Fatal(err)
	}
	if !ilHas(m2, 0x61, []byte{0xA0, 0x01, 0x28}) {
		t.Fatal("TRES for the largest panel")
	}
}

func TestIL0373InvalidArgsAndTimeout(t *testing.T) {
	m := connection.NewDisplayConnectionMock(connection.WireModeSimplex)
	for _, wh := range [][2]int{{161, 212}, {168, 212}, {104, 297}, {4, 10}, {104, 0}} {
		if _, err := NewIL0373Minimal(m, wh[0], wh[1], 0); err == nil {
			t.Errorf("size %v accepted", wh)
		}
	}
	m.Ready = false
	if _, err := NewIL0373Minimal(m, 16, 2, time.Second); !errors.Is(err, ErrIL0373BusyTimeout) {
		t.Fatalf("err = %v", err)
	}
}

func TestIL0373Framebuffer(t *testing.T) {
	d, _ := newILFull(t, connection.WireModeSimplex, 16, 2)
	if d.GetPixel(0, 0) != IL0373White {
		t.Fatal("not white after init")
	}
	d.SetPixel(0, 0, IL0373Black)
	d.SetPixel(9, 1, IL0373Black)
	if d.GetPixel(0, 0) != IL0373Black || d.GetPixel(9, 1) != IL0373Black {
		t.Fatal("pixel colours")
	}
	if d.fb[0] != 0x7F || d.fb[3] != 0xBF {
		t.Fatalf("bytes fb0=%#x fb3=%#x", d.fb[0], d.fb[3])
	}
	d.SetPixel(-1, 0, IL0373Black)
	d.SetPixel(16, 0, IL0373Black)
	d.SetPixel(0, 2, IL0373Black)
	d.SetPixel(0, 0, IL0373White)
	if d.GetPixel(0, 0) != IL0373White {
		t.Fatal("white overwrite")
	}
}

func TestIL0373ShowSequence(t *testing.T) {
	m := connection.NewDisplayConnectionMock(connection.WireModeSimplex)
	d, _ := NewIL0373Minimal(m, 16, 2, time.Second)
	d.SetPixel(0, 0, IL0373Black)
	n := len(m.Events)
	if err := d.Show(); err != nil {
		t.Fatal(err)
	}
	tail := m.Events[n:]
	kinds := ""
	for _, e := range tail {
		kinds += e.Kind[:1]
	}
	if kinds != "cdcdcwccw" {
		t.Fatalf("event kinds = %q", kinds)
	}
	if tail[0].Cmd != 0x10 || !bytes.Equal(tail[1].Data, []byte{0x80, 0x00, 0x00, 0x00}) ||
		tail[2].Cmd != 0x13 || !bytes.Equal(tail[3].Data, []byte{0x7F, 0xFF, 0xFF, 0xFF}) {
		t.Fatalf("planes = %+v", tail[:4])
	}
	if tail[4].Cmd != 0x12 || tail[6].Cmd != 0x50 || !bytes.Equal(tail[6].Data, []byte{0xF7}) || tail[7].Cmd != 0x02 {
		t.Fatal("refresh order")
	}
	// second show powers on first
	n = len(m.Events)
	_ = d.Show()
	tail = m.Events[n:]
	if tail[0].Cmd != 0x04 || tail[1].Kind != "waitBusy" || tail[2].Cmd != 0x50 ||
		!bytes.Equal(tail[2].Data, []byte{0x97}) || tail[3].Cmd != 0x10 {
		t.Fatalf("second show = %+v", tail[:4])
	}
	m.Ready = false
	if err := d.Show(); !errors.Is(err, ErrIL0373BusyTimeout) {
		t.Fatalf("err = %v", err)
	}
}

func TestIL0373SleepWake(t *testing.T) {
	m := connection.NewDisplayConnectionMock(connection.WireModeSimplex)
	d, _ := NewIL0373Minimal(m, 16, 2, 0)
	if err := d.Sleep(); err != nil {
		t.Fatal(err)
	}
	c := m.Commands()
	l := len(c)
	if c[l-3].Cmd != 0x50 || !bytes.Equal(c[l-3].Data, []byte{0xF7}) || c[l-2].Cmd != 0x02 ||
		c[l-1].Cmd != 0x07 || !bytes.Equal(c[l-1].Data, []byte{0xA5}) {
		t.Fatalf("sleep sequence = %+v", c[l-3:])
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

func TestIL0373Settings(t *testing.T) {
	d, m := newILFull(t, connection.WireModeSimplex, 16, 2)
	base := len(m.Commands())
	_ = d.SetFlip(true, false)
	_ = d.SetPower(14, 14, 20)
	_ = d.SetPower(6.4, 15, 17)
	_ = d.SetVcomDc(-1.0)
	_ = d.SetVcomDc(-9.0)
	_ = d.SetFrameRate(50)
	_ = d.SetFrameRate(200)
	_ = d.SetFrameRate(30)
	_ = d.SetSoftStart(0x17, 0x17, 0x17)
	_ = d.SetInterval(7, 2, 1)
	_ = d.SetTemperatureSensor(true, -3)
	want := [][]byte{
		{0x1B}, {0x03, 0x00, 0x26, 0x26, 0x03}, {0x03, 0x03, 0x00, 0x2B, 0x03}, {0x12}, {0x3A},
		{0x3C}, {0x39}, {0x09}, {0x17, 0x17, 0x17}, {0x97}, {0x8D},
	}
	wantCmd := []uint8{0x00, 0x01, 0x01, 0x82, 0x82, 0x30, 0x30, 0x30, 0x06, 0x50, 0x41}
	c := m.Commands()[base:]
	for i := range want {
		if c[i].Cmd != wantCmd[i] || !bytes.Equal(c[i].Data, want[i]) {
			t.Errorf("command %d = %#x % x, want %#x % x", i, c[i].Cmd, c[i].Data, wantCmd[i], want[i])
		}
	}
	if err := d.WriteLUT(5, nil); err == nil {
		t.Fatal("LUT table 5 accepted")
	}
	_ = d.WriteLUT(0, make([]byte, 44))
	_ = d.WriteLUT(4, make([]byte, 42))
	c = m.Commands()
	last := c[len(c)-4:]
	if last[0].Cmd != 0x20 || len(last[0].Data) != 44 || last[1].Cmd != 0x00 || !bytes.Equal(last[1].Data, []byte{0x3B}) ||
		last[2].Cmd != 0x24 {
		t.Fatalf("LUT sequence = %+v", last)
	}
	_ = d.SendCommand(0xAB, []byte{1})
	if !ilHas(m, 0xAB, []byte{1}) {
		t.Fatal("raw command")
	}
}

func TestIL0373Drawing(t *testing.T) {
	d, _ := newILFull(t, connection.WireModeSimplex, 16, 2)
	d.DrawLine(0, 0, 1, 1, IL0373Black)
	if d.GetPixel(0, 0) != IL0373Black || d.GetPixel(1, 1) != IL0373Black {
		t.Fatal("line")
	}
	d.DrawRect(4, 0, 4, 2, IL0373Black, true)
	if d.GetPixel(4, 0) != IL0373Black || d.GetPixel(7, 1) != IL0373Black {
		t.Fatal("filled rect")
	}
	d.Clear(IL0373White)
	d.DrawRect(0, 0, 4, 2, IL0373Black, false)
	if d.GetPixel(3, 1) != IL0373Black || d.GetPixel(4, 0) != IL0373White {
		t.Fatal("outline rect")
	}
	d.Clear(IL0373White)
	d.Blit(8, 0, []byte{0xA0}, 4, 1, IL0373Black)
	if d.GetPixel(8, 0) != IL0373Black || d.GetPixel(9, 0) != IL0373White ||
		d.GetPixel(10, 0) != IL0373Black || d.GetPixel(11, 0) != IL0373White {
		t.Fatal("blit")
	}
	d.Clear(IL0373Black)
	if d.GetPixel(5, 1) != IL0373Black {
		t.Fatal("clear black")
	}
}

func TestIL0373ShowRegion(t *testing.T) {
	d, m := newILFull(t, connection.WireModeSimplex, 32, 4)
	n := len(m.Events)
	if err := d.ShowRegion(9, 1, 6, 2); err != nil {
		t.Fatal(err)
	}
	tail := m.Events[n:]
	if tail[0].Cmd != 0x91 || tail[1].Cmd != 0x90 ||
		!bytes.Equal(tail[1].Data, []byte{8, 15, 0, 1, 0, 2, 0}) {
		t.Fatalf("PTIN/PTL = %+v", tail[:2])
	}
	if tail[2].Cmd != 0x10 || !bytes.Equal(tail[3].Data, []byte{0x00}) || !bytes.Equal(tail[4].Data, []byte{0x00}) {
		t.Fatal("OLD window rows must be inverted")
	}
	if tail[5].Cmd != 0x13 || !bytes.Equal(tail[6].Data, []byte{0xFF}) {
		t.Fatal("NEW window rows")
	}
	if last := tail[len(tail)-1]; last.Cmd != 0x92 {
		t.Fatal("PTOUT missing")
	}
	if err := d.ShowRegion(0, 0, 0, 1); !errors.Is(err, ErrIL0373InvalidWindow) {
		t.Fatal("empty window")
	}
	if err := d.ShowRegion(40, 0, 4, 4); !errors.Is(err, ErrIL0373InvalidWindow) {
		t.Fatal("off-panel window")
	}
	ok, _ := d.WaitBusy(10 * time.Millisecond)
	e := m.Events[len(m.Events)-1]
	if !ok || e.Kind != "waitBusy" || e.BusyActiveHigh || e.Timeout != 10*time.Millisecond {
		t.Fatalf("WaitBusy event = %+v", e)
	}
}

func TestIL0373Reads(t *testing.T) {
	d, m := newILFull(t, connection.WireModeHalfDuplexPin, 16, 2)
	m.QueueRead(0x05)
	if v, _ := d.ReadStatus(); v != 5 {
		t.Fatalf("status = %d", v)
	}
	m.QueueRead(0xE7, 0x00)
	if v, _ := d.ReadTemperature(); v != -25 {
		t.Fatalf("temperature = %d", v)
	}
	m.QueueRead(0x19)
	if v, _ := d.ReadRevision(); v != 0x19 {
		t.Fatalf("rev = %#x", v)
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

	ds, _ := newILFull(t, connection.WireModeSimplex, 16, 2)
	if _, err := ds.ReadStatus(); !errors.Is(err, connection.ErrReadUnavailable) {
		t.Fatalf("simplex read err = %v", err)
	}
}
