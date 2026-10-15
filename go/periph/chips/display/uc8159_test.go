package display

import (
	"bytes"
	"errors"
	"testing"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

func ucHas8159(m *connection.DisplayConnectionMock, cmd uint8, data []byte) bool {
	for _, c := range m.Commands() {
		if c.Cmd == cmd && bytes.Equal(c.Data, data) {
			return true
		}
	}
	return false
}

func newUC8159Full(t *testing.T, mode connection.WireMode) (*UC8159Full, *connection.DisplayConnectionMock) {
	t.Helper()
	m := connection.NewDisplayConnectionMock(mode)
	d, err := NewUC8159Full(m, 60*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	return d, m
}

func TestUC8159Init(t *testing.T) {
	m := connection.NewDisplayConnectionMock(connection.WireModeSimplex)
	if _, err := NewUC8159Minimal(m, 60*time.Second); err != nil {
		t.Fatal(err)
	}
	if m.Events[0].Kind != "reset" || m.Events[1].Kind != "waitBusy" || m.Events[1].BusyActiveHigh ||
		m.Events[1].Timeout != 60*time.Second {
		t.Fatalf("events = %+v", m.Events[:2])
	}
	want := []struct {
		cmd  uint8
		data []byte
	}{
		{0x00, []byte{0xEF, 0x08}}, {0x01, []byte{0x37, 0x00, 0x23, 0x23}}, {0x03, []byte{0x00}},
		{0x06, []byte{0xC7, 0xC7, 0x1D}}, {0x30, []byte{0x3C}}, {0x41, []byte{0x00}}, {0x50, []byte{0x37}},
		{0x60, []byte{0x22}}, {0x61, []byte{0x02, 0x58, 0x01, 0xC0}}, {0xE3, []byte{0xAA}},
	}
	c := m.Commands()
	if len(c) != len(want) {
		t.Fatalf("commands = %+v", c)
	}
	for i, w := range want {
		if c[i].Cmd != w.cmd || !bytes.Equal(c[i].Data, w.data) {
			t.Errorf("command %d = %#x % x", i, c[i].Cmd, c[i].Data)
		}
	}
	m.Ready = false
	if _, err := NewUC8159Minimal(m, time.Second); !errors.Is(err, ErrUC8159BusyTimeout) {
		t.Fatalf("err = %v", err)
	}
}

func TestUC8159Framebuffer(t *testing.T) {
	d, _ := newUC8159Full(t, connection.WireModeSimplex)
	if d.GetPixel(0, 0) != UC8159White || d.GetPixel(599, 447) != UC8159White {
		t.Fatal("not white after init")
	}
	for c := uint8(0); c < 8; c++ {
		d.SetPixel(7+int(c), 3, c)
	}
	for c := uint8(0); c < 8; c++ {
		if d.GetPixel(7+int(c), 3) != c {
			t.Fatalf("colour %d", c)
		}
	}
	if d.GetPixel(6, 3) != UC8159White || d.GetPixel(15, 3) != UC8159White {
		t.Fatal("neighbours")
	}
	d.SetPixel(599, 447, UC8159Orange)
	if d.GetPixel(599, 447) != UC8159Orange || d.GetPixel(598, 447) != UC8159White {
		t.Fatal("last pixel")
	}
	d.SetPixel(-1, 0, UC8159Red)
	d.SetPixel(600, 0, UC8159Red)
	d.SetPixel(0, 448, UC8159Red)
	if d.GetPixel(0, 0) != UC8159White || d.GetPixel(700, 9) != UC8159Black {
		t.Fatal("out of range")
	}
	d.Clear(UC8159Green)
	if d.GetPixel(0, 0) != UC8159Green || d.GetPixel(333, 222) != UC8159Green {
		t.Fatal("clear")
	}
}

func TestUC8159ShowSequence(t *testing.T) {
	m := connection.NewDisplayConnectionMock(connection.WireModeSimplex)
	d, _ := NewUC8159Minimal(m, time.Second)
	d.SetPixel(0, 0, UC8159Black)
	d.SetPixel(1, 0, UC8159Orange)
	d.SetPixel(599, 447, UC8159Red)
	n := len(m.Events)
	if err := d.Show(); err != nil {
		t.Fatal(err)
	}
	tail := m.Events[n:]
	if tail[0].Cmd != 0x10 {
		t.Fatal("DTM1 first")
	}
	var data []byte
	for _, e := range tail {
		if e.Kind == "data" {
			data = append(data, e.Data...)
		}
	}
	if len(data) != 134400 || data[0] != 0x06 || data[1] != 0x11 || data[134399] != 0x14 {
		t.Fatalf("frame len=%d first=%x last=%x", len(data), data[:2], data[len(data)-1])
	}
	last := tail[len(tail)-6:]
	wantCmd := []uint8{0x04, 0, 0x12, 0, 0x02, 0}
	for i, e := range last {
		if i%2 == 0 && (e.Kind != "command" || e.Cmd != wantCmd[i]) {
			t.Fatalf("tail[%d] = %+v", i, e)
		}
		if i%2 == 1 && (e.Kind != "waitBusy" || e.BusyActiveHigh) {
			t.Fatalf("tail[%d] = %+v", i, e)
		}
	}
	m.Ready = false
	if err := d.Show(); !errors.Is(err, ErrUC8159BusyTimeout) {
		t.Fatalf("err = %v", err)
	}
}

func TestUC8159SleepWake(t *testing.T) {
	m := connection.NewDisplayConnectionMock(connection.WireModeSimplex)
	d, _ := NewUC8159Minimal(m, 0)
	if err := d.Sleep(); err != nil {
		t.Fatal(err)
	}
	if !ucHas8159(m, 0x07, []byte{0xA5}) {
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

func TestUC8159Settings(t *testing.T) {
	d, m := newUC8159Full(t, connection.WireModeSimplex)
	base := len(m.Commands())
	_ = d.SetFlip(true, false)
	_ = d.SetFlip(false, true)
	_ = d.SetFlip(false, false)
	_ = d.SetFrameRate(0x3A)
	_ = d.SetInterval(7, UC8159White, 1)
	_ = d.SetInterval(3, UC8159Black, 0)
	_ = d.SetTemperatureSensor(true, -3)
	_ = d.SetVcomDc(-0.10)
	_ = d.SetVcomDc(-2.0)
	_ = d.SetVcomDc(-9.0)
	want := []struct {
		cmd  uint8
		data []byte
	}{
		{0x00, []byte{0xEB, 0x08}}, {0x00, []byte{0xE7, 0x08}}, {0x00, []byte{0xEF, 0x08}},
		{0x30, []byte{0x3A}}, {0x50, []byte{0x37}}, {0x50, []byte{0x03}}, {0x41, []byte{0x8D}},
		{0x82, []byte{0x02}}, {0x82, []byte{0x28}}, {0x82, []byte{0x50}},
	}
	c := m.Commands()[base:]
	for i, w := range want {
		if c[i].Cmd != w.cmd || !bytes.Equal(c[i].Data, w.data) {
			t.Errorf("command %d = %#x % x, want %#x % x", i, c[i].Cmd, c[i].Data, w.cmd, w.data)
		}
	}
}

func TestUC8159Drawing(t *testing.T) {
	d, _ := newUC8159Full(t, connection.WireModeSimplex)
	d.DrawLine(0, 0, 9, 9, UC8159Red)
	if d.GetPixel(0, 0) != UC8159Red || d.GetPixel(9, 9) != UC8159Red || d.GetPixel(5, 4) != UC8159White {
		t.Fatal("line")
	}
	d.Clear(UC8159White)
	d.DrawRect(1, 1, 5, 4, UC8159Blue, false)
	if d.GetPixel(1, 1) != UC8159Blue || d.GetPixel(5, 4) != UC8159Blue || d.GetPixel(3, 2) != UC8159White {
		t.Fatal("outline")
	}
	d.DrawRect(10, 1, 3, 3, UC8159Yellow, true)
	if d.GetPixel(11, 2) != UC8159Yellow || d.GetPixel(13, 3) != UC8159White {
		t.Fatal("filled")
	}
	d.Clear(UC8159White)
	d.Blit(4, 2, []byte{0xA0, 0x40}, 4, 2, UC8159Green)
	if d.GetPixel(4, 2) != UC8159Green || d.GetPixel(5, 2) != UC8159White || d.GetPixel(6, 2) != UC8159Green ||
		d.GetPixel(5, 3) != UC8159Green {
		t.Fatal("blit")
	}
}

func TestUC8159Reads(t *testing.T) {
	d, m := newUC8159Full(t, connection.WireModeHalfDuplexPin)
	m.QueueRead(0x0F)
	if v, _ := d.ReadStatus(); v != 0x0F {
		t.Fatalf("status = %d", v)
	}
	m.QueueRead(0xE7, 0x00)
	if v, _ := d.ReadTemperature(); v != -25 {
		t.Fatalf("temperature = %d", v)
	}
	m.QueueRead(0x01)
	if v, _ := d.ReadLowPower(); v {
		t.Fatal("low power")
	}
	m.QueueRead(0x0C)
	if v, _ := d.ReadVcom(); v > -0.59 || v < -0.61 {
		t.Fatalf("vcom = %v", v)
	}
	ds, _ := newUC8159Full(t, connection.WireModeSimplex)
	if _, err := ds.ReadStatus(); !errors.Is(err, connection.ErrReadUnavailable) {
		t.Fatalf("simplex read err = %v", err)
	}
}
