package display

import (
	"bytes"
	"testing"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// ssdStream returns the command/parameter bytes recorded from index `from` (failing on parameters
// sent with D/C# high), with data events as -n and delays as -1000000-ms-ish markers dropped.
func ssdStream(t *testing.T, m *connection.DisplayConnectionMock, from int) []int {
	t.Helper()
	var out []int
	for _, e := range m.Events[from:] {
		switch e.Kind {
		case "command":
			if len(e.Data) != 0 {
				t.Fatalf("parameters must be separate commands (D/C# low), got %#x % x", e.Cmd, e.Data)
			}
			out = append(out, int(e.Cmd))
		case "data":
			out = append(out, -len(e.Data))
		}
	}
	return out
}

func ssdEq(a, b []int) bool {
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

func ssdDataBytes(m *connection.DisplayConnectionMock, from int) int {
	n := 0
	for _, e := range m.Events[from:] {
		if e.Kind == "data" {
			n += len(e.Data)
		}
	}
	return n
}

func newSSDFull(t *testing.T) (*SSD1331Full, *connection.DisplayConnectionMock) {
	t.Helper()
	m := connection.NewDisplayConnectionMock(connection.WireModeSimplex)
	d, err := NewSSD1331Full(m)
	if err != nil {
		t.Fatal(err)
	}
	return d, m
}

func TestSSD1331Init(t *testing.T) {
	m := connection.NewDisplayConnectionMock(connection.WireModeSimplex)
	d, err := NewSSD1331Minimal(m)
	if err != nil {
		t.Fatal(err)
	}
	if m.Events[0].Kind != "reset" || m.Events[1].Kind != "delay" || m.Events[1].Timeout != 100*time.Millisecond {
		t.Fatalf("events = %+v", m.Events[:2])
	}
	want := []int{
		0xAE, 0x81, 0xFF, 0x82, 0xFF, 0x83, 0xFF, 0x87, 0x06, 0x8A, 0x64, 0x8B, 0x78, 0x8C, 0x64,
		0xA0, 0x72, 0xA1, 0x00, 0xA2, 0x00, 0xA4, 0xA8, 0x3F, 0xAD, 0x8E, 0xB0, 0x0B, 0xB1, 0x31,
		0xB3, 0xF0, 0xBB, 0x3A, 0xBE, 0x3E, 0x2E, 0x25, 0x00, 0x00, 0x5F, 0x3F, 0xAF,
	}
	if got := ssdStream(t, m, 0); !ssdEq(got, want) {
		t.Fatalf("init stream = % x", got)
	}
	if m.Events[len(m.Events)-2].Kind != "delay" || ssdDataBytes(m, 0) != 0 {
		t.Error("expected the 100 ms VCC wait before display on and no pixel data")
	}
	if d.Width() != 96 || d.Height() != 64 || len(d.fb) != SSD1331BufferSize {
		t.Error("geometry")
	}
}

func TestSSD1331Framebuffer(t *testing.T) {
	d, _ := newSSDFull(t)
	d.SetPixel(0, 0, SSD1331Red)
	d.SetPixel(95, 63, SSD1331Blue)
	d.SetPixel(1, 2, 0x1234)
	if d.fb[0] != 0xF8 || d.fb[1] != 0x00 || d.fb[12286] != 0x00 || d.fb[12287] != 0x1F {
		t.Error("RGB565 high byte first / last pixel")
	}
	if i := (2*96 + 1) * 2; d.fb[i] != 0x12 || d.fb[i+1] != 0x34 || d.GetPixel(1, 2) != 0x1234 {
		t.Error("index and readback")
	}
	d.SetPixel(-1, 0, 1)
	d.SetPixel(0, -1, 1)
	d.SetPixel(96, 0, 1)
	d.SetPixel(0, 64, 1)
	if d.GetPixel(99, 99) != 0 || d.fb[0] != 0xF8 {
		t.Error("out of range must be ignored")
	}
	d.Clear(SSD1331Green)
	if d.GetPixel(0, 0) != SSD1331Green || d.GetPixel(95, 63) != SSD1331Green {
		t.Error("clear")
	}
	if SSD1331RGB(255, 0, 0) != 0xF800 || SSD1331RGB(0, 255, 0) != 0x07E0 ||
		SSD1331RGB(0, 0, 255) != 0x001F || SSD1331RGB(255, 255, 255) != 0xFFFF {
		t.Error("rgb packing")
	}
	d.Clear(0)
	d.DrawLine(0, 0, 4, 4, SSD1331White)
	if d.GetPixel(0, 0) != SSD1331White || d.GetPixel(2, 2) != SSD1331White || d.GetPixel(4, 4) != SSD1331White || d.GetPixel(4, 0) != 0 {
		t.Error("diagonal line")
	}
	d.Clear(0)
	d.DrawRect(2, 2, 4, 3, SSD1331White, false)
	if d.GetPixel(2, 2) != SSD1331White || d.GetPixel(5, 4) != SSD1331White || d.GetPixel(3, 3) != 0 || d.GetPixel(6, 2) != 0 {
		t.Error("rect outline")
	}
	d.DrawRect(10, 10, 3, 3, SSD1331Red, true)
	if d.GetPixel(11, 11) != SSD1331Red || d.GetPixel(13, 13) != 0 {
		t.Error("rect filled")
	}
	d.Blit(20, 20, []uint16{1, 2, 3, 4}, 2, 2)
	if d.GetPixel(20, 20) != 1 || d.GetPixel(21, 20) != 2 || d.GetPixel(20, 21) != 3 || d.GetPixel(21, 21) != 4 {
		t.Error("blit")
	}
}

func TestSSD1331Show(t *testing.T) {
	d, m := newSSDFull(t)
	d.Clear(SSD1331Red)
	n := len(m.Events)
	if err := d.Show(); err != nil {
		t.Fatal(err)
	}
	s := ssdStream(t, m, n)
	if !ssdEq(s[:6], []int{0x15, 0, 0x5F, 0x75, 0, 0x3F}) || s[6] != -192 || len(s) != 6+64 {
		t.Errorf("show stream head = % x (len %d)", s[:7], len(s))
	}
	if ssdDataBytes(m, n) != 12288 {
		t.Error("show must send 12288 bytes")
	}
	if !bytes.Equal(m.Events[n+6].Data[:1], []byte{0xF8}) {
		t.Error("first data byte")
	}
	n = len(m.Events)
	_ = d.ShowRegion(10, 20, 4, 2)
	if !ssdEq(ssdStream(t, m, n), []int{0x15, 10, 13, 0x75, 20, 21, -8, -8}) {
		t.Errorf("ShowRegion = % x", ssdStream(t, m, n))
	}
	n = len(m.Events)
	_ = d.ShowRegion(90, 60, 20, 20)
	if s := ssdStream(t, m, n); !ssdEq(s[:6], []int{0x15, 90, 95, 0x75, 60, 63}) || ssdDataBytes(m, n) != 48 {
		t.Errorf("ShowRegion clipped = % x", s)
	}
	n = len(m.Events)
	_ = d.ShowRegion(200, 0, 5, 5)
	if len(m.Events) != n {
		t.Error("off-panel region must send nothing")
	}
}

func TestSSD1331HardwareAndSettings(t *testing.T) {
	d, m := newSSDFull(t)
	run := func(label string, fn func() error, want []int) {
		t.Helper()
		n := len(m.Events)
		if err := fn(); err != nil {
			t.Fatalf("%s: %v", label, err)
		}
		if got := ssdStream(t, m, n); !ssdEq(got, want) {
			t.Errorf("%s = % x, want % x", label, got, want)
		}
	}
	run("HwLine", func() error { return d.HwLine(1, 2, 3, 4, SSD1331Red) }, []int{0x21, 1, 2, 3, 4, 0x3E, 0, 0})
	run("HwRect filled", func() error { return d.HwRect(10, 20, 5, 6, SSD1331Green, SSD1331Blue, true) },
		[]int{0x26, 1, 0x22, 10, 20, 14, 25, 0, 0x3F, 0, 0, 0, 0x3E})
	run("HwRect outline", func() error { return d.HwRect(10, 20, 5, 6, SSD1331White, 0, false) },
		[]int{0x26, 0, 0x22, 10, 20, 14, 25, 0x3E, 0x3F, 0x3E, 0, 0, 0})
	run("HwRect zero", func() error { return d.HwRect(10, 20, 0, 6, SSD1331White, 0, false) }, nil)
	run("HwClear", func() error { return d.HwClear(0, 1, 2, 3) }, []int{0x25, 0, 1, 2, 3})
	run("HwDim", func() error { return d.HwDim(0, 1, 2, 3) }, []int{0x24, 0, 1, 2, 3})
	run("HwCopy", func() error { return d.HwCopy(1, 2, 3, 4, 5, 6) }, []int{0x23, 1, 2, 3, 4, 5, 6})
	run("Scroll", func() error { return d.Scroll(1, 0, 64, 2, 7) }, []int{0x27, 1, 0, 64, 2, 3, 0x2F})
	run("ScrollStop", d.ScrollStop, []int{0x2E})
	run("SetContrast", func() error { return d.SetContrast(1, 2, 3) }, []int{0x81, 1, 0x82, 2, 0x83, 3})
	run("SetMasterCurrent 16", func() error { return d.SetMasterCurrent(16) }, []int{0x87, 0x0F})
	run("SetMasterCurrent 0", func() error { return d.SetMasterCurrent(0) }, []int{0x87, 0x00})
	run("SetDisplayMode", func() error { return d.SetDisplayMode(SSD1331ModeInverse) }, []int{0xA7})
	run("SetFlip none", func() error { return d.SetFlip(false, false) }, []int{0xA0, 0x60})
	run("SetFlip both", func() error { return d.SetFlip(true, true) }, []int{0xA0, 0x72})
	run("SetPowerSave on", func() error { return d.SetPowerSave(true) }, []int{0xB0, 0x1A})
	run("SetPowerSave off", func() error { return d.SetPowerSave(false) }, []int{0xB0, 0x0B})
	run("DimMode", func() error { return d.DimMode(1, 2, 3, 0xFF) }, []int{0xAB, 0, 1, 2, 3, 0x1F, 0xAC})
	run("DisplayOff/On", func() error {
		if err := d.DisplayOff(); err != nil {
			return err
		}
		return d.DisplayOn()
	}, []int{0xAE, 0xAF})
	run("GrayLinear", d.GrayLinear, []int{0xB9})
	run("SendCommand", func() error { return d.SendCommand(0xAA, 0x12, 0x34) }, []int{0xAA, 0x12, 0x34})
}
