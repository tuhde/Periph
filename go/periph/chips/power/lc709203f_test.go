package power

import (
	"math"
	"testing"
)

// lc709203fMock holds one (low, high, crc) word per command code so that
// 3-byte writes do not overlap neighbouring commands.
type lc709203fMock struct {
	*mockConnection
	words map[byte][]byte
}

func newLC709203FMock() *lc709203fMock {
	return &lc709203fMock{mockConnection: newMockConnection(), words: map[byte][]byte{}}
}

func (m *lc709203fMock) put(cmd byte, value uint16) {
	lo, hi := byte(value&0xFF), byte(value>>8)
	m.words[cmd] = []byte{lo, hi, LC709203FCRC8([]byte{0x16, cmd, 0x17, lo, hi})}
}

func (m *lc709203fMock) WriteReg(reg uint32, data []byte) error {
	m.writes = append(m.writes, append([]byte{byte(reg)}, data...))
	if len(data) == 3 {
		m.words[byte(reg)] = append([]byte(nil), data...)
	}
	return nil
}

func (m *lc709203fMock) ReadReg(reg uint32, length int) ([]byte, error) {
	out := make([]byte, length)
	copy(out, m.words[byte(reg)])
	return out, nil
}

func (m *lc709203fMock) wrote(cmd, lo, hi byte) bool {
	for _, w := range m.writes {
		if len(w) == 4 && w[0] == cmd && w[1] == lo && w[2] == hi {
			return true
		}
	}
	return false
}

func (m *lc709203fMock) lastIs(cmd, lo, hi byte) bool {
	w := m.writes[len(m.writes)-1]
	return len(w) == 4 && w[0] == cmd && w[1] == lo && w[2] == hi && w[3] == LC709203FCRC8([]byte{0x16, cmd, lo, hi})
}

func TestLC709203FCRC(t *testing.T) {
	if got := LC709203FCRC8([]byte{0x16, 0x09, 0x17, 0xC2, 0x0E}); got != 0x86 {
		t.Errorf("read CRC = 0x%02X, want 0x86", got)
	}
	if got := LC709203FCRC8([]byte{0x16, 0x09, 0x55, 0xAA}); got != 0x3B {
		t.Errorf("write CRC = 0x%02X, want 0x3B", got)
	}
}

func TestLC709203FAPA(t *testing.T) {
	if LC709203FAPAFor(700) != 0x14 || LC709203FAPAFor(50) != 0x08 || LC709203FAPAFor(9000) != 0x36 || LC709203FAPAFor(1000) != 0x19 {
		t.Errorf("APA interpolation wrong")
	}
}

func TestLC709203FFullAPI(t *testing.T) {
	conn := newLC709203FMock()
	conn.put(0x13, 8)
	conn.put(0x14, 0)
	sensor, err := NewLC709203FFull(conn, 1000, 1)
	if err != nil {
		t.Fatalf("NewLC709203FFull: %v", err)
	}
	for _, c := range [][3]byte{{0x15, 1, 0}, {0x0B, 0x19, 0}, {0x12, 1, 0}, {0x16, 0, 0}, {0x08, 0xA6, 0x0B}} {
		if !conn.wrote(c[0], c[1], c[2]) {
			t.Errorf("init: missing write %v", c)
		}
	}

	conn.put(0x09, 3778)
	if v, err := sensor.Voltage(); err != nil || math.Abs(float64(v)-3.778) > 1e-4 {
		t.Errorf("Voltage() = %v, %v", v, err)
	}
	conn.put(0x0F, 755)
	if v, err := sensor.Percent(); err != nil || math.Abs(float64(v)-75.5) > 1e-4 {
		t.Errorf("Percent() = %v, %v", v, err)
	}
	conn.put(0x08, 0x0BA6)
	if v, err := sensor.Temperature(); err != nil || math.Abs(float64(v)-25.0) > 0.06 {
		t.Errorf("Temperature() = %v, %v", v, err)
	}

	conn.words[0x09] = []byte{0xC2, 0x0E, 0x00}
	if _, err := sensor.Voltage(); err != ErrLC709203FCRC {
		t.Errorf("CRC mismatch: err = %v, want ErrLC709203FCRC", err)
	}

	_ = sensor.SetTemperature(100)
	if !conn.lastIs(0x08, 0x04, 0x0D) {
		t.Errorf("SetTemperature clamp high: %v", conn.writes[len(conn.writes)-1])
	}
	_ = sensor.SetTemperature(-50)
	if !conn.lastIs(0x08, 0xE4, 0x09) {
		t.Errorf("SetTemperature clamp low: %v", conn.writes[len(conn.writes)-1])
	}

	conn.put(0x0D, 42)
	if v, err := sensor.RSOC(); err != nil || v != 42 {
		t.Errorf("RSOC() = %v, %v", v, err)
	}
	_ = sensor.SetRSOC(55)
	if !conn.lastIs(0x0D, 55, 0) {
		t.Errorf("SetRSOC")
	}
	_ = sensor.InitRSOC()
	if !conn.lastIs(0x07, 0x55, 0xAA) {
		t.Errorf("InitRSOC")
	}
	_ = sensor.BeforeRSOC()
	if !conn.lastIs(0x04, 0x55, 0xAA) {
		t.Errorf("BeforeRSOC")
	}

	_ = sensor.UseThermistor(3380)
	if !conn.wrote(0x06, 0x34, 0x0D) || !conn.lastIs(0x16, 1, 0) {
		t.Errorf("UseThermistor")
	}
	conn.put(0x16, 1)
	if v, err := sensor.IsThermistorMode(); err != nil || !v {
		t.Errorf("IsThermistorMode() = %v, %v", v, err)
	}
	_ = sensor.UseI2CTemperature(25)
	if !conn.lastIs(0x08, 0xA6, 0x0B) {
		t.Errorf("UseI2CTemperature")
	}

	_ = sensor.SetCurrentDirection(LC709203FDirectionDischarge)
	if !conn.lastIs(0x0A, 0xFF, 0xFF) {
		t.Errorf("SetCurrentDirection")
	}
	if err := sensor.SetCurrentDirection(5); err == nil {
		t.Errorf("SetCurrentDirection(5) should fail")
	}

	_ = sensor.SetAPA(0x2D)
	if !conn.lastIs(0x0B, 0x2D, 0) {
		t.Errorf("SetAPA")
	}
	_ = sensor.SetAPT(0x1234)
	if !conn.lastIs(0x0C, 0x34, 0x12) {
		t.Errorf("SetAPT")
	}
	_ = sensor.SetProfile(0)
	if !conn.wrote(0x12, 0, 0) || !conn.lastIs(0x15, 1, 0) {
		t.Errorf("SetProfile")
	}

	conn.put(0x1A, 0x0301)
	if v, _ := sensor.ProfileID(); v != 0x0301 {
		t.Errorf("ProfileID() = 0x%04X", v)
	}
	conn.put(0x11, 0x1234)
	if v, _ := sensor.ICVersion(); v != 0x1234 {
		t.Errorf("ICVersion() = 0x%04X", v)
	}

	_ = sensor.Sleep()
	if !conn.lastIs(0x15, 2, 0) {
		t.Errorf("Sleep")
	}
	conn.put(0x15, 2)
	if v, err := sensor.IsSleeping(); err != nil || !v {
		t.Errorf("IsSleeping() = %v, %v", v, err)
	}

	_ = sensor.SetAlarmRSOC(20)
	_ = sensor.SetAlarmVoltage(3.5)
	if !conn.lastIs(0x14, 0xAC, 0x0D) {
		t.Errorf("SetAlarmVoltage")
	}
	conn.put(0x0D, 10)
	conn.put(0x09, 3400)
	if s, err := sensor.PollInterrupt(); err != nil || s != LC709203FAlarmRSOC|LC709203FAlarmVoltage {
		t.Errorf("PollInterrupt() = %v, %v, want 3", s, err)
	}
	conn.put(0x0D, 50)
	conn.put(0x09, 3900)
	if s, err := sensor.PollInterrupt(); err != nil || s != 0 {
		t.Errorf("PollInterrupt() = %v, %v, want 0", s, err)
	}
}
