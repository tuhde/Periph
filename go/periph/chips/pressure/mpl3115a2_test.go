package pressure

import (
	"math"
	"testing"
)

// mplMock is mockConnection (see bmp180_test.go) with RST (0x04) and OST (0x02)
// self-clearing in CTRL_REG1, like the real chip.
type mplMock struct{ *mockConnection }

func (m *mplMock) Write(data []byte) error {
	if err := m.mockConnection.Write(data); err != nil {
		return err
	}
	if len(data) == 2 && data[0] == mpl3115a2RegCtrlReg1 {
		m.registers[mpl3115a2RegCtrlReg1] = data[1] &^ 0x06
	}
	return nil
}

func (m *mplMock) WriteReg(reg uint32, data []byte) error {
	return m.Write(append([]byte{byte(reg)}, data...))
}

func newMplMock() *mplMock {
	c := &mplMock{newMockConnection()}
	c.setRegister(mpl3115a2RegWhoAmI, 0xC4)
	c.setRegister(mpl3115a2RegDRStatus, 0x0E)
	c.setRegister(mpl3115a2RegOutPMSB, 0x62, 0xF3, 0x80, 0x19, 0x80) // 101326.0 Pa, 25.5 °C
	return c
}

func mplLast(c *mplMock, reg byte, idx int) int {
	v := -1
	for _, w := range c.writes {
		if len(w) > idx && w[0] == reg {
			v = int(w[idx])
		}
	}
	return v
}

func near(a, b float32) bool { return math.Abs(float64(a-b)) < 1e-3 }

func TestMPL3115A2FullAPI(t *testing.T) {
	c := newMplMock()
	s := NewMPL3115A2Full(c)
	if len(c.writes) != 0 {
		t.Fatalf("constructor touched the bus")
	}
	if err := s.Init(); err != nil {
		t.Fatalf("Init: %v", err)
	}
	if mplLast(c, mpl3115a2RegPTDataCfg, 1) != 0x07 {
		t.Errorf("PT_DATA_CFG not 0x07")
	}
	if mplLast(c, mpl3115a2RegCtrlReg1, 1) != 0x38 {
		t.Errorf("CTRL_REG1 after Init = 0x%02X, want 0x38", mplLast(c, mpl3115a2RegCtrlReg1, 1))
	}

	p, temp, err := s.Read()
	if err != nil || !near(p, 101326.0) || !near(temp, 25.5) {
		t.Errorf("Read() = %v, %v, %v", p, temp, err)
	}
	if mplLast(c, mpl3115a2RegCtrlReg1, 1) != 0x3A {
		t.Errorf("Read did not set OST")
	}

	c.setRegister(mpl3115a2RegOutPMSB+3, 0xE7, 0x00)
	if v, err := s.ReadTemperature(); err != nil || !near(v, -25.0) {
		t.Errorf("ReadTemperature() = %v, %v", v, err)
	}
	c.setRegister(mpl3115a2RegOutPMSB+3, 0x19, 0x80)

	c.setRegister(mpl3115a2RegOutPMSB, 0x00, 0x7B, 0x30)
	if v, err := s.ReadAltitude(); err != nil || !near(v, 123.1875) {
		t.Errorf("ReadAltitude() = %v, %v", v, err)
	}
	if mplLast(c, mpl3115a2RegCtrlReg1, 1) != 0xBA {
		t.Errorf("altimeter read did not set ALT")
	}
	c.setRegister(mpl3115a2RegOutPMSB, 0xFF, 0x9C, 0x00)
	if v, err := s.ReadAltitude(); err != nil || !near(v, -100.0) {
		t.Errorf("negative altitude = %v, %v", v, err)
	}
	c.setRegister(mpl3115a2RegOutPMSB, 0x62, 0xF3, 0x80)
	if v, err := s.ReadPressure(); err != nil || !near(v, 101326.0) {
		t.Errorf("ReadPressure after altitude = %v, %v", v, err)
	}

	if err := s.SetOversample(16); err != nil || s.GetOversample() != 16 || mplLast(c, mpl3115a2RegCtrlReg1, 1) != 0x20 {
		t.Errorf("SetOversample(16) failed")
	}
	if err := s.SetOversample(3); err == nil {
		t.Errorf("SetOversample(3) should fail")
	}
	_ = s.SetOversample(128)

	if err := s.SetSeaLevelPressure(101326); err != nil || mplLast(c, mpl3115a2RegBarInMSB, 1) != 0xC5 || mplLast(c, mpl3115a2RegBarInMSB, 2) != 0xE7 {
		t.Errorf("SetSeaLevelPressure wrote wrong BAR_IN")
	}
	if err := s.SetOffsets(-8, 1, -3); err != nil || mplLast(c, mpl3115a2RegOffP, 1) != 0xFE || mplLast(c, mpl3115a2RegOffT, 1) != 16 || mplLast(c, mpl3115a2RegOffH, 1) != 0xFD {
		t.Errorf("SetOffsets wrote wrong values")
	}
	if err := s.SetOffsets(1000, 0, 0); err == nil {
		t.Errorf("SetOffsets(1000) should fail")
	}
	if err := s.SetTarget(100000, 200, -5, 3); err != nil ||
		mplLast(c, mpl3115a2RegPTgtMSB, 1) != 0xC3 || mplLast(c, mpl3115a2RegPTgtMSB, 2) != 0x50 ||
		mplLast(c, mpl3115a2RegTTgt, 1) != 0xFB || mplLast(c, mpl3115a2RegPWndMSB, 2) != 0x64 || mplLast(c, mpl3115a2RegTWnd, 1) != 3 {
		t.Errorf("SetTarget wrote wrong values")
	}

	if err := s.StartContinuous(2); err != nil || mplLast(c, mpl3115a2RegCtrlReg2, 1)&0x0F != 2 || mplLast(c, mpl3115a2RegCtrlReg1, 1) != 0x39 {
		t.Errorf("StartContinuous failed")
	}
	if err := s.StartContinuous(16); err == nil {
		t.Errorf("StartContinuous(16) should fail")
	}
	if ok, err := s.DataReady(); err != nil || !ok {
		t.Errorf("DataReady() = %v, %v", ok, err)
	}
	if v, tc, err := s.ReadContinuous(); err != nil || !near(v, 101326.0) || !near(tc, 25.5) {
		t.Errorf("ReadContinuous() = %v, %v, %v", v, tc, err)
	}
	_ = s.StopContinuous()
	if mplLast(c, mpl3115a2RegCtrlReg1, 1) != 0x38 {
		t.Errorf("StopContinuous failed")
	}

	c.setRegister(mpl3115a2RegOutPDelta, 0x00, 0x00, 0x40, 0x00, 0x10)
	if dv, dt, err := s.ReadDelta(); err != nil || !near(dv, 1.0) || !near(dt, 0.0625) {
		t.Errorf("ReadDelta() = %v, %v, %v", dv, dt, err)
	}

	c.setRegister(mpl3115a2RegPMin, 0x62, 0xF3, 0x80, 0x19, 0x80)
	if mm, err := s.ReadMinMax(); err != nil || !near(mm.PMin, 101326.0) {
		t.Errorf("ReadMinMax() = %+v, %v", mm, err)
	}
	if err := s.ClearMinMax(); err != nil || mplLast(c, mpl3115a2RegPMin, 3) != 0 {
		t.Errorf("ClearMinMax failed")
	}

	if err := s.FIFOSetup(MPL3115A2FIFOCircular, 16); err != nil || mplLast(c, mpl3115a2RegFSetup, 1) != 0x50 {
		t.Errorf("FIFOSetup failed")
	}
	if err := s.FIFOSetup(MPL3115A2FIFOStop, 0); err == nil {
		t.Errorf("FIFO 1->2 switch should fail")
	}
	c.setRegister(mpl3115a2RegFStatus, 0xC2)
	if ov, wm, n, err := s.FIFOStatus(); err != nil || !ov || !wm || n != 2 {
		t.Errorf("FIFOStatus() = %v %v %v %v", ov, wm, n, err)
	}
	c.setRegister(mpl3115a2RegFData, 0x62, 0xF3, 0x80, 0x19, 0x80)
	if smp, err := s.FIFORead(); err != nil || len(smp) != 2 || !near(smp[0].Value, 101326.0) {
		t.Errorf("FIFORead() = %v, %v", smp, err)
	}
	_ = s.FIFOSetup(MPL3115A2FIFOOff, 0)

	if err := s.SetInterruptPins(true, true, false, false); err != nil || mplLast(c, mpl3115a2RegCtrlReg3, 1) != 0x30 {
		t.Errorf("SetInterruptPins failed")
	}
	if err := s.EnableInterrupt(MPL3115A2SourceTTH, 1); err != nil || mplLast(c, mpl3115a2RegCtrlReg4, 1)&0x04 == 0 || mplLast(c, mpl3115a2RegCtrlReg5, 1)&0x04 == 0 {
		t.Errorf("EnableInterrupt INT1 failed")
	}
	_ = s.EnableInterrupt(MPL3115A2SourceDRDY, 2)
	if mplLast(c, mpl3115a2RegCtrlReg5, 1)&0x80 != 0 {
		t.Errorf("INT2 routing set the INT1 bit")
	}
	_ = s.DisableInterrupt(MPL3115A2SourceTTH)
	if mplLast(c, mpl3115a2RegCtrlReg4, 1)&0x04 != 0 {
		t.Errorf("DisableInterrupt failed")
	}
	if err := s.EnableInterrupt(1, 3); err == nil {
		t.Errorf("EnableInterrupt pin 3 should fail")
	}
	c.setRegister(mpl3115a2RegIntSource, 0x84)
	if v, err := s.PollInterrupt(); err != nil || v != 0x84 {
		t.Errorf("PollInterrupt() = %v, %v", v, err)
	}

	if id, err := s.WhoAmI(); err != nil || id != 0xC4 {
		t.Errorf("WhoAmI() = %v, %v", id, err)
	}
	c.setRegister(mpl3115a2RegSysmod, 0x01)
	if a, err := s.IsActive(); err != nil || !a {
		t.Errorf("IsActive() = %v, %v", a, err)
	}
	if err := s.Reset(); err != nil || mplLast(c, mpl3115a2RegCtrlReg1, 1) != 0x38 {
		t.Errorf("Reset failed")
	}
}

func TestMPL3115A2InitRejectsWrongChip(t *testing.T) {
	c := newMplMock()
	c.setRegister(mpl3115a2RegWhoAmI, 0x00)
	if err := NewMPL3115A2Minimal(c).Init(); err != ErrMPL3115A2ChipID {
		t.Errorf("Init() = %v, want ErrMPL3115A2ChipID", err)
	}
}
