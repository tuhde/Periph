package imu

import (
	"bytes"
	"math"
	"sync"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// bnoMock is a flat register-map RegisterConnection that logs every write.
type bnoMock struct {
	regs map[byte]byte
	log  [][]byte
}

func newBnoMock() *bnoMock {
	m := &bnoMock{regs: map[byte]byte{}}
	m.set(0x00, 0xA0, 0xFB, 0x32, 0x0F, 0x11, 0x03, 0x15)
	return m
}

func (m *bnoMock) set(reg byte, values ...byte) {
	for i, v := range values {
		m.regs[reg+byte(i)] = v
	}
}

func (m *bnoMock) writesTo(reg byte) []byte {
	var out []byte
	for _, w := range m.log {
		if len(w) == 2 && w[0] == reg {
			out = append(out, w[1])
		}
	}
	return out
}

func (m *bnoMock) Write(data []byte) error {
	m.log = append(m.log, append([]byte(nil), data...))
	for i := 1; i < len(data); i++ {
		m.regs[data[0]+byte(i-1)] = data[i]
	}
	return nil
}

func (m *bnoMock) Read(n int) ([]byte, error) { return make([]byte, n), nil }

func (m *bnoMock) WriteRead(data []byte, n int) ([]byte, error) {
	out := make([]byte, n)
	for i := 0; i < n; i++ {
		out[i] = m.regs[data[0]+byte(i)]
	}
	return out, nil
}

func (m *bnoMock) ReadReg(reg uint32, length int) ([]byte, error) {
	return m.WriteRead([]byte{byte(reg)}, length)
}

func (m *bnoMock) WriteReg(reg uint32, data []byte) error {
	return m.Write(append([]byte{byte(reg)}, data...))
}

func (m *bnoMock) Close() error                { return nil }
func (m *bnoMock) Enable()                     {}
func (m *bnoMock) Disable()                    {}
func (m *bnoMock) IsEnabled() bool             { return true }
func (m *bnoMock) IntPin() connection.InputPin { return nil }
func (m *bnoMock) EnPin() connection.OutputPin { return nil }

func newBNO(t *testing.T) (*bnoMock, *BNO055Full) {
	t.Helper()
	m := newBnoMock()
	s, err := NewBNO055Full(m)
	if err != nil {
		t.Fatalf("NewBNO055Full: %v", err)
	}
	return m, s
}

func bnoLe(vals ...int16) []byte {
	var out []byte
	for _, v := range vals {
		out = append(out, byte(uint16(v)&0xFF), byte(uint16(v)>>8))
	}
	return out
}

func bnoNear(a, b float64) bool { return math.Abs(a-b) < 1e-4 }

func TestBNO055Init(t *testing.T) {
	m, _ := newBNO(t)
	var w2 [][]byte
	for _, w := range m.log {
		if len(w) == 2 {
			w2 = append(w2, w)
		}
	}
	for _, want := range [][]byte{{0x3E, 0x00}, {0x07, 0x00}, {0x3B, 0x86}} {
		found := false
		for _, w := range w2 {
			if bytes.Equal(w, want) {
				found = true
			}
		}
		if !found {
			t.Errorf("missing init write %v", want)
		}
	}
	if !bytes.Equal(w2[len(w2)-1], []byte{0x3D, 0x0C}) {
		t.Errorf("last init write = %v, want OPR_MODE=NDOF", w2[len(w2)-1])
	}
}

func TestBNO055ChipIDMismatch(t *testing.T) {
	m := newBnoMock()
	m.regs[0x00] = 0x55
	if _, err := NewBNO055Minimal(m); err == nil {
		t.Fatal("expected CHIP_ID error")
	}
}

func TestBNO055Euler(t *testing.T) {
	m, s := newBNO(t)
	m.set(0x1A, bnoLe(1414, -900, 450)...)
	h, r, p, err := s.Euler()
	if err != nil || !bnoNear(float64(h), 1414.0/900) || !bnoNear(float64(r), -1) || !bnoNear(float64(p), 0.5) {
		t.Errorf("euler = %v %v %v %v", h, r, p, err)
	}
}

func TestBNO055Vectors(t *testing.T) {
	m, s := newBNO(t)
	m.set(0x20, bnoLe(16384, -8192, 0, 4096)...)
	w, x, y, z, _ := s.Quaternion()
	if w != 1 || x != -0.5 || y != 0 || z != 0.25 {
		t.Errorf("quaternion = %v %v %v %v", w, x, y, z)
	}
	m.set(0x28, bnoLe(100, -250, 981)...)
	x, y, z, _ = s.LinearAcceleration()
	if !bnoNear(float64(x), 1) || !bnoNear(float64(y), -2.5) || !bnoNear(float64(z), 9.81) {
		t.Errorf("linear = %v %v %v", x, y, z)
	}
	m.set(0x2E, bnoLe(0, 0, 981)...)
	_, _, z, _ = s.Gravity()
	if !bnoNear(float64(z), 9.81) {
		t.Errorf("gravity z = %v", z)
	}
	m.set(0x08, bnoLe(-100, 200, 300)...)
	x, y, z, _ = s.Acceleration()
	if x != -1 || y != 2 || z != 3 {
		t.Errorf("acceleration = %v %v %v", x, y, z)
	}
	m.set(0x0E, bnoLe(160, -32, 16)...)
	x, y, z, _ = s.MagneticField()
	if x != 10 || y != -2 || z != 1 {
		t.Errorf("magnetic = %v %v %v", x, y, z)
	}
	m.set(0x14, bnoLe(900, 0, -450)...)
	x, y, z, _ = s.AngularRate()
	if x != 1 || y != 0 || z != -0.5 {
		t.Errorf("angular = %v %v %v", x, y, z)
	}
	m.set(0x34, 0xE7)
	if tc, _ := s.Temperature(); tc != -25 {
		t.Errorf("temperature = %d, want -25", tc)
	}
}

func TestBNO055Calibration(t *testing.T) {
	m, s := newBNO(t)
	m.set(0x35, 0xE4)
	a, b, c, d, _ := s.CalibrationStatus()
	if a != 3 || b != 2 || c != 1 || d != 0 {
		t.Errorf("status = %d %d %d %d", a, b, c, d)
	}
	if ok, _ := s.IsCalibrated(); ok {
		t.Error("IsCalibrated true for 0xE4")
	}
	m.set(0x35, 0xFF)
	if ok, _ := s.IsCalibrated(); !ok {
		t.Error("IsCalibrated false for 0xFF")
	}

	blob := make([]byte, 22)
	for i := range blob {
		blob[i] = byte(i + 1)
	}
	m.set(0x55, blob...)
	m.log = nil
	got, err := s.GetCalibration()
	if err != nil || !bytes.Equal(got, blob) {
		t.Errorf("GetCalibration = %v %v", got, err)
	}
	if w := m.writesTo(0x3D); !bytes.Equal(w, []byte{0x00, 0x0C}) {
		t.Errorf("get_calibration mode writes = %v", w)
	}
	m.log = nil
	if err := s.SetCalibration(blob); err != nil {
		t.Fatal(err)
	}
	n := 0
	for _, w := range m.log {
		if len(w) == 2 && w[0] >= 0x55 && w[0] <= 0x6A {
			if w[0] != 0x55+byte(n) || w[1] != blob[n] {
				t.Errorf("calibration write %d = %v", n, w)
			}
			n++
		}
	}
	if n != 22 {
		t.Errorf("calibration writes = %d, want 22", n)
	}
	if err := s.SetCalibration(make([]byte, 21)); err == nil {
		t.Error("expected length error")
	}
}

func TestBNO055Modes(t *testing.T) {
	m, s := newBNO(t)
	m.log = nil
	if err := s.SetMode(BNO055ModeIMU); err != nil {
		t.Fatal(err)
	}
	if w := m.writesTo(0x3D); !bytes.Equal(w, []byte{0x00, 0x08}) {
		t.Errorf("set_mode writes = %v", w)
	}
	m.set(0x3D, 0x08)
	if mode, _ := s.GetMode(); mode != 8 {
		t.Errorf("mode = %d", mode)
	}
	if err := s.SetMode(13); err == nil {
		t.Error("expected range error")
	}
	_ = s.SetMode(BNO055ModeNDOF)
}

func TestBNO055Configuration(t *testing.T) {
	m, s := newBNO(t)
	m.log = nil
	_ = s.SetAxisRemap(0x21, 0x02)
	if !bytes.Equal(m.writesTo(0x41), []byte{0x21}) || !bytes.Equal(m.writesTo(0x42), []byte{0x02}) {
		t.Error("axis remap writes wrong")
	}
	if !bytes.Equal(m.writesTo(0x3D), []byte{0x00, 0x0C}) {
		t.Errorf("axis remap mode writes = %v", m.writesTo(0x3D))
	}
	_ = s.SetPowerMode(BNO055PowerLowPower)
	if !bytes.Equal(m.writesTo(0x3E), []byte{1}) {
		t.Errorf("power mode writes = %v", m.writesTo(0x3E))
	}
	if err := s.SetPowerMode(3); err == nil {
		t.Error("expected power mode error")
	}
	_ = s.SetTemperatureSource(BNO055TempSourceGyroscope)
	if !bytes.Equal(m.writesTo(0x40), []byte{1}) {
		t.Errorf("temp source writes = %v", m.writesTo(0x40))
	}
	m.log = nil
	_ = s.UseExternalCrystal(true)
	_ = s.UseExternalCrystal(false)
	if !bytes.Equal(m.writesTo(0x3F), []byte{0x80, 0x00}) {
		t.Errorf("crystal writes = %v", m.writesTo(0x3F))
	}
}

func TestBNO055SelfTestStatusRevisionsUniqueID(t *testing.T) {
	m, s := newBNO(t)
	m.set(0x36, 0x0D)
	m.log = nil
	st, err := s.SelfTest()
	if err != nil || st != (BNO055SelfTestResult{Acc: true, Mag: false, Gyr: true, MCU: true}) {
		t.Errorf("self test = %+v %v", st, err)
	}
	if !bytes.Equal(m.writesTo(0x3F), []byte{0x01}) || !bytes.Equal(m.writesTo(0x3D), []byte{0x00, 0x0C}) {
		t.Errorf("self test writes: trigger %v mode %v", m.writesTo(0x3F), m.writesTo(0x3D))
	}
	m.set(0x39, 1, 3)
	if status, code, _ := s.SystemStatus(); status != 1 || code != 3 {
		t.Errorf("system status = %d %d", status, code)
	}
	rev, _ := s.Revisions()
	if rev != (BNO055Revisions{ChipID: 0xA0, AccID: 0xFB, MagID: 0x32, GyrID: 0x0F, SwRevision: 0x0311, Bootloader: 0x15}) {
		t.Errorf("revisions = %+v", rev)
	}
	uid := make([]byte, 16)
	for i := range uid {
		uid[i] = byte(i)
	}
	m.set(0x50, uid...)
	m.log = nil
	got, _ := s.UniqueID()
	if !bytes.Equal(got, uid) || !bytes.Equal(m.writesTo(0x07), []byte{1, 0}) {
		t.Errorf("unique id = %v, page writes %v", got, m.writesTo(0x07))
	}
}

func TestBNO055Interrupts(t *testing.T) {
	m, s := newBNO(t)
	m.set(0x10, 0)
	m.set(0x0F, 0)
	m.log = nil
	_ = s.EnableInterrupt(BNO055SourceAccBsxDrdy | BNO055SourceGyrDrdy)
	if !bytes.Equal(m.writesTo(0x10), []byte{0x11}) || !bytes.Equal(m.writesTo(0x0F), []byte{0x11}) || !bytes.Equal(m.writesTo(0x07), []byte{1, 0}) {
		t.Errorf("enable writes: en %v msk %v page %v", m.writesTo(0x10), m.writesTo(0x0F), m.writesTo(0x07))
	}
	m.set(0x10, 0x11)
	m.set(0x0F, 0x11)
	m.log = nil
	_ = s.DisableInterrupt(BNO055SourceGyrDrdy)
	if !bytes.Equal(m.writesTo(0x10), []byte{0x01}) || !bytes.Equal(m.writesTo(0x0F), []byte{0x01}) {
		t.Errorf("disable writes: en %v msk %v", m.writesTo(0x10), m.writesTo(0x0F))
	}
	m.set(0x37, 0x41)
	if st, _ := s.PollInterrupt(); st != 0x41 {
		t.Errorf("poll = 0x%02X", st)
	}

	var mu sync.Mutex
	seen := make(chan byte, 4)
	_ = s.OnInterrupt(func(v byte) {
		mu.Lock()
		defer mu.Unlock()
		select {
		case seen <- v:
		default:
		}
	})
	got := <-seen
	_ = s.OffInterrupt()
	if got != 0x41 {
		t.Errorf("callback status = 0x%02X", got)
	}
}

func TestBNO055Reset(t *testing.T) {
	m, s := newBNO(t)
	m.log = nil
	if err := s.Reset(); err != nil {
		t.Fatal(err)
	}
	w := m.writesTo(0x3F)
	if len(w) == 0 || w[0] != 0x20 {
		t.Errorf("reset trigger writes = %v", w)
	}
	om := m.writesTo(0x3D)
	if om[len(om)-1] != 0x0C || m.writesTo(0x3B)[0] != 0x86 {
		t.Error("reset did not re-initialise")
	}
}
