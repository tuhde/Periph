package imu

import (
	"bytes"
	"math"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// bankedMock models the ICM-20948 user banks (REG_BANK_SEL = 0x7F). Registers
// are keyed (bank << 8) | reg; every write is logged with the bank it landed in.
type bankedMock struct {
	bank uint8
	regs map[uint16]byte
	log  []bankedWrite
}

type bankedWrite struct {
	bank uint8
	data []byte
}

func newBankedMock() *bankedMock {
	m := &bankedMock{regs: map[uint16]byte{}}
	m.set(0, reg20948WhoAmI, whoAmI20948Value)
	m.set(0, reg20948I2cMstStatus, 0x40) // SLV4_DONE
	return m
}

func (m *bankedMock) set(bank uint8, reg byte, values ...byte) {
	for i, v := range values {
		m.regs[uint16(bank)<<8|uint16(reg)+uint16(i)] = v
	}
}

func (m *bankedMock) get(bank uint8, reg byte) byte { return m.regs[uint16(bank)<<8|uint16(reg)] }

func (m *bankedMock) writesIn(bank uint8) [][]byte {
	var out [][]byte
	for _, w := range m.log {
		if w.bank == bank && len(w.data) > 1 && w.data[0] != reg20948BankSel {
			out = append(out, w.data)
		}
	}
	return out
}

func (m *bankedMock) wrote(bank uint8, want ...byte) bool {
	for _, w := range m.writesIn(bank) {
		if bytes.Equal(w, want) {
			return true
		}
	}
	return false
}

func (m *bankedMock) lastIn(bank uint8) []byte {
	w := m.writesIn(bank)
	if len(w) == 0 {
		return nil
	}
	return w[len(w)-1]
}

func (m *bankedMock) Write(data []byte) error {
	cp := append([]byte(nil), data...)
	m.log = append(m.log, bankedWrite{m.bank, cp})
	if data[0] == reg20948BankSel {
		m.bank = data[1] >> 4
		return nil
	}
	for i := 1; i < len(data); i++ {
		m.regs[uint16(m.bank)<<8|uint16(data[0])+uint16(i-1)] = data[i]
	}
	return nil
}

func (m *bankedMock) Read(n int) ([]byte, error) { return make([]byte, n), nil }

func (m *bankedMock) WriteRead(data []byte, n int) ([]byte, error) {
	m.log = append(m.log, bankedWrite{m.bank, append([]byte(nil), data...)})
	out := make([]byte, n)
	for i := 0; i < n; i++ {
		out[i] = m.regs[uint16(m.bank)<<8|uint16(data[0])+uint16(i)]
	}
	return out, nil
}

func (m *bankedMock) ReadReg(reg uint32, length int) ([]byte, error) {
	return m.WriteRead([]byte{byte(reg)}, length)
}

func (m *bankedMock) WriteReg(reg uint32, data []byte) error {
	return m.Write(append([]byte{byte(reg)}, data...))
}

func (m *bankedMock) Close() error                { return nil }
func (m *bankedMock) Enable()                     {}
func (m *bankedMock) Disable()                    {}
func (m *bankedMock) IsEnabled() bool             { return true }
func (m *bankedMock) IntPin() connection.InputPin { return nil }
func (m *bankedMock) EnPin() connection.OutputPin { return nil }

func s16b(v int16) []byte { return []byte{byte(uint16(v) >> 8), byte(uint16(v))} }
func s16l(v int16) []byte { return []byte{byte(uint16(v)), byte(uint16(v) >> 8)} }
func cat(parts ...[]byte) []byte {
	var out []byte
	for _, p := range parts {
		out = append(out, p...)
	}
	return out
}

func near(a, b, tol float64) bool { return math.Abs(a-b) < tol }

func newICM(t *testing.T) (*bankedMock, *ICM20948Full) {
	t.Helper()
	m := newBankedMock()
	s, err := NewICM20948Full(m)
	if err != nil {
		t.Fatalf("NewICM20948Full: %v", err)
	}
	return m, s
}

func TestICM20948Init(t *testing.T) {
	m, _ := newICM(t)
	expected := [][]byte{
		{0x7F, 0x00}, {0x00}, {0x06, 0x80}, {0x06, 0x01}, {0x07, 0x00},
		{0x7F, 0x20}, {0x01, 0x19}, {0x7F, 0x00},
		{0x7F, 0x20}, {0x14, 0x19}, {0x7F, 0x00},
		{0x7F, 0x20}, {0x00, 0x0A}, {0x7F, 0x00},
		{0x7F, 0x20}, {0x10, 0x00}, {0x7F, 0x00},
		{0x7F, 0x20}, {0x11, 0x0A}, {0x7F, 0x00},
	}
	if len(m.log) != len(expected) {
		t.Fatalf("init wrote %d times, want %d", len(m.log), len(expected))
	}
	for i, w := range m.log {
		if !bytes.Equal(w.data, expected[i]) {
			t.Errorf("init write %d = %v, want %v", i, w.data, expected[i])
		}
	}
	if m.bank != 0 {
		t.Errorf("bank after init = %d, want 0", m.bank)
	}
}

func TestICM20948WhoAmIMismatch(t *testing.T) {
	m := newBankedMock()
	m.set(0, reg20948WhoAmI, 0x00)
	if _, err := NewICM20948Minimal(m); err == nil {
		t.Error("expected WHO_AM_I mismatch error")
	}
}

func TestICM20948AccelGyroConfig(t *testing.T) {
	m, s := newICM(t)
	m.set(0, reg20948AccelXoutH, cat(s16b(16384), s16b(-8192), s16b(4096))...)
	ax, ay, az, _ := s.Accel()
	if !near(float64(ax), 9.80665, 1e-3) || !near(float64(ay), -4.903325, 1e-3) || !near(float64(az), 2.4516625, 1e-3) {
		t.Errorf("Accel() = %v %v %v", ax, ay, az)
	}
	m.set(0, reg20948GyroXoutH, cat(s16b(131), s16b(-131), s16b(262))...)
	gx, gy, gz, _ := s.Gyro()
	d := math.Pi / 180
	if !near(float64(gx), d, 1e-4) || !near(float64(gy), -d, 1e-4) || !near(float64(gz), 2*d, 1e-4) {
		t.Errorf("Gyro() = %v %v %v", gx, gy, gz)
	}

	_ = s.ConfigureGyro(2)
	if !bytes.Equal(m.lastIn(2), []byte{0x01, 0x1D}) {
		t.Errorf("ConfigureGyro write = %v", m.lastIn(2))
	}
	m.set(0, reg20948GyroXoutH, cat(s16b(328), s16b(0), s16b(0))...)
	gx, _, _, _ = s.Gyro()
	if !near(float64(gx), 10*d, 1e-3) {
		t.Errorf("sensitivity after ConfigureGyro(2): %v", gx)
	}
	_ = s.ConfigureAccel(1)
	if !bytes.Equal(m.lastIn(2), []byte{0x14, 0x1B}) {
		t.Errorf("ConfigureAccel write = %v", m.lastIn(2))
	}
	m.set(0, reg20948AccelXoutH, cat(s16b(8192), s16b(0), s16b(0))...)
	ax, _, _, _ = s.Accel()
	if !near(float64(ax), 9.80665, 1e-3) {
		t.Errorf("sensitivity after ConfigureAccel(1): %v", ax)
	}

	_ = s.ConfigureFilters(5, 2, false, true)
	w := m.writesIn(2)
	if !bytes.Equal(w[len(w)-2], []byte{0x01, 5<<3 | 2<<1}) || !bytes.Equal(w[len(w)-1], []byte{0x14, 2<<3 | 1<<1 | 1}) {
		t.Errorf("ConfigureFilters writes = %v", w[len(w)-2:])
	}
	_ = s.ConfigureFilters(3, 3, true, true)
	_ = s.ConfigureSampleRate(9, 0x123)
	w = m.writesIn(2)
	if !bytes.Equal(w[len(w)-3], []byte{0x00, 9}) || !bytes.Equal(w[len(w)-2], []byte{0x10, 0x01}) || !bytes.Equal(w[len(w)-1], []byte{0x11, 0x23}) {
		t.Errorf("ConfigureSampleRate writes = %v", w[len(w)-3:])
	}
	if m.bank != 0 {
		t.Errorf("bank = %d, want 0", m.bank)
	}
}

func TestICM20948ReadAllRawTemperature(t *testing.T) {
	m, s := newICM(t)
	_ = s.ConfigureAccel(1)
	_ = s.ConfigureGyro(2)
	m.set(0, reg20948AccelXoutH, cat(s16b(8192), s16b(0), s16b(0), s16b(262), s16b(0), s16b(0), s16b(340))...)
	a, g, temp, err := s.ReadAll()
	if err != nil || !near(float64(a[0]), 9.80665, 1e-3) || !near(float64(g[0]), 262/32.8*math.Pi/180, 1e-4) ||
		!near(float64(temp), 340/333.87+21.0, 1e-3) {
		t.Errorf("ReadAll() = %v %v %v %v", a, g, temp, err)
	}
	m.set(0, reg20948TempOutH, s16b(340)...)
	if v, _ := s.Temperature(); !near(float64(v), 340/333.87+21.0, 1e-3) {
		t.Errorf("Temperature() = %v", v)
	}
	m.set(0, reg20948AccelXoutH, cat(s16b(100), s16b(-200), s16b(300))...)
	if x, y, z, _ := s.AccelRaw(); x != 100 || y != -200 || z != 300 {
		t.Errorf("AccelRaw() = %d %d %d", x, y, z)
	}
	m.set(0, reg20948GyroXoutH, cat(s16b(-50), s16b(60), s16b(-70))...)
	if x, y, z, _ := s.GyroRaw(); x != -50 || y != 60 || z != -70 {
		t.Errorf("GyroRaw() = %d %d %d", x, y, z)
	}
	m.set(0, reg20948IntStatus1, 0x01)
	if r, _ := s.DataReady(); !r {
		t.Error("DataReady() = false, want true")
	}
	m.set(0, reg20948IntStatus1, 0x00)
	if r, _ := s.DataReady(); r {
		t.Error("DataReady() = true, want false")
	}
}

func TestICM20948PowerAndOffsets(t *testing.T) {
	m, s := newICM(t)
	for _, c := range []struct {
		a, g bool
		want byte
	}{{false, true, 0x38}, {true, false, 0x07}, {true, true, 0x00}} {
		_ = s.SetSensors(c.a, c.g)
		if !bytes.Equal(m.lastIn(0), []byte{0x07, c.want}) {
			t.Errorf("SetSensors(%v,%v) = %v", c.a, c.g, m.lastIn(0))
		}
	}
	_ = s.SetSleep(true)
	if !bytes.Equal(m.lastIn(0), []byte{0x06, 0x41}) {
		t.Errorf("SetSleep(true) = %v", m.lastIn(0))
	}
	_ = s.SetSleep(false)
	if !bytes.Equal(m.lastIn(0), []byte{0x06, 0x01}) {
		t.Errorf("SetSleep(false) = %v", m.lastIn(0))
	}
	d := float32(math.Pi / 180)
	_ = s.SetGyroOffset(d, 0, -d)
	if !bytes.Equal(m.lastIn(2), []byte{0x03, 0x00, 0x21, 0x00, 0x00, 0xFF, 0xDF}) {
		t.Errorf("SetGyroOffset = %v", m.lastIn(2))
	}
	_ = s.SetAccelOffset(0x1234, -1, 0)
	w := m.writesIn(1)
	if !bytes.Equal(w[len(w)-3], []byte{0x14, 0x24, 0x68}) || !bytes.Equal(w[len(w)-2], []byte{0x17, 0xFF, 0xFE}) {
		t.Errorf("SetAccelOffset = %v", w[len(w)-3:])
	}
	m.set(1, reg20948XaOffsH, 0x24, 0x68, 0, 0xFF, 0xFE, 0, 0x00, 0x02)
	if x, y, z, _ := s.GetAccelOffset(); x != 0x1234 || y != -1 || z != 1 {
		t.Errorf("GetAccelOffset = %d %d %d", x, y, z)
	}
	m.set(1, reg20948TimebaseCorrection, 0xF6)
	if v, _ := s.ReadTimebaseCorrection(); !near(float64(v), -10*0.079, 1e-4) {
		t.Errorf("ReadTimebaseCorrection = %v", v)
	}
	if m.bank != 0 {
		t.Errorf("bank = %d, want 0", m.bank)
	}
}

func TestICM20948Fifo(t *testing.T) {
	m, s := newICM(t)
	m.set(0, reg20948FifoCountH, 0x03, 0x45)
	if n, _ := s.FifoCount(); n != 0x345 {
		t.Errorf("FifoCount = %#x", n)
	}
	m.set(0, reg20948FifoCountH, 0x00, 0x02)
	m.set(0, reg20948FifoRW, 0xAA, 0xBB)
	if b, _ := s.ReadFifo(-1); !bytes.Equal(b, []byte{0xAA, 0xBB}) {
		t.Errorf("ReadFifo(-1) = %v", b)
	}
	if b, _ := s.ReadFifo(1); !bytes.Equal(b, []byte{0xAA}) {
		t.Errorf("ReadFifo(1) = %v", b)
	}
	m.set(0, reg20948FifoCountH, 0x00, 0x00)
	if b, _ := s.ReadFifo(-1); len(b) != 0 {
		t.Errorf("ReadFifo empty = %v", b)
	}
	_ = s.EnableFifo(true, true, false)
	if !m.wrote(0, 0x67, 0x1E) || !m.wrote(0, 0x03, 0x40) {
		t.Error("EnableFifo did not write FIFO_EN_2 / USER_CTRL")
	}
	w := m.writesIn(0)
	if !bytes.Equal(w[len(w)-2], []byte{0x68, 0x1F}) || !bytes.Equal(w[len(w)-1], []byte{0x68, 0x00}) {
		t.Errorf("EnableFifo reset = %v", w[len(w)-2:])
	}
	_ = s.SetFifoMode(true)
	if !bytes.Equal(m.lastIn(0), []byte{0x69, 0x1F}) {
		t.Errorf("SetFifoMode(true) = %v", m.lastIn(0))
	}
	_ = s.SetFifoMode(false)
	if !bytes.Equal(m.lastIn(0), []byte{0x69, 0x00}) {
		t.Errorf("SetFifoMode(false) = %v", m.lastIn(0))
	}
}

func TestICM20948Interrupts(t *testing.T) {
	m, s := newICM(t)
	_ = s.SetInterruptPin(true, false, true, true)
	if !bytes.Equal(m.lastIn(0), []byte{0x0F, 0xB0}) {
		t.Errorf("SetInterruptPin = %v", m.lastIn(0))
	}
	steps := []struct {
		fn   func(uint8) error
		src  uint8
		want []byte
	}{
		{s.EnableInterrupt, ICM20948SourceDataReady, []byte{0x11, 0x01}},
		{s.EnableInterrupt, ICM20948SourcePLLReady, []byte{0x10, 0x04}},
		{s.DisableInterrupt, ICM20948SourcePLLReady, []byte{0x10, 0x00}},
		{s.EnableInterrupt, ICM20948SourceFifoOverflow, []byte{0x12, 0x1F}},
	}
	for _, st := range steps {
		_ = st.fn(st.src)
		if !bytes.Equal(m.lastIn(0), st.want) {
			t.Errorf("interrupt source %#x write = %v, want %v", st.src, m.lastIn(0), st.want)
		}
	}
	if err := s.EnableInterrupt(0x80); err == nil {
		t.Error("EnableInterrupt(0x80): expected error")
	}
	m.set(0, reg20948IntStatus, 0x09, 0x01, 0x02)
	if v, _ := s.PollInterrupt(); v != 0x01|0x02|0x04|0x10 {
		t.Errorf("PollInterrupt = %#x", v)
	}
	m.set(0, reg20948IntStatus, 0x04, 0x00, 0x00)
	if v, _ := s.PollInterrupt(); v != 0x08 {
		t.Errorf("PollInterrupt pll = %#x", v)
	}
	_ = s.SetWakeOnMotion(200, true)
	if !m.wrote(2, 0x13, 50) || !m.wrote(2, 0x12, 0x03) || !bytes.Equal(m.lastIn(0), []byte{0x10, 0x08}) {
		t.Error("SetWakeOnMotion register writes wrong")
	}
}

func TestICM20948LowPowerGuard(t *testing.T) {
	m, s := newICM(t)
	m.set(0, reg20948PwrMgmt1, 0x01)
	_ = s.SetLowPower(true, true, 2, 3)
	if !m.wrote(0, 0x05, 0x70) || !bytes.Equal(m.lastIn(0), []byte{0x06, 0x21}) {
		t.Errorf("SetLowPower writes wrong, last = %v", m.lastIn(0))
	}
	if !m.wrote(2, 0x15, 0x02) || !m.wrote(2, 0x02, 0x03) {
		t.Error("SetLowPower averaging writes missing")
	}
	m.log = nil
	_ = s.ConfigureGyro(0)
	w := m.writesIn(0)
	if !bytes.Equal(w[0], []byte{0x06, 0x01}) || !bytes.Equal(w[len(w)-1], []byte{0x06, 0x21}) {
		t.Errorf("LP guard writes = %v", w)
	}
	_ = s.SetLowPower(false, false, 0, 0)
	if m.get(0, reg20948PwrMgmt1)&0x20 != 0 {
		t.Error("LP_EN still set after SetLowPower(false, false)")
	}
}

func TestICM20948Magnetometer(t *testing.T) {
	m, s := newICM(t)
	if _, _, _, err := s.Mag(); err == nil {
		t.Error("Mag() before EnableMag: expected error")
	}
	if _, _, _, err := s.MagRaw(); err == nil {
		t.Error("MagRaw() before EnableMag: expected error")
	}
	if _, err := s.MagDataReady(); err == nil {
		t.Error("MagDataReady() before EnableMag: expected error")
	}
	m.set(3, reg20948I2cSlv4Di, 0x09) // WIA2
	if err := s.EnableMag(); err != nil {
		t.Fatalf("EnableMag: %v", err)
	}
	if m.bank != 0 || !m.wrote(3, 0x01, 0x07) || !m.wrote(3, 0x16, 0x01) || !m.wrote(3, 0x14, 0x32) ||
		!m.wrote(3, 0x16, 0x08) || !m.wrote(3, 0x14, 0x31) {
		t.Error("EnableMag register sequence wrong")
	}
	w3 := m.writesIn(3)
	if !bytes.Equal(w3[len(w3)-3], []byte{0x03, 0x8C}) || !bytes.Equal(w3[len(w3)-2], []byte{0x04, 0x10}) ||
		!bytes.Equal(w3[len(w3)-1], []byte{0x05, 0x89}) {
		t.Errorf("slave 0 setup = %v", w3[len(w3)-3:])
	}
	if m.get(0, reg20948UserCtrl)&0x20 == 0 {
		t.Error("I2C_MST_EN not set")
	}
	m.set(0, reg20948ExtSlvSensData, cat([]byte{0x01}, s16l(1000), s16l(-500), s16l(250), []byte{0x00, 0x00})...)
	x, y, z, err := s.Mag()
	if err != nil || !near(float64(x), 150, 1e-3) || !near(float64(y), -75, 1e-3) || !near(float64(z), 37.5, 1e-3) {
		t.Errorf("Mag() = %v %v %v %v", x, y, z, err)
	}
	if rx, ry, rz, _ := s.MagRaw(); rx != 1000 || ry != -500 || rz != 250 {
		t.Errorf("MagRaw() = %d %d %d", rx, ry, rz)
	}
	if r, _ := s.MagDataReady(); !r {
		t.Error("MagDataReady() = false")
	}
	m.set(0, reg20948ExtSlvSensData, cat([]byte{0x01}, s16l(1), s16l(2), s16l(3), []byte{0x00, 0x08})...)
	if _, _, _, err := s.Mag(); err == nil {
		t.Error("Mag() with HOFL: expected error")
	}
	if rx, _, _, _ := s.MagRaw(); rx != 1 {
		t.Errorf("MagRaw() with HOFL = %d, want 1", rx)
	}
	_ = s.MagSetMode(2)
	if !m.wrote(3, 0x16, 0x02) {
		t.Error("MagSetMode(2) did not write CNTL2")
	}
	m.set(3, reg20948I2cSlv4Di, 0x5A)
	if v, err := s.AuxReadByte(0x1E, 0x0A); err != nil || v != 0x5A || !m.wrote(3, 0x13, 0x9E) {
		t.Errorf("AuxReadByte = %#x, %v", v, err)
	}
	if err := s.AuxWriteByte(0x1E, 0x0B, 0xA5); err != nil || !m.wrote(3, 0x13, 0x1E) || !m.wrote(3, 0x16, 0xA5) {
		t.Errorf("AuxWriteByte = %v", err)
	}
	if m.bank != 0 {
		t.Errorf("bank = %d, want 0", m.bank)
	}
}

func TestICM20948MagnetometerErrors(t *testing.T) {
	_, s := newICM(t) // WIA2 reads 0 -> mismatch
	if err := s.EnableMag(); err == nil {
		t.Error("EnableMag with WIA2 mismatch: expected error")
	}
	m, s := newICM(t)
	m.set(0, reg20948I2cMstStatus, 0x10)
	if _, err := s.AuxReadByte(0x0C, 0x01); err == nil {
		t.Error("AuxReadByte with NACK: expected error")
	}
}

func TestICM20948SelfTestAndReset(t *testing.T) {
	m, s := newICM(t)
	a, g, err := s.SelfTest()
	if err != nil || a != [3]float32{} || g != [3]float32{} {
		t.Errorf("SelfTest() = %v %v %v", a, g, err)
	}
	if m.bank != 0 {
		t.Errorf("bank = %d, want 0", m.bank)
	}
	_ = s.Reset()
	if !bytes.Equal(m.lastIn(0), []byte{0x06, 0x80}) {
		t.Errorf("Reset write = %v", m.lastIn(0))
	}
}
