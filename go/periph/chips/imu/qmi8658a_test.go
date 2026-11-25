package imu

import (
	"bytes"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// qmiMock models the QMI8658A CTRL9 handshake, the self-test Avail flag and
// the FIFO_DATA port.
type qmiMock struct {
	regs map[byte]byte
	log  [][]byte
	cmds []qmiCmd
	fifo []byte
}

type qmiCmd struct {
	cmd byte
	cal [8]byte
}

func newQmiMock() *qmiMock {
	m := &qmiMock{regs: map[byte]byte{}}
	m.regs[regQmiWhoAmI] = whoAmIQmi8658Value
	return m
}

func (m *qmiMock) set(reg byte, values ...byte) {
	for i, v := range values {
		m.regs[reg+byte(i)] = v
	}
}

func (m *qmiMock) wrote(want ...byte) bool {
	for _, w := range m.log {
		if bytes.Equal(w, want) {
			return true
		}
	}
	return false
}

// last returns the last value written to a register, -1 if never written.
func (m *qmiMock) last(reg byte) int {
	v := -1
	for _, w := range m.log {
		if len(w) == 2 && w[0] == reg {
			v = int(w[1])
		}
	}
	return v
}

func (m *qmiMock) Write(data []byte) error {
	m.log = append(m.log, append([]byte(nil), data...))
	for i := 1; i < len(data); i++ {
		m.regs[data[0]+byte(i-1)] = data[i]
	}
	if len(data) == 2 && (data[0] == 0x03 || data[0] == 0x04) { // self-test start / end
		if data[1]&0x80 != 0 {
			m.regs[0x2D] |= 0x01
		} else {
			m.regs[0x2D] &^= 0x01
		}
	}
	if len(data) == 2 && data[0] == 0x0A {
		if data[1] != 0 {
			c := qmiCmd{cmd: data[1]}
			for i := 0; i < 8; i++ {
				c.cal[i] = m.regs[byte(0x0B+i)]
			}
			m.cmds = append(m.cmds, c)
			m.regs[0x2D] |= 0x80
		} else {
			m.regs[0x2D] &^= 0x80
		}
	}
	return nil
}

func (m *qmiMock) Read(n int) ([]byte, error) { return make([]byte, n), nil }

func (m *qmiMock) WriteRead(data []byte, n int) ([]byte, error) {
	out := make([]byte, n)
	for i := 0; i < n; i++ {
		if data[0] == regQmiFifoData {
			if len(m.fifo) > 0 {
				out[i] = m.fifo[0]
				m.fifo = m.fifo[1:]
			}
		} else {
			out[i] = m.regs[data[0]+byte(i)]
		}
	}
	return out, nil
}

func (m *qmiMock) ReadReg(reg uint32, length int) ([]byte, error) {
	return m.WriteRead([]byte{byte(reg)}, length)
}

func (m *qmiMock) WriteReg(reg uint32, data []byte) error {
	return m.Write(append([]byte{byte(reg)}, data...))
}

func (m *qmiMock) Close() error                { return nil }
func (m *qmiMock) Enable()                     {}
func (m *qmiMock) Disable()                    {}
func (m *qmiMock) IsEnabled() bool             { return true }
func (m *qmiMock) IntPin() connection.InputPin { return nil }
func (m *qmiMock) EnPin() connection.OutputPin { return nil }

func newQMI(t *testing.T) (*qmiMock, *QMI8658AFull) {
	t.Helper()
	m := newQmiMock()
	s, err := NewQMI8658AFull(m)
	if err != nil {
		t.Fatalf("NewQMI8658AFull: %v", err)
	}
	return m, s
}

func le16s(vals ...int16) []byte {
	var out []byte
	for _, v := range vals {
		out = append(out, s16l(v)...)
	}
	return out
}

func TestQMI8658AInit(t *testing.T) {
	m, _ := newQMI(t)
	if !bytes.Equal(m.log[0], []byte{0x60, 0xB0}) {
		t.Fatalf("first write = %v, want reset", m.log[0])
	}
	for _, w := range [][]byte{{0x02, 0x40}, {0x09, 0x80}, {0x03, 0x05}, {0x04, 0x45}, {0x06, 0x77}} {
		if !m.wrote(w...) {
			t.Errorf("missing init write %v", w)
		}
	}
	if !bytes.Equal(m.log[len(m.log)-1], []byte{0x08, 0x03}) {
		t.Errorf("last init write = %v, want CTRL7=0x03", m.log[len(m.log)-1])
	}
}

func TestQMI8658AWhoAmIMismatch(t *testing.T) {
	m := newQmiMock()
	m.regs[regQmiWhoAmI] = 0x00
	if _, err := NewQMI8658AMinimal(m); err == nil {
		t.Fatal("expected WHO_AM_I error")
	}
}

func TestQMI8658AAccelGyro(t *testing.T) {
	m, s := newQMI(t)
	m.set(regQmiAxL, le16s(16384, -8192, 0)...)
	x, y, z, err := s.Accel()
	if err != nil || !near(float64(x), qmiG, 1e-3) || !near(float64(y), -qmiG/2, 1e-3) || !near(float64(z), 0, 1e-3) {
		t.Errorf("accel = %v %v %v %v", x, y, z, err)
	}
	m.set(regQmiGxL, le16s(128, -256, 64)...)
	gx, gy, gz, err := s.Gyro()
	if err != nil || !near(float64(gx), qmiDegRad, 1e-5) || !near(float64(gy), -2*qmiDegRad, 1e-5) || !near(float64(gz), 0.5*qmiDegRad, 1e-5) {
		t.Errorf("gyro = %v %v %v %v", gx, gy, gz, err)
	}
}

func TestQMI8658ARawReadAllTemperature(t *testing.T) {
	m, s := newQMI(t)
	m.set(regQmiTempL, le16s(0x1980, 2048, 0, -2048, 16, 32, -64)...)
	ax, ay, az, _ := s.AccelRaw()
	if ax != 2048 || ay != 0 || az != -2048 {
		t.Errorf("accel raw = %d %d %d", ax, ay, az)
	}
	gx, gy, gz, _ := s.GyroRaw()
	if gx != 16 || gy != 32 || gz != -64 {
		t.Errorf("gyro raw = %d %d %d", gx, gy, gz)
	}
	a, g, temp, err := s.ReadAll()
	if err != nil || !near(float64(temp), 25.5, 1e-3) || !near(float64(a[0]), 2048.0/16384*qmiG, 1e-3) ||
		!near(float64(g[2]), -64.0/128*qmiDegRad, 1e-5) {
		t.Errorf("ReadAll = %v %v %v %v", a, g, temp, err)
	}
	if tc, _ := s.Temperature(); !near(float64(tc), 25.5, 1e-3) {
		t.Errorf("temperature = %v", tc)
	}
}

func TestQMI8658ATimestampDataReady(t *testing.T) {
	m, s := newQMI(t)
	m.set(regQmiTimestamp, 1, 2, 3)
	if ts, _ := s.Timestamp(); ts != 0x030201 {
		t.Errorf("timestamp = %x", ts)
	}
	m.set(regQmiStatus0, 0x00)
	if r, _ := s.DataReady(); r {
		t.Error("data ready should be false")
	}
	m.set(regQmiStatus0, 0x02)
	if r, _ := s.DataReady(); !r {
		t.Error("data ready should be true")
	}
}

func TestQMI8658AConfigure(t *testing.T) {
	m, s := newQMI(t)
	if err := s.ConfigureAccel(2, 4); err != nil || m.last(regQmiCtrl2) != 0x24 {
		t.Errorf("configure accel: %v %x", err, m.last(regQmiCtrl2))
	}
	m.set(regQmiAxL, le16s(4096, 0, 0)...)
	if x, _, _, _ := s.Accel(); !near(float64(x), qmiG, 1e-3) {
		t.Errorf("accel after range = %v", x)
	}
	if err := s.ConfigureGyro(7, 3); err != nil || m.last(regQmiCtrl3) != 0x73 {
		t.Errorf("configure gyro: %v %x", err, m.last(regQmiCtrl3))
	}
	m.set(regQmiGxL, le16s(16, 0, 0)...)
	if x, _, _, _ := s.Gyro(); !near(float64(x), qmiDegRad, 1e-5) {
		t.Errorf("gyro after range = %v", x)
	}
	if err := s.ConfigureAccel(4, 5); err == nil {
		t.Error("expected range error")
	}
	if err := s.ConfigureFilters(true, 3, false, 1); err != nil || m.last(regQmiCtrl5) != 0x27 {
		t.Errorf("filters: %v %x", err, m.last(regQmiCtrl5))
	}
}

func TestQMI8658ASensorsPower(t *testing.T) {
	m, s := newQMI(t)
	_ = s.SetSensors(true, false, false)
	if m.last(regQmiCtrl7) != 0x01 {
		t.Errorf("accel only: %x", m.last(regQmiCtrl7))
	}
	_ = s.SetSensors(true, true, true)
	if m.last(regQmiCtrl7) != 0x13 {
		t.Errorf("snooze: %x", m.last(regQmiCtrl7))
	}
	_ = s.SetSensors(true, true, false)
	_ = s.SetPowerDown(true)
	if m.last(regQmiCtrl1) != 0x41 || m.last(regQmiCtrl7) != 0x00 {
		t.Errorf("power down: ctrl1=%x ctrl7=%x", m.last(regQmiCtrl1), m.last(regQmiCtrl7))
	}
	_ = s.SetPowerDown(false)
	if m.last(regQmiCtrl1) != 0x40 {
		t.Errorf("wake: %x", m.last(regQmiCtrl1))
	}
}

func TestQMI8658ASyncSample(t *testing.T) {
	m, s := newQMI(t)
	if err := s.SetSyncSample(true); err != nil {
		t.Fatal(err)
	}
	c := m.cmds[len(m.cmds)-1]
	if c.cmd != 0x12 || c.cal[0] != 1 || !m.wrote(0x08, 0x83) {
		t.Errorf("sync on: %v", c)
	}
	m.regs[0x2D] = 0x03
	m.set(regQmiAxL, le16s(16384, 0, 0)...)
	if x, _, _, err := s.Accel(); err != nil || !near(float64(x), qmiG, 1e-3) {
		t.Errorf("sync accel = %v %v", x, err)
	}
	_ = s.SetSyncSample(false)
	c = m.cmds[len(m.cmds)-1]
	if m.last(regQmiCtrl7) != 0x03 || c.cmd != 0x12 || c.cal[0] != 0 {
		t.Errorf("sync off: ctrl7=%x %v", m.last(regQmiCtrl7), c)
	}
}

func TestQMI8658AFifo(t *testing.T) {
	m, s := newQMI(t)
	_ = s.ConfigureFifo(2, 1, 16)
	if !m.wrote(0x14, 0x06) || !m.wrote(0x13, 0x10) {
		t.Error("fifo config writes missing")
	}
	m.set(regQmiFifoStatus, 0x00)
	m.set(regQmiFifoSmplCnt, 12)
	if n, _ := s.FifoCount(); n != 24 {
		t.Errorf("fifo count = %d", n)
	}
	for i := 0; i < 24; i++ {
		m.fifo = append(m.fifo, byte(i))
	}
	m.cmds = nil
	data, err := s.ReadFifo(-1)
	if err != nil || len(data) != 24 || data[23] != 23 {
		t.Fatalf("read fifo = %v %v", data, err)
	}
	if len(m.cmds) != 1 || m.cmds[0].cmd != 0x05 || !m.wrote(0x02, 0x00) || m.last(regQmiCtrl1) != 0x40 || m.last(regQmiFifoCtrl) != 0x06 {
		t.Errorf("fifo procedure wrong: cmds=%v ctrl1=%x fifoctrl=%x", m.cmds, m.last(regQmiCtrl1), m.last(regQmiFifoCtrl))
	}
	m.fifo = nil
	for i := 0; i < 24; i++ {
		m.fifo = append(m.fifo, byte(i))
	}
	if data, _ := s.ReadFifo(10); len(data) != 10 || data[9] != 9 {
		t.Errorf("limited read = %v", data)
	}
	_ = s.ResetFifo()
	if m.cmds[len(m.cmds)-1].cmd != 0x04 {
		t.Error("reset fifo command missing")
	}
	_ = s.ConfigureFifo(0, 3, 0)
	if m.last(regQmiCtrl7) != 0x03 {
		t.Errorf("bypass drdy: %x", m.last(regQmiCtrl7))
	}
}

func TestQMI8658AMotion(t *testing.T) {
	m, s := newQMI(t)
	if err := s.ConfigureAnyMotion(0.25, 3, 0x07, false); err != nil {
		t.Fatal(err)
	}
	if len(m.cmds) != 2 || m.cmds[0].cmd != 0x0E || m.cmds[1].cmd != 0x0E {
		t.Fatalf("motion cmds = %v", m.cmds)
	}
	c1, c2 := m.cmds[0].cal, m.cmds[1].cal
	if c1[0] != 8 || c1[3] != 4 || c1[6] != 0xF7 || c1[7] != 0x10 {
		t.Errorf("set1 = %v", c1)
	}
	if c2[0] != 3 || c2[1] != 30 || c2[2] != 150 || c2[4] != byte(600&0xFF) || c2[5] != byte(600>>8) || c2[7] != 0x20 {
		t.Errorf("set2 = %v", c2)
	}
	if !m.wrote(0x08, 0x00) || m.last(regQmiCtrl7) != 0x03 {
		t.Error("sensors not turned off and restored")
	}
	_ = s.ConfigureSignificantMotion(100, 300)
	c2 = m.cmds[len(m.cmds)-1].cal
	if c2[2] != 100 || c2[4] != byte(300&0xFF) || c2[5] != 1 {
		t.Errorf("sig set2 = %v", c2)
	}
	_ = s.EnableMotion(true, true, false)
	if m.last(regQmiCtrl8) != 0x86 {
		t.Errorf("enable motion = %x", m.last(regQmiCtrl8))
	}
	_ = s.EnableMotion(false, false, false)
	if m.last(regQmiCtrl8) != 0x80 {
		t.Errorf("disable motion = %x", m.last(regQmiCtrl8))
	}
}

func TestQMI8658ATapPedometer(t *testing.T) {
	m, s := newQMI(t)
	_ = s.ConfigureTap(0, 20, 50, 250, 0.0625, 0.25, 0.8, 0.4)
	if len(m.cmds) != 2 || m.cmds[0].cmd != 0x0C {
		t.Fatalf("tap cmds = %v", m.cmds)
	}
	c1, c2 := m.cmds[0].cal, m.cmds[1].cal
	if c1[0] != 20 || c1[1] != 0 || c1[2] != 50 || c1[4] != 250 || c1[7] != 0x10 {
		t.Errorf("tap set1 = %v", c1)
	}
	if c2[0] != 8 || c2[1] != 32 || c2[2] != 0x33 || c2[3] != 0x03 || c2[4] != 0x9A || c2[5] != 0x01 || c2[7] != 0x20 {
		t.Errorf("tap set2 = %v", c2)
	}
	_ = s.EnableTap(true)
	if m.last(regQmiCtrl8) != 0x81 {
		t.Errorf("enable tap = %x", m.last(regQmiCtrl8))
	}
	m.set(regQmiTapStatus, 0xA2)
	if n, ax, neg, _ := s.TapStatus(); n != 2 || ax != 2 || !neg {
		t.Errorf("tap status = %d %d %v", n, ax, neg)
	}
	_ = s.EnableTap(false)

	m.cmds = nil
	_ = s.ConfigurePedometer(50, 200, 100, 200, 20, 10, 0, 4)
	if len(m.cmds) != 2 || m.cmds[0].cmd != 0x0D {
		t.Fatalf("ped cmds = %v", m.cmds)
	}
	p1, p2 := m.cmds[0].cal, m.cmds[1].cal
	if p1[0] != 50 || p1[2] != 205 || p1[4] != 102 || p1[7] != 0x10 {
		t.Errorf("ped set1 = %v", p1)
	}
	if p2[0] != 200 || p2[2] != 20 || p2[3] != 10 || p2[5] != 4 || p2[7] != 0x20 {
		t.Errorf("ped set2 = %v", p2)
	}
	_ = s.EnablePedometer(true)
	if m.last(regQmiCtrl8) != 0x90 {
		t.Errorf("enable pedometer = %x", m.last(regQmiCtrl8))
	}
	m.set(regQmiStepCnt, 0x34, 0x12, 0x00)
	if n, _ := s.StepCount(); n != 0x1234 {
		t.Errorf("step count = %x", n)
	}
	_ = s.ResetStepCount()
	if m.cmds[len(m.cmds)-1].cmd != 0x0F {
		t.Error("reset pedometer command missing")
	}
}

func TestQMI8658AWakeOnMotion(t *testing.T) {
	m, s := newQMI(t)
	if err := s.SetWakeOnMotion(100, 2, 1, 5); err != nil {
		t.Fatal(err)
	}
	c := m.cmds[len(m.cmds)-1]
	if !m.wrote(0x03, 0x0C) || c.cmd != 0x08 || c.cal[0] != 100 || c.cal[1] != 0xC5 {
		t.Errorf("wom cmd = %v", c)
	}
	if !m.wrote(0x08, 0x01) || m.last(regQmiCtrl1)&0x10 == 0 {
		t.Error("accelerometer / INT2 not enabled")
	}
	_ = s.SetWakeOnMotion(0, 1, 0, 0)
	c = m.cmds[len(m.cmds)-1]
	if c.cmd != 0x08 || c.cal[0] != 0 || m.last(regQmiCtrl1)&0x18 != 0 {
		t.Errorf("wom exit = %v ctrl1=%x", c, m.last(regQmiCtrl1))
	}
}

func TestQMI8658AOffsetsCalibrationSelfTest(t *testing.T) {
	m, s := newQMI(t)
	_ = s.SetAccelOffset(qmiG, 0, -qmiG)
	c := m.cmds[len(m.cmds)-1]
	if c.cmd != 0x09 || c.cal[0] != 0x00 || c.cal[1] != 0x10 || c.cal[4] != 0x00 || c.cal[5] != 0xF0 {
		t.Errorf("accel offset = %v", c)
	}
	_ = s.SetGyroOffset(qmiDegRad, 0, 0)
	c = m.cmds[len(m.cmds)-1]
	if c.cmd != 0x0A || c.cal[0] != 32 || c.cal[1] != 0 {
		t.Errorf("gyro offset = %v", c)
	}
	m.set(regQmiCodStatus, 0x00)
	if ok, st, err := s.CalibrateGyro(); err != nil || !ok || st != 0 || m.cmds[len(m.cmds)-1].cmd != 0xA2 {
		t.Errorf("calibrate = %v %v %v", ok, st, err)
	}
	m.set(regQmiCodStatus, 0x01)
	if ok, st, _ := s.CalibrateGyro(); ok || st != 1 {
		t.Errorf("calibrate fail = %v %v", ok, st)
	}
	m.set(regQmiDvxL, le16s(1000, 2000, 3000)...)
	if x, y, z, _ := s.GetGyroGains(); x != 1000 || y != 2000 || z != 3000 {
		t.Errorf("gains = %d %d %d", x, y, z)
	}
	_ = s.SetGyroGains(1000, 2000, 3000)
	c = m.cmds[len(m.cmds)-1]
	if c.cmd != 0xAA || c.cal[0] != 0xE8 || c.cal[1] != 0x03 || c.cal[4] != 0xB8 || c.cal[5] != 0x0B {
		t.Errorf("set gains = %v", c)
	}

	m.set(regQmiDvxL, le16s(2048, 2048, -2048)...)
	if ok, v, err := s.SelfTestAccel(); err != nil || !ok || !near(float64(v[0]), qmiG, 1e-3) {
		t.Errorf("accel self-test = %v %v %v", ok, v, err)
	}
	m.set(regQmiDvxL, le16s(16*400, 16*400, 16*400)...)
	if ok, v, err := s.SelfTestGyro(); err != nil || !ok || !near(float64(v[0]), 400*qmiDegRad, 1e-3) {
		t.Errorf("gyro self-test = %v %v %v", ok, v, err)
	}
	m.set(regQmiDvxL, le16s(16*100, 16*400, 16*400)...)
	if ok, _, _ := s.SelfTestGyro(); ok {
		t.Error("gyro self-test should fail")
	}
}

func TestQMI8658ADeviceInfoPullupsReset(t *testing.T) {
	m, s := newQMI(t)
	m.set(regQmiRevisionID, 0x7C)
	m.set(regQmiFwVersion, 1, 2, 0, 3)
	m.set(0x4B, 3)
	m.set(regQmiDvxL, 1, 2, 3, 4, 5, 6)
	rev, fw, usid, err := s.DeviceInfo()
	if err != nil || rev != 0x7C || fw != [3]byte{1, 2, 3} || usid != [6]byte{1, 2, 3, 4, 5, 6} || m.cmds[len(m.cmds)-1].cmd != 0x10 {
		t.Errorf("device info = %x %v %v %v", rev, fw, usid, err)
	}
	_ = s.SetPullups(false, true, true, false)
	c := m.cmds[len(m.cmds)-1]
	if c.cmd != 0x11 || c.cal[0] != 0x09 {
		t.Errorf("pullups = %v", c)
	}
	_ = s.Reset()
	if !m.wrote(0x60, 0xB0) {
		t.Error("reset write missing")
	}
}

func TestQMI8658AInterrupts(t *testing.T) {
	m, s := newQMI(t)
	_ = s.SetInterruptPins(1, 1)
	if m.last(regQmiCtrl8)&0x40 == 0 {
		t.Error("activity pin not routed to INT1")
	}
	_ = s.EnableInterrupt(QMI8658ASourceTap)
	if m.last(regQmiCtrl8)&0x01 == 0 || m.last(regQmiCtrl1)&0x08 == 0 {
		t.Error("tap interrupt not enabled on INT1")
	}
	_ = s.EnableInterrupt(QMI8658ASourceDataReady)
	if m.last(regQmiCtrl1)&0x10 == 0 {
		t.Error("data ready not enabled on INT2")
	}
	_ = s.DisableInterrupt(QMI8658ASourceDataReady)
	if m.last(regQmiCtrl1)&0x10 != 0 || m.last(regQmiCtrl7)&0x20 == 0 {
		t.Error("data ready not disabled")
	}
	_ = s.DisableInterrupt(QMI8658ASourceTap)
	if m.last(regQmiCtrl1)&0x08 != 0 {
		t.Error("INT1 should be released")
	}
	_ = s.SetInterruptPins(2, 2)
	m.set(regQmiStatus0, 0x01, 0xA2)
	m.set(regQmiFifoStatus, 0xC0)
	want := QMI8658ASourceDataReady | QMI8658ASourceFifoWatermark | QMI8658ASourceFifoFull |
		QMI8658ASourceAnyMotion | QMI8658ASourceSignificantMotion | QMI8658ASourceTap
	if got, err := s.PollInterrupt(); err != nil || got != want {
		t.Errorf("poll = %x want %x (%v)", got, want, err)
	}
}
