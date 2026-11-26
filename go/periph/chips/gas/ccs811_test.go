package gas

import (
	"bytes"
	"errors"
	"math"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// ALG_RESULT_DATA: eCO2 500 ppm, TVOC 15 ppb, STATUS 0x98, ERROR_ID 0, RAW 0x8C88.
var ccs811TestResult = []byte{0x01, 0xF4, 0x00, 0x0F, 0x98, 0x00, 0x8C, 0x88}

// ccs811Mock treats every register as its own mailbox; STATUS tracks FW_MODE and
// DATA_READY, APP_START sets FW_MODE and reading ALG_RESULT_DATA clears DATA_READY.
type ccs811Mock struct {
	mail            map[byte][]byte
	writes          [][]byte
	appValid        bool
	fwMode          bool
	dataReady       bool
	readyAfterStart bool
	pin             connection.InputPin
}

func newCCS811Mock() *ccs811Mock {
	return &ccs811Mock{
		mail: map[byte][]byte{
			0x20: {0x81}, 0x21: {0x12}, 0x23: {0x10, 0x00}, 0x24: {0x11, 0x02},
			0x02: ccs811TestResult, 0x03: {0x8C, 0x88}, 0x06: {0x06, 0x40, 0x03, 0x20},
			0x11: {0x84, 0x7B}, 0xE0: {0x00},
		},
		appValid: true, dataReady: true, readyAfterStart: true,
	}
}

func (m *ccs811Mock) status() byte {
	var s byte
	if m.fwMode {
		s |= 0x80
	}
	if m.appValid {
		s |= 0x10
	}
	if m.dataReady {
		s |= 0x08
	}
	return s
}

func (m *ccs811Mock) Write(data []byte) error {
	m.writes = append(m.writes, append([]byte(nil), data...))
	switch {
	case data[0] == 0xF4 && len(data) == 1:
		if m.readyAfterStart {
			m.fwMode = true
		}
	case data[0] == 0xFF:
		m.fwMode = false
	case len(data) >= 2:
		m.mail[data[0]] = append([]byte(nil), data[1:]...)
	}
	return nil
}

func (m *ccs811Mock) ReadReg(reg uint32, n int) ([]byte, error) {
	m.writes = append(m.writes, []byte{byte(reg)})
	if reg == 0x00 {
		return []byte{m.status()}, nil
	}
	out := make([]byte, n)
	copy(out, m.mail[byte(reg)])
	if reg == 0x02 {
		m.dataReady = false
	}
	return out, nil
}

func (m *ccs811Mock) WriteReg(reg uint32, data []byte) error {
	return m.Write(append([]byte{byte(reg)}, data...))
}
func (m *ccs811Mock) Read(n int) ([]byte, error) { return make([]byte, n), nil }
func (m *ccs811Mock) WriteRead(data []byte, n int) ([]byte, error) {
	return m.ReadReg(uint32(data[0]), n)
}
func (m *ccs811Mock) Close() error                { return nil }
func (m *ccs811Mock) Enable()                     {}
func (m *ccs811Mock) Disable()                    {}
func (m *ccs811Mock) IsEnabled() bool             { return true }
func (m *ccs811Mock) IntPin() connection.InputPin { return m.pin }
func (m *ccs811Mock) EnPin() connection.OutputPin { return nil }

func (m *ccs811Mock) last(reg byte) []byte {
	var v []byte
	for _, w := range m.writes {
		if len(w) >= 2 && w[0] == reg {
			v = w[1:]
		}
	}
	return v
}

func (m *ccs811Mock) wrote(frame []byte, from int) bool {
	for _, w := range m.writes[from:] {
		if bytes.Equal(w, frame) {
			return true
		}
	}
	return false
}

type ccs811FakePin struct {
	handler func()
	trigger connection.Trigger
}

func (p *ccs811FakePin) OnEdge(trigger connection.Trigger, handler func()) func() {
	p.handler, p.trigger = handler, trigger
	return func() { p.handler = nil }
}

func (p *ccs811FakePin) IsHigh() (bool, error) { return true, nil }

func newCCS811FullTest(t *testing.T) (*ccs811Mock, *CCS811Full) {
	t.Helper()
	c := newCCS811Mock()
	s, _ := NewCCS811Full(c)
	if len(c.writes) != 0 {
		t.Fatal("constructor touched the bus")
	}
	if err := s.Init(); err != nil {
		t.Fatalf("Init: %v", err)
	}
	return c, s
}

func TestCCS811InitAndRead(t *testing.T) {
	c, s := newCCS811FullTest(t)
	if !c.wrote([]byte{0xF4}, 0) || !bytes.Equal(c.last(0x01), []byte{0x10}) {
		t.Fatal("init did not start the application in mode 1")
	}
	m, err := s.Read()
	if err != nil || m.ECO2ppm != 500 || m.TVOCppb != 15 || c.dataReady {
		t.Fatalf("Read = %+v, %v", m, err)
	}
	c.dataReady = true
	if v, err := s.ReadECO2(); err != nil || v != 500 {
		t.Fatalf("ReadECO2 = %d, %v", v, err)
	}
	c.dataReady = true
	if v, err := s.ReadTVOC(); err != nil || v != 15 {
		t.Fatalf("ReadTVOC = %d, %v", v, err)
	}
	if r, _ := s.DataReady(); r {
		t.Fatal("DataReady after read")
	}
	cur, volt, err := s.ReadRaw()
	if err != nil || cur != 35 || math.Abs(volt-136*1.65/1023) > 1e-9 {
		t.Fatalf("ReadRaw = %d %f %v", cur, volt, err)
	}
}

func TestCCS811ReadError(t *testing.T) {
	c, s := newCCS811FullTest(t)
	c.mail[0x02] = []byte{0x01, 0xF4, 0x00, 0x0F, 0x99, 0x08}
	c.mail[0xE0] = []byte{0x08}
	_, err := s.Read()
	if !errors.Is(err, ErrCCS811Sensor) {
		t.Fatalf("Read error = %v", err)
	}
}

func TestCCS811ModeAndInterruptBits(t *testing.T) {
	c, s := newCCS811FullTest(t)
	steps := []struct {
		f    func() error
		want byte
	}{
		{func() error { return s.EnableInterrupt(CCS811SourceDataReady) }, 0x18},
		{func() error { return s.SetMode(2) }, 0x28},
		{func() error { return s.EnableInterrupt(CCS811SourceThreshold) }, 0x2C},
		{func() error { return s.DisableInterrupt(CCS811SourceThreshold) }, 0x20},
		{func() error { return s.EnableInterrupt(CCS811SourceThreshold) }, 0x2C},
		{func() error { return s.DisableInterrupt(CCS811SourceDataReady) }, 0x24},
		{func() error { return s.DisableInterrupt(CCS811SourceThreshold) }, 0x20},
		{func() error { return s.SetMode(4) }, 0x40},
		{func() error { return s.SetMode(0) }, 0x00},
	}
	for i, st := range steps {
		if err := st.f(); err != nil || !bytes.Equal(c.last(0x01), []byte{st.want}) {
			t.Fatalf("step %d: MEAS_MODE = % X, %v", i, c.last(0x01), err)
		}
	}
	if s.SetMode(5) != ErrCCS811Argument || s.SetMode(-1) != ErrCCS811Argument {
		t.Fatal("SetMode accepted an invalid mode")
	}
}

func TestCCS811EnvironmentThresholdsBaseline(t *testing.T) {
	c, s := newCCS811FullTest(t)
	if err := s.SetEnvironment(48.5, 22.5); err != nil || !bytes.Equal(c.last(0x05), []byte{0x61, 0x00, 0x5F, 0x00}) {
		t.Fatalf("SetEnvironment = % X, %v", c.last(0x05), err)
	}
	if err := s.SetEnvironment(50, 25); err != nil || !bytes.Equal(c.last(0x05), []byte{0x64, 0x00, 0x64, 0x00}) {
		t.Fatal("SetEnvironment default")
	}
	for _, v := range [][2]float64{{-1, 25}, {101, 25}, {50, -26}, {50, 103}} {
		if s.SetEnvironment(v[0], v[1]) != ErrCCS811Argument {
			t.Fatalf("SetEnvironment(%v) accepted", v)
		}
	}
	if err := s.SetThresholds(1000, 2000, 50); err != nil || !bytes.Equal(c.last(0x10), []byte{0x03, 0xE8, 0x07, 0xD0, 0x32}) {
		t.Fatal("SetThresholds")
	}
	if s.SetThresholds(2000, 1000, 50) != ErrCCS811Argument {
		t.Fatal("SetThresholds accepted low >= high")
	}
	if b, err := s.GetBaseline(); err != nil || b != 0x847B {
		t.Fatalf("GetBaseline = %X", b)
	}
	if err := s.SetBaseline(0x1234); err != nil || !bytes.Equal(c.last(0x11), []byte{0x12, 0x34}) {
		t.Fatal("SetBaseline")
	}
	if b, _ := s.GetBaseline(); b != 0x1234 {
		t.Fatal("baseline round trip")
	}
}

func TestCCS811NTCStatusVersions(t *testing.T) {
	c, s := newCCS811FullTest(t)
	if vr, vn, err := s.ReadNTC(); err != nil || vr != 1600 || vn != 800 {
		t.Fatal("ReadNTC")
	}
	if r, err := s.NTCResistance(100000); err != nil || math.Abs(r-50000) > 1e-9 {
		t.Fatalf("NTCResistance = %f", r)
	}
	c.mail[0x06] = []byte{0x00, 0x00, 0x03, 0x20}
	if _, err := s.NTCResistance(100000); err != ErrCCS811Argument {
		t.Fatal("NTCResistance without reference")
	}
	c.dataReady = false
	if st, _ := s.Status(); st != 0x90 {
		t.Fatalf("Status = %X", st)
	}
	c.mail[0xE0] = []byte{0x10}
	if e, _ := s.Error(); e != 0x10 {
		t.Fatal("Error")
	}
	if id, ver, err := s.HardwareVersion(); err != nil || id != 0x81 || ver != 0x12 {
		t.Fatal("HardwareVersion")
	}
	boot, app, err := s.FirmwareVersion()
	if err != nil || boot != (CCS811Version{1, 0, 0}) || app != (CCS811Version{1, 1, 2}) {
		t.Fatalf("FirmwareVersion = %+v %+v", boot, app)
	}
}

func TestCCS811Interrupt(t *testing.T) {
	c, s := newCCS811FullTest(t)
	if st, _ := s.PollInterrupt(); st != 0x98 || !c.dataReady {
		t.Fatal("PollInterrupt")
	}
	pin := &ccs811FakePin{}
	c.pin = pin
	var seen []uint8
	if err := s.OnInterrupt(func(st uint8) { seen = append(seen, st) }); err != nil || pin.handler == nil || pin.trigger != connection.Falling {
		t.Fatalf("OnInterrupt: %v", err)
	}
	pin.handler()
	if len(seen) != 1 || seen[0] != 0x98 {
		t.Fatalf("dispatch = %v", seen)
	}
	c.dataReady = false
	pin.handler()
	if len(seen) != 1 {
		t.Fatal("spurious dispatch")
	}
	if err := s.OffInterrupt(); err != nil || pin.handler != nil {
		t.Fatal("OffInterrupt")
	}
}

func TestCCS811SoftReset(t *testing.T) {
	c, s := newCCS811FullTest(t)
	n := len(c.writes)
	_ = s.SetMode(3)
	if err := s.SoftReset(); err != nil {
		t.Fatalf("SoftReset: %v", err)
	}
	if !c.wrote([]byte{0xFF, 0x11, 0xE5, 0x72, 0x8A}, n) || !c.wrote([]byte{0xF4}, n) || !bytes.Equal(c.last(0x01), []byte{0x10}) {
		t.Fatal("SoftReset sequence")
	}
	c.dataReady = true
	if m, err := s.Read(); err != nil || m.ECO2ppm != 500 {
		t.Fatal("Read after reset")
	}
}

func TestCCS811InitErrors(t *testing.T) {
	c := newCCS811Mock()
	c.fwMode = true
	m, _ := NewCCS811Minimal(c)
	if err := m.Init(); err != nil || c.wrote([]byte{0xF4}, 0) {
		t.Fatal("Init in application mode")
	}
	if r, err := m.Read(); err != nil || r != (CCS811Measurement{500, 15}) {
		t.Fatal("Minimal Read")
	}
	c = newCCS811Mock()
	c.mail[0x20] = []byte{0x55}
	m, _ = NewCCS811Minimal(c)
	if m.Init() != ErrCCS811ChipID {
		t.Fatal("bad HW_ID")
	}
	c = newCCS811Mock()
	c.appValid = false
	m, _ = NewCCS811Minimal(c)
	if m.Init() != ErrCCS811NoApplication {
		t.Fatal("no application")
	}
	c = newCCS811Mock()
	c.readyAfterStart = false
	m, _ = NewCCS811Minimal(c)
	if m.Init() != ErrCCS811AppStart {
		t.Fatal("APP_START ignored")
	}
}
