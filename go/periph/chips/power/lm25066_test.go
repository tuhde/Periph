package power

import (
	"math"
	"testing"
)

// lm25066Mock holds one byte string per PMBus command so that writes do not
// overlap neighbouring commands.
type lm25066Mock struct {
	*mockConnection
	cmds map[byte][]byte
}

func newLM25066Mock() *lm25066Mock {
	return &lm25066Mock{mockConnection: newMockConnection(), cmds: map[byte][]byte{}}
}

func (m *lm25066Mock) word(cmd byte, v uint16) { m.cmds[cmd] = []byte{byte(v), byte(v >> 8)} }
func (m *lm25066Mock) b(cmd, v byte)           { m.cmds[cmd] = []byte{v} }
func (m *lm25066Mock) block(cmd byte, words ...uint16) {
	d := []byte{12}
	for _, w := range words {
		d = append(d, byte(w), byte(w>>8))
	}
	m.cmds[cmd] = d
}

func (m *lm25066Mock) WriteReg(reg uint32, data []byte) error {
	m.writes = append(m.writes, append([]byte{byte(reg)}, data...))
	if len(data) > 0 {
		m.cmds[byte(reg)] = append([]byte(nil), data...)
	}
	return nil
}

func (m *lm25066Mock) ReadReg(reg uint32, length int) ([]byte, error) {
	out := make([]byte, length)
	copy(out, m.cmds[byte(reg)])
	return out, nil
}

func (m *lm25066Mock) lastIs(want ...byte) bool {
	if len(m.writes) == 0 {
		return false
	}
	w := m.writes[len(m.writes)-1]
	if len(w) != len(want) {
		return false
	}
	for i := range w {
		if w[i] != want[i] {
			return false
		}
	}
	return true
}

func lm25066Near(a, b float64) bool { return math.Abs(a-b) < 1e-3 }

func fp(v float64) *float64 { return &v }

func TestLM25066MinimalClGnd(t *testing.T) {
	conn := newLM25066Mock()
	m := NewLM25066Minimal(conn, 5.0, false)
	if len(conn.writes) != 0 {
		t.Errorf("constructor wrote to the bus")
	}
	conn.word(0x88, 2000)
	if v, _ := m.VoltageIn(); !lm25066Near(v, 201800.0/22070.0) {
		t.Errorf("vin = %v", v)
	}
	conn.word(0x8B, 0xF000|1500)
	if v, _ := m.VoltageOut(); !lm25066Near(v, 151800.0/22070.0) {
		t.Errorf("vout = %v", v)
	}
	conn.word(0xD1, 648)
	if v, _ := m.Current(); !lm25066Near(v, 70000.0/68305.0) {
		t.Errorf("current = %v", v)
	}
	conn.word(0xD2, 1000)
	if v, _ := m.Power(); !lm25066Near(v, 103300.0/(736.0*5.0)) {
		t.Errorf("power = %v", v)
	}
	conn.word(0x8D, 0x0190)
	if v, _ := m.Temperature(); !lm25066Near(v, 25.0) {
		t.Errorf("temp = %v", v)
	}
	conn.word(0x8D, 0xFF00)
	if v, _ := m.Temperature(); !lm25066Near(v, -16.0) {
		t.Errorf("negative temp = %v", v)
	}
}

func TestLM25066MinimalClVdd(t *testing.T) {
	conn := newLM25066Mock()
	m := NewLM25066Minimal(conn, 5.0, true)
	conn.word(0xD1, 648)
	if v, _ := m.Current(); !lm25066Near(v, 67900.0/(6854.0*5.0)) {
		t.Errorf("current = %v", v)
	}
	conn.word(0xD2, 1000)
	if v, _ := m.Power(); !lm25066Near(v, 101900.0/(369.0*5.0)) {
		t.Errorf("power = %v", v)
	}
}

func TestLM25066Blocks(t *testing.T) {
	conn := newLM25066Mock()
	f := NewLM25066Full(conn, 1.0, false)
	conn.word(0xD0, 3546-3)
	if v, _ := f.VoltageAux(); !lm25066Near(v, 1.0) {
		t.Errorf("vaux = %v", v)
	}
	conn.block(0xDA, 0x0460, 648, 1500, 2000, 1000, 0x0190)
	b, err := f.ReadBlock()
	if err != nil || b.Diagnostic != 0x0460 || !lm25066Near(b.Current, 70000.0/13661.0) ||
		!lm25066Near(b.VoltageOut, 151800.0/22070.0) || !lm25066Near(b.VoltageIn, 201800.0/22070.0) ||
		!lm25066Near(b.Power, 103300.0/736.0) || !lm25066Near(b.Temperature, 25.0) {
		t.Errorf("block = %+v err %v", b, err)
	}
	conn.block(0xE2, 0, 0, 0, 0, 0, 0)
	if a, _ := f.ReadAverage(); !lm25066Near(a.VoltageIn, 1800.0/22070.0) {
		t.Errorf("avg block = %+v", a)
	}
	conn.block(0xE0, 1, 0, 0, 0, 0, 0)
	if bb, _ := f.BlackBox(); bb.Diagnostic != 1 {
		t.Errorf("black box = %+v", bb)
	}
	conn.cmds[0xDA] = append([]byte{5}, make([]byte, 12)...)
	if _, err := f.ReadBlock(); err != ErrLM25066BlockLength {
		t.Errorf("block length not checked: %v", err)
	}
}

func TestLM25066AveragesAndPeak(t *testing.T) {
	conn := newLM25066Mock()
	f := NewLM25066Full(conn, 1.0, false)
	conn.word(0xDC, 2000)
	if v, _ := f.VoltageInAvg(); !lm25066Near(v, 201800.0/22070.0) {
		t.Errorf("vin avg = %v", v)
	}
	conn.word(0xD5, 1000)
	if v, _ := f.PowerPeak(); !lm25066Near(v, 103300.0/736.0) {
		t.Errorf("peak = %v", v)
	}
	_ = f.ClearPowerPeak()
	if !conn.lastIs(0xD6) {
		t.Errorf("clear peak write = %v", conn.writes[len(conn.writes)-1])
	}
	if err := f.SetAveraging(6); err != nil || !conn.lastIs(0xDB, 6) {
		t.Errorf("set averaging")
	}
	if err := f.SetAveraging(13); err == nil {
		t.Errorf("averaging range not checked")
	}
	if v, _ := f.GetAveraging(); v != 6 {
		t.Errorf("get averaging = %d", v)
	}
}

func TestLM25066Limits(t *testing.T) {
	conn := newLM25066Mock()
	f := NewLM25066Full(conn, 1.0, false)
	_ = f.SetVinUVWarn(fp(10.0))
	if !conn.lastIs(0x58, 0x8D, 0x08) {
		t.Errorf("vin uv warn = %v", conn.writes[len(conn.writes)-1])
	}
	_ = f.SetVinUVWarn(nil)
	if !conn.lastIs(0x58, 0, 0) {
		t.Errorf("vin uv disable")
	}
	_ = f.SetVinOVWarn(nil)
	if !conn.lastIs(0x57, 0xFF, 0x0F) {
		t.Errorf("vin ov disable")
	}
	_ = f.SetOTFault(fp(150))
	if !conn.lastIs(0x4F, 0x60, 0x09) {
		t.Errorf("ot fault")
	}
	_ = f.SetOTWarn(fp(125))
	if !conn.lastIs(0x51, 0xD0, 0x07) {
		t.Errorf("ot warn")
	}
	_ = f.SetPinWarn(fp(1e9))
	if !conn.lastIs(0xD4, 0xFE, 0x0F) {
		t.Errorf("clamp")
	}
	_ = f.SetIinWarn(fp(1.02))
	y := uint16(math.Round((13661.0*1.02 - 5200.0) / 100.0))
	if !conn.lastIs(0xD3, byte(y), byte(y>>8)) {
		t.Errorf("iin warn")
	}
	conn.word(0x57, 0x0FFF)
	conn.word(0x58, 0)
	conn.word(0x43, 0)
	conn.word(0x4F, 0x0960)
	conn.word(0x51, 0x07D0)
	conn.word(0xD3, 0x0FFF)
	conn.word(0xD4, 0x0FFF)
	l, err := f.GetLimits()
	if err != nil || l.VinOVWarn != nil || l.VinUVWarn != nil || l.VoutUVWarn != nil || l.IinWarn != nil || l.PinWarn != nil {
		t.Errorf("disabled limits = %+v", l)
	}
	if l.OTFault == nil || l.OTWarn == nil || !lm25066Near(*l.OTFault, 150) || !lm25066Near(*l.OTWarn, 125) {
		t.Errorf("ot limits = %+v", l)
	}
}

func TestLM25066StatusAndControl(t *testing.T) {
	conn := newLM25066Mock()
	f := NewLM25066Full(conn, 1.0, false)
	conn.b(0x78, 0x49)
	conn.word(0x79, 0x3849)
	conn.b(0x7A, 0x20)
	conn.b(0x7C, 0x10)
	conn.b(0x7D, 0x80)
	conn.b(0x7E, 0x02)
	conn.b(0x80, 0x10)
	conn.word(0xE1, 0x0460)
	sb, _ := f.StatusByte()
	sw, _ := f.StatusWord()
	sv, _ := f.StatusVout()
	si, _ := f.StatusInput()
	st, _ := f.StatusTemperature()
	sc, _ := f.StatusCML()
	sm, _ := f.StatusMfr()
	dw, _ := f.DiagnosticWord()
	pi, _ := f.PollInterrupt()
	if sb != 0x49 || sw != 0x3849 || sv != 0x20 || si != 0x10 || st != 0x80 || sc != 2 || sm != 0x10 || dw != 0x0460 || pi != 0x0460 {
		t.Errorf("status values wrong")
	}
	_ = f.ClearFaults()
	if !conn.lastIs(0x03) {
		t.Errorf("clear faults")
	}
	_ = f.OperationOn()
	if !conn.lastIs(0x01, 0x80) {
		t.Errorf("operation on")
	}
	_ = f.OperationOff()
	if !conn.lastIs(0x01, 0x00) {
		t.Errorf("operation off")
	}
	conn.b(0x01, 0x80)
	conn.b(0x78, 0x00)
	if on, _ := f.IsOn(); !on {
		t.Errorf("is on")
	}
	conn.b(0x78, 0x40)
	if on, _ := f.IsOn(); on {
		t.Errorf("is on with OFF bit")
	}
}

func TestLM25066DeviceSetupGateMaskAlerts(t *testing.T) {
	conn := newLM25066Mock()
	f := NewLM25066Full(conn, 1.0, false)
	retry, hi, lo := uint8(7), true, false
	if err := f.ConfigureDevice(&retry, &hi, &lo); err != nil || !conn.lastIs(0xD9, 0xE0|0x04|0x10|0x02) {
		t.Errorf("configure device")
	}
	conn.word(0xD1, 648)
	if v, _ := f.Current(); !lm25066Near(v, 67900.0/6854.0) {
		t.Errorf("coefficients not updated: %v", v)
	}
	bad := uint8(8)
	if err := f.ConfigureDevice(&bad, nil, nil); err == nil {
		t.Errorf("retry range not checked")
	}
	conn.b(0xD9, 0x16)
	if v, _ := f.DeviceSetup(); v != 0x16 {
		t.Errorf("device setup = %d", v)
	}
	if err := f.SetGateMask(0x04); err != nil || !conn.lastIs(0xD7, 0x04) {
		t.Errorf("gate mask")
	}
	if err := f.SetGateMask(0x01); err == nil {
		t.Errorf("gate mask validation")
	}
	if v, _ := f.GetGateMask(); v != 0x04 {
		t.Errorf("get gate mask = %d", v)
	}
	conn.b(0x19, 0xB0)
	if v, _ := f.Capability(); v != 0xB0 {
		t.Errorf("capability = %d", v)
	}
	conn.cmds[0x99] = []byte{3, 'N', 'S', 'C'}
	conn.cmds[0x9A] = append([]byte{8}, []byte("LM25066\x00")...)
	conn.cmds[0x9B] = []byte{2, 'A', 'A'}
	id, _ := f.ManufacturerID()
	model, _ := f.Model()
	rev, _ := f.Revision()
	if id != "NSC" || model != "LM25066" || rev != "AA" {
		t.Errorf("identity = %q %q %q", id, model, rev)
	}
	conn.word(0xD8, 0x0820)
	_ = f.EnableInterrupt(LM25066SourceVinUVFault)
	if !conn.lastIs(0xD8, 0x00, 0x08) {
		t.Errorf("enable interrupt")
	}
	_ = f.DisableInterrupt(LM25066SourceVinUVWarn)
	if !conn.lastIs(0xD8, 0x00, 0x28) {
		t.Errorf("disable interrupt")
	}
}
