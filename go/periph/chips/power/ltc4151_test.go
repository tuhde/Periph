package power

import (
	"math"
	"testing"
)

func ltcNear(a, b, tol float32) bool { return float32(math.Abs(float64(a-b))) < tol }

// controlWrites returns the data byte of every write to CONTROL (0x06), ignoring register-pointer writes.
func controlWrites(c *mockConnection) []byte {
	var out []byte
	for _, w := range c.writes {
		if len(w) == 2 && w[0] == ltc4151RegControl {
			out = append(out, w[1])
		}
	}
	return out
}

func TestLTC4151FullAPI(t *testing.T) {
	conn := newMockConnection()
	s, err := NewLTC4151Full(conn, 0.02)
	if err != nil {
		t.Fatal(err)
	}
	if len(conn.writes) != 0 {
		t.Errorf("init: expected no writes, got %v", conn.writes)
	}

	conn.setRegister(0x02, 0x80, 0x00)
	if v, _ := s.Voltage(); !ltcNear(v, 51.2, 1e-3) {
		t.Errorf("Voltage = %v", v)
	}
	conn.setRegister(0x00, 0x40, 0x00)
	if v, _ := s.SenseVoltage(); !ltcNear(v, 0.02048, 1e-7) {
		t.Errorf("SenseVoltage = %v", v)
	}
	if v, _ := s.Current(); !ltcNear(v, 1.024, 1e-4) {
		t.Errorf("Current = %v", v)
	}
	if v, _ := s.Power(); !ltcNear(v, 51.2*1.024, 1e-2) {
		t.Errorf("Power = %v", v)
	}
	conn.setRegister(0x02, 0x00, 0xF8)
	if v, _ := s.Voltage(); !ltcNear(v, 15*0.025, 1e-4) {
		t.Errorf("Voltage low nibble = %v", v)
	}
	conn.setRegister(0x04, 0x7D, 0x00)
	if v, _ := s.AdinVoltage(); !ltcNear(v, 1.0, 1e-4) {
		t.Errorf("AdinVoltage = %v", v)
	}

	conn.setRegister(0x00, 0x40, 0x00, 0x80, 0x00, 0x7D, 0x00)
	r, _ := s.ReadAll()
	if !ltcNear(r.Vin, 51.2, 1e-3) || !ltcNear(r.SenseV, 0.02048, 1e-7) || !ltcNear(r.Current, 1.024, 1e-4) ||
		!ltcNear(r.Power, 51.2*1.024, 1e-2) || !ltcNear(r.Adin, 1.0, 1e-4) {
		t.Errorf("ReadAll = %+v", r)
	}

	for ch, want := range map[uint8]uint16{LTC4151Sense: 0x400, LTC4151Vin: 0x800, LTC4151Adin: 0x7D0} {
		if c, err := s.ReadRaw(ch); err != nil || c != want {
			t.Errorf("ReadRaw(%d) = %v, %v", ch, c, err)
		}
	}
	if _, err := s.ReadRaw(3); err != ErrLTC4151Channel {
		t.Errorf("ReadRaw(3) err = %v", err)
	}

	if err := s.SetSenseResistor(0.01); err != nil || !ltcNear(s.GetSenseResistor(), 0.01, 1e-9) {
		t.Errorf("SetSenseResistor")
	}
	if v, _ := s.Current(); !ltcNear(v, 2.048, 1e-3) {
		t.Errorf("Current at 10 mOhm = %v", v)
	}
	if err := s.SetSenseResistor(0); err != ErrLTC4151Resistor {
		t.Errorf("SetSenseResistor(0) err = %v", err)
	}
	_ = s.SetSenseResistor(0.02)

	conn.setRegister(0x06, 0x0C)
	if c, _ := s.ReadControl(); c != 0x0C {
		t.Errorf("ReadControl = %x", c)
	}
	conn.writes = nil
	_ = s.SetStuckBusTimer(false)
	if w := controlWrites(conn); len(w) != 1 || w[0] != 0x08 {
		t.Errorf("stuck bus off: %v", w)
	}
	conn.setRegister(0x06, 0x08)
	_ = s.SetStuckBusTimer(true)
	if w := controlWrites(conn); w[len(w)-1] != 0x0C {
		t.Errorf("stuck bus on: %v", w)
	}
	_ = s.SetPageRW(false)
	if w := controlWrites(conn); w[len(w)-1] != 0x04 {
		t.Errorf("page rw off: %v", w)
	}
	conn.setRegister(0x06, 0x04)
	_ = s.SetPageRW(true)
	if w := controlWrites(conn); w[len(w)-1] != 0x0C {
		t.Errorf("page rw on: %v", w)
	}
	_ = s.WriteControl(0xFF)
	if w := controlWrites(conn); w[len(w)-1] != 0xEF {
		t.Errorf("WriteControl masks test bit: %v", w)
	}
	_ = s.WriteControl(0x0C)

	conn.setRegister(0x06, 0xA8)
	if m, _ := s.IsSnapshotMode(); !m {
		t.Errorf("IsSnapshotMode want true")
	}
	_ = s.SetContinuous()
	if w := controlWrites(conn); w[len(w)-1] != 0x28 {
		t.Errorf("SetContinuous: %v", w)
	}
	conn.setRegister(0x06, 0x0C)

	conn.setRegister(0x01, 0x00)
	conn.setRegister(0x03, 0x08)
	if b, _ := s.IsBusy(LTC4151Sense); b {
		t.Errorf("IsBusy(SENSE) want false")
	}
	if b, _ := s.IsBusy(LTC4151Vin); !b {
		t.Errorf("IsBusy(VIN) want true")
	}
	conn.setRegister(0x03, 0x00)

	conn.writes = nil
	conn.setRegister(0x02, 0x80, 0x00)
	if v, err := s.Snapshot(LTC4151Vin); err != nil || !ltcNear(v, 51.2, 1e-3) {
		t.Errorf("Snapshot = %v, %v", v, err)
	}
	w := controlWrites(conn)
	if len(w) != 3 || w[0] != 0x0C || w[1] != 0xAC || w[2] != 0x0C {
		t.Errorf("Snapshot control writes: %v", w)
	}

	conn.setRegister(0x01, 0x08)
	if _, err := s.Snapshot(LTC4151Sense); err != ErrLTC4151Timeout {
		t.Errorf("Snapshot timeout err = %v", err)
	}
	conn.setRegister(0x01, 0x00)

	conn.setRegister(0x00, 0x40, 0x00, 0x80, 0x00, 0x7D, 0x00)
	_ = s.SetPageRW(false)
	if r, _ := s.ReadAll(); !ltcNear(r.Power, 51.2*1.024, 1e-2) {
		t.Errorf("ReadAll with page R/W off: %+v", r)
	}
	_ = s.SetPageRW(true)

	if v := LTC4151TemperatureFromAdin(900, 1200); !ltcNear(v, 58.82*(0.75-0.1066), 1e-3) {
		t.Errorf("TemperatureFromAdin = %v", v)
	}
}

func TestLTC4151Minimal(t *testing.T) {
	conn := newMockConnection()
	m, _ := NewLTC4151Minimal(conn, 0.005)
	conn.setRegister(0x00, 0x40, 0x00, 0x80, 0x00)
	if v, _ := m.Current(); !ltcNear(v, 4.096, 1e-3) {
		t.Errorf("Current = %v", v)
	}
	if v, _ := m.Power(); !ltcNear(v, 51.2*4.096, 5e-2) {
		t.Errorf("Power = %v", v)
	}
	conn.setRegister(0x02, 0x80, 0x00)
	if v, _ := m.Voltage(); !ltcNear(v, 51.2, 1e-3) {
		t.Errorf("Voltage = %v", v)
	}
}
