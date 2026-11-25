package adcdac

import (
	"math"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// ads7828Conn is an in-memory RegisterConnection: ReadReg(command, 2) returns
// the two bytes preloaded for that command byte.
type ads7828Conn struct {
	results map[uint32][2]byte
	writes  [][]byte
}

func newADS7828Conn() *ads7828Conn { return &ads7828Conn{results: map[uint32][2]byte{}} }

func (m *ads7828Conn) set(cmd uint32, hi, lo byte) { m.results[cmd] = [2]byte{hi, lo} }

func (m *ads7828Conn) Write(data []byte) error {
	m.writes = append(m.writes, append([]byte(nil), data...))
	return nil
}
func (m *ads7828Conn) Read(n int) ([]byte, error) { return make([]byte, n), nil }
func (m *ads7828Conn) WriteRead(data []byte, n int) ([]byte, error) {
	return m.ReadReg(uint32(data[0]), n)
}
func (m *ads7828Conn) ReadReg(reg uint32, length int) ([]byte, error) {
	m.writes = append(m.writes, []byte{byte(reg)})
	r := m.results[reg]
	return []byte{r[0], r[1]}[:length], nil
}
func (m *ads7828Conn) WriteReg(reg uint32, data []byte) error { return nil }
func (m *ads7828Conn) Close() error                           { return nil }
func (m *ads7828Conn) Enable()                                {}
func (m *ads7828Conn) Disable()                               {}
func (m *ads7828Conn) IsEnabled() bool                        { return true }
func (m *ads7828Conn) IntPin() connection.InputPin            { return nil }
func (m *ads7828Conn) EnPin() connection.OutputPin            { return nil }

func (m *ads7828Conn) last() byte { return m.writes[len(m.writes)-1][0] }

func ads7828Near(a, b float32) bool { return math.Abs(float64(a-b)) < 1e-5 }

func TestADS7828InternalReferenceAndSingleEnded(t *testing.T) {
	m := newADS7828Conn()
	adc, _ := NewADS7828Full(m, 0)
	if len(m.writes) != 0 {
		t.Fatalf("construction made bus traffic: %v", m.writes)
	}
	if err := adc.Init(); err != nil || len(m.writes) != 1 || m.writes[0][0] != 0x08 {
		t.Fatalf("Init writes = %v, err %v", m.writes, err)
	}
	if adc.GetReference() != 2.5 || !adc.UsesInternalReference() {
		t.Errorf("reference = %v internal %v", adc.GetReference(), adc.UsesInternalReference())
	}

	m.set(0x8C, 0x0A, 0xBC)
	if raw, _ := adc.ReadRaw(0); raw != 0xABC || m.last() != 0x8C {
		t.Errorf("ch0 raw = %#x cmd %#x", raw, m.last())
	}
	m.set(0xCC, 0x0F, 0xFF)
	if raw, _ := adc.ReadRaw(1); raw != 0xFFF || m.last() != 0xCC {
		t.Errorf("ch1 raw = %#x cmd %#x", raw, m.last())
	}
	adc.ReadRaw(2)
	if m.last() != 0x9C {
		t.Errorf("ch2 cmd = %#x", m.last())
	}
	adc.ReadRaw(7)
	if m.last() != 0xFC {
		t.Errorf("ch7 cmd = %#x", m.last())
	}
	m.set(0x8C, 0xF1, 0x23)
	if raw, _ := adc.ReadRaw(0); raw != 0x123 {
		t.Errorf("nibble mask = %#x", raw)
	}
	adc.ReadRaw(99)
	if m.last() != 0xFC {
		t.Errorf("clamp high cmd = %#x", m.last())
	}
	adc.ReadRaw(-3)
	if m.last() != 0x8C {
		t.Errorf("clamp low cmd = %#x", m.last())
	}
	m.set(0x8C, 0x08, 0x00)
	if v, _ := adc.ReadVoltage(0); !ads7828Near(v, 1.25) {
		t.Errorf("voltage = %v", v)
	}
	if all, _ := adc.ReadAll(); !ads7828Near(all[0], 1.25) || all[7] != 0 {
		t.Errorf("read all = %v", all)
	}
}

func TestADS7828Differential(t *testing.T) {
	m := newADS7828Conn()
	adc, _ := NewADS7828Full(m, 0)
	m.set(0x0C, 0x04, 0x00)
	if raw, _ := adc.ReadDifferentialRaw(0, false); raw != 0x400 || m.last() != 0x0C {
		t.Errorf("diff raw = %#x cmd %#x", raw, m.last())
	}
	adc.ReadDifferentialRaw(3, true)
	if m.last() != 0x7C {
		t.Errorf("inverted pair 3 cmd = %#x", m.last())
	}
	if v, _ := adc.ReadDifferential(0, false); !ads7828Near(v, 0x400/4096.0*2.5) {
		t.Errorf("diff voltage = %v", v)
	}
	m.set(0x0C, 0x00, 0x00)
	m.set(0x4C, 0x02, 0x00)
	if v, _ := adc.ReadSignedDifferential(0); !ads7828Near(v, -0x200/4096.0*2.5) {
		t.Errorf("signed negative = %v", v)
	}
	m.set(0x0C, 0x02, 0x00)
	m.set(0x4C, 0x00, 0x00)
	if v, _ := adc.ReadSignedDifferential(0); !ads7828Near(v, 0x200/4096.0*2.5) {
		t.Errorf("signed positive = %v", v)
	}
}

func TestADS7828ReferencePowerAndScan(t *testing.T) {
	m := newADS7828Conn()
	adc, _ := NewADS7828Full(m, 5.0)
	adc.Init()
	if len(m.writes) != 0 || adc.UsesInternalReference() || adc.GetReference() != 5.0 {
		t.Fatalf("external init: writes %v ref %v", m.writes, adc.GetReference())
	}
	m.set(0x80, 0x08, 0x00)
	if v, _ := adc.ReadVoltage(0); !ads7828Near(v, 2.5) || m.last() != 0x80 {
		t.Errorf("external voltage = %v cmd %#x", v, m.last())
	}
	if err := adc.SetPowerMode(ADS7828PowerDownBetween); err != nil {
		t.Errorf("external allows PD0: %v", err)
	}

	adc.SetReference(0)
	if m.writes[len(m.writes)-1][0] != 0x08 || adc.GetReference() != 2.5 {
		t.Errorf("SetReference(0) writes %v", m.writes)
	}
	if adc.SetPowerMode(1) != ErrADS7828InvalidPowerMode || adc.SetPowerMode(7) != ErrADS7828InvalidPowerMode {
		t.Errorf("internal reference must reject PD1 = 0 and out-of-range")
	}
	if err := adc.SetPowerMode(ADS7828RefOnADCOff); err != nil {
		t.Errorf("PD2 rejected: %v", err)
	}
	adc.ReadRaw(0)
	if m.last() != 0x88 {
		t.Errorf("PD2 cmd = %#x", m.last())
	}
	adc.SetPowerMode(ADS7828RefOnADCOn)

	adc.PowerDown()
	if m.last() != 0x00 {
		t.Errorf("power down cmd = %#x", m.last())
	}
	m.set(0x8C, 0x00, 0x01)
	m.set(0xCC, 0x00, 0x02)
	scan, _ := adc.ReadScan([]int{0, 1})
	if len(scan) != 2 || scan[0] != 1 || scan[1] != 2 {
		t.Errorf("scan = %v", scan)
	}
	if m.writes[len(m.writes)-3][0] != 0x08 {
		t.Errorf("read after PowerDown did not re-enable the reference: %v", m.writes)
	}
	if !ads7828Near(adc.LSB(), 2.5/4096) {
		t.Errorf("lsb = %v", adc.LSB())
	}

	e, _ := NewADS7828Minimal(newADS7828Conn(), 0)
	if raw, err := e.ReadRaw(0); err != nil || raw != 0 {
		t.Errorf("minimal read = %v %v", raw, err)
	}
}
