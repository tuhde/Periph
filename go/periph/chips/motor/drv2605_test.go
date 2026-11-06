package motor

import (
	"bytes"
	"math"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// drv2605Mock is an in-memory register-addressed fake connection.RegisterConnection
// whose DEV_RESET and GO bits self-clear like the real chip.
type drv2605Mock struct {
	regs   map[uint32]byte
	writes [][]byte // each: reg followed by data
}

func newDRV2605Mock() *drv2605Mock {
	return &drv2605Mock{regs: map[uint32]byte{0x00: 0x60}}
}

func (m *drv2605Mock) set(reg uint32, vals ...byte) {
	for i, v := range vals {
		m.regs[reg+uint32(i)] = v
	}
}

func (m *drv2605Mock) Write(data []byte) error { return nil }
func (m *drv2605Mock) Read(n int) ([]byte, error) {
	return make([]byte, n), nil
}
func (m *drv2605Mock) WriteRead(data []byte, n int) ([]byte, error) {
	return m.ReadReg(uint32(data[0]), n)
}
func (m *drv2605Mock) ReadReg(reg uint32, length int) ([]byte, error) {
	out := make([]byte, length)
	for i := range out {
		out[i] = m.regs[reg+uint32(i)]
	}
	return out, nil
}
func (m *drv2605Mock) WriteReg(reg uint32, data []byte) error {
	m.writes = append(m.writes, append([]byte{byte(reg)}, data...))
	for i, v := range data {
		m.regs[reg+uint32(i)] = v
	}
	if reg == 0x01 {
		m.regs[0x01] &= 0x7F
	}
	if reg == 0x0C {
		m.regs[0x0C] = 0
	}
	return nil
}
func (m *drv2605Mock) Close() error                { return nil }
func (m *drv2605Mock) Enable()                     {}
func (m *drv2605Mock) Disable()                    {}
func (m *drv2605Mock) IsEnabled() bool             { return true }
func (m *drv2605Mock) IntPin() connection.InputPin { return nil }
func (m *drv2605Mock) EnPin() connection.OutputPin { return nil }

// last returns the data bytes of the last write to reg, or nil.
func (m *drv2605Mock) last(reg byte) []byte {
	var out []byte
	for _, w := range m.writes {
		if w[0] == reg && len(w) >= 2 {
			out = w[1:]
		}
	}
	return out
}

func (m *drv2605Mock) lastByte(reg byte) int {
	l := m.last(reg)
	if l == nil {
		return -1
	}
	return int(l[0])
}

func (m *drv2605Mock) wrote(reg, val byte) bool {
	for _, w := range m.writes {
		if len(w) == 2 && w[0] == reg && w[1] == val {
			return true
		}
	}
	return false
}

func newFull(t *testing.T, lra bool) (*drv2605Mock, *DRV2605Full) {
	t.Helper()
	m := newDRV2605Mock()
	f, err := NewDRV2605Full(m, lra)
	if err != nil {
		t.Fatal(err)
	}
	return m, f
}

func TestDRV2605Init(t *testing.T) {
	m := newDRV2605Mock()
	if _, err := NewDRV2605Minimal(m, false); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(m.writes[0], []byte{0x01, 0x80}) {
		t.Fatalf("reset first: %v", m.writes[0])
	}
	if !m.wrote(0x01, 0x00) || m.lastByte(0x1A) != 0x36 || m.lastByte(0x03) != 0x01 {
		t.Fatalf("erm init writes: %v", m.writes)
	}
	m = newDRV2605Mock()
	if _, err := NewDRV2605Minimal(m, true); err != nil {
		t.Fatal(err)
	}
	if m.lastByte(0x1A) != 0xB6 || m.lastByte(0x03) != 0x06 {
		t.Fatalf("lra init writes: %v", m.writes)
	}
	m = newDRV2605Mock()
	m.set(0x00, 0xE0)
	if _, err := NewDRV2605Minimal(m, false); err == nil {
		t.Fatal("wrong DEVICE_ID must fail")
	}
}

func TestDRV2605Playback(t *testing.T) {
	m := newDRV2605Mock()
	d, _ := NewDRV2605Minimal(m, false)
	if err := d.PlayEffect(47); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(m.last(0x04), []byte{47, 0}) || m.lastByte(0x0C) != 1 {
		t.Fatalf("play_effect writes: %v", m.writes)
	}
	if d.PlayEffect(0) != ErrDRV2605InvalidArgument || d.PlayEffect(124) != ErrDRV2605InvalidArgument {
		t.Fatal("effect range not enforced")
	}
	_ = d.Stop()
	if m.lastByte(0x0C) != 0 {
		t.Fatal("stop must clear GO")
	}
	m.set(0x0C, 1)
	if p, _ := d.IsPlaying(); !p {
		t.Fatal("is_playing true")
	}
	m.set(0x0C, 0)
	if p, _ := d.IsPlaying(); p {
		t.Fatal("is_playing false")
	}
}

func TestDRV2605Sequence(t *testing.T) {
	m, f := newFull(t, false)
	if err := f.PlaySequence([]DRV2605SequenceStep{DRV2605Effect(1), DRV2605Wait(100), DRV2605Effect(10)}); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(m.last(0x04), []byte{1, 0x8A, 10, 0}) {
		t.Fatalf("encoding: %v", m.last(0x04))
	}
	eight := make([]DRV2605SequenceStep, 8)
	for i := range eight {
		eight[i] = DRV2605Effect(uint8(i + 1))
	}
	_ = f.PlaySequence(eight)
	if !bytes.Equal(m.last(0x04), []byte{1, 2, 3, 4, 5, 6, 7, 8}) {
		t.Fatalf("eight steps: %v", m.last(0x04))
	}
	if f.PlaySequence(append(eight, DRV2605Effect(9))) == nil || f.PlaySequence([]DRV2605SequenceStep{DRV2605Wait(2000)}) == nil || f.PlaySequence(nil) == nil {
		t.Fatal("invalid sequences must be rejected")
	}
	m.set(0x0C, 0)
	if ok, _ := f.WaitDone(50); !ok {
		t.Fatal("wait_done true")
	}
	m.set(0x0C, 1)
	m.regs[0x0C] = 1
	if ok, _ := f.WaitDone(20); ok {
		t.Fatal("wait_done must time out")
	}
}

func TestDRV2605ModeBits(t *testing.T) {
	m, f := newFull(t, false)
	_ = f.SetMode(5)
	_ = f.Standby()
	if m.lastByte(0x01) != 0x45 {
		t.Fatalf("standby: %#x", m.lastByte(0x01))
	}
	_ = f.Wake()
	if m.lastByte(0x01) != 0x05 {
		t.Fatalf("wake: %#x", m.lastByte(0x01))
	}
	_ = f.SetHiZ(true)
	_ = f.SetLibrary(6)
	if m.lastByte(0x03) != 0x16 {
		t.Fatalf("library keeps HI_Z: %#x", m.lastByte(0x03))
	}
	if f.SetLibrary(7) == nil || f.SetMode(8) == nil {
		t.Fatal("range")
	}
	m.set(0x1A, 0x36)
	_ = f.SetActuator(true)
	if m.lastByte(0x1A) != 0xB6 {
		t.Fatal("set_actuator")
	}
}

func TestDRV2605RTP(t *testing.T) {
	m, f := newFull(t, false)
	_ = f.RtpLevel(0.5)
	if !m.wrote(0x01, 0x05) || m.lastByte(0x02) != 64 {
		t.Fatalf("rtp_level: %v", m.writes)
	}
	_ = f.RtpWrite(200)
	if m.lastByte(0x02) != 200 {
		t.Fatal("rtp_write")
	}
	_ = f.Stop()
	if m.lastByte(0x02) != 0 || m.lastByte(0x01) != 0x00 {
		t.Fatal("stop must leave RTP")
	}
	if f.RtpLevel(1.5) == nil {
		t.Fatal("range")
	}
	_ = f.SetRtpFormat(true)
	if m.lastByte(0x1D)&0x08 == 0 {
		t.Fatal("unsigned")
	}
}

func TestDRV2605LoopConstraint(t *testing.T) {
	m, f := newFull(t, false)
	m.set(0x1D, 0x20)
	if f.SetBidirectional(false) != ErrDRV2605InvalidState {
		t.Fatal("unidirectional must be rejected in open loop")
	}
	_ = f.SetOpenLoop(false)
	if m.lastByte(0x1D) != 0x00 {
		t.Fatal("erm open loop clear")
	}
	if f.SetBidirectional(false) != nil || m.lastByte(0x1C)&0x80 != 0 {
		t.Fatal("unidirectional closed loop")
	}
}

func TestDRV2605VoltageConversions(t *testing.T) {
	m, f := newFull(t, false)
	m.set(0x1A, 0x36)
	_ = f.SetRatedVoltage(3.0, 0)
	if m.lastByte(0x16) != 141 {
		t.Fatalf("erm rated: %d", m.lastByte(0x16))
	}
	_ = f.SetRatedVoltageCode(77)
	if m.lastByte(0x16) != 77 {
		t.Fatal("raw code")
	}
	m.set(0x1D, 0x20)
	_ = f.SetOverdriveClamp(3.0, 0)
	if m.lastByte(0x17) != 137 {
		t.Fatalf("erm open-loop clamp: %d", m.lastByte(0x17))
	}
	m.set(0x1D, 0x00)
	if f.SetOverdriveClamp(3.0, 0) != ErrDRV2605InvalidState {
		t.Fatal("erm closed-loop clamp in volts must be rejected")
	}
	m.set(0x1A, 0xB6)
	m.set(0x1C, 0x30)
	_ = f.SetRatedVoltage(2.0, 200)
	if m.lastByte(0x16) != 81 {
		t.Fatalf("lra rated: %d", m.lastByte(0x16))
	}
	if f.SetRatedVoltage(2.0, 0) != ErrDRV2605InvalidArgument {
		t.Fatal("lra needs frequency")
	}
	_ = f.SetDriveTime(2.5)
	if m.lastByte(0x1B)&0x1F != 20 {
		t.Fatal("lra drive time")
	}
	m.set(0x1A, 0x36)
	_ = f.SetDriveTime(5.0)
	if m.lastByte(0x1B)&0x1F != 20 {
		t.Fatal("erm drive time")
	}
}

func TestDRV2605FeedbackAndOffsets(t *testing.T) {
	m, f := newFull(t, false)
	m.set(0x1A, 0x80)
	_ = f.SetFeedback(2, 2, 1)
	if m.lastByte(0x1A) != 0x80|2<<4|2<<2|1 {
		t.Fatal("feedback preserves type")
	}
	_ = f.SetTimeOffsets(10, -5, 0, -640)
	if !bytes.Equal(m.last(0x0D), []byte{2, 0xFF, 0, 0x80}) {
		t.Fatalf("offsets: %v", m.last(0x0D))
	}
	if f.SetTimeOffsets(0, 0, 0, 700) == nil {
		t.Fatal("offset range")
	}
}

func TestDRV2605Calibration(t *testing.T) {
	m, f := newFull(t, false)
	m.set(0x18, 0x0D, 0x6D)
	m.set(0x1A, 0x36)
	c, err := f.Calibrate(false, 3.0, 3.0, 0)
	if err != nil {
		t.Fatal(err)
	}
	if !m.wrote(0x01, 0x07) || m.lastByte(0x16) != 141 || m.lastByte(0x17) != 137 || m.lastByte(0x1E)&0x30 != 0x30 || !m.wrote(0x0C, 1) {
		t.Fatalf("calibration inputs: %v", m.writes)
	}
	if !c.OK || c.Comp != 0x0D || c.BEMF != 0x6D || c.BEMFGain != 2 || m.lastByte(0x01) != 0x00 {
		t.Fatalf("result: %+v", c)
	}
	m.set(0x00, 0x68)
	if c, _ := f.Calibrate(false, 3.0, 3.0, 0); c.OK {
		t.Fatal("DIAG_RESULT must fail the calibration")
	}
	m.set(0x00, 0x60)
	if _, err := f.Calibrate(true, 2.0, 2.5, 0); err != ErrDRV2605InvalidArgument {
		t.Fatal("lra needs frequency")
	}
	_, _ = f.Calibrate(true, 2.0, 2.5, 200)
	if m.lastByte(0x1A)&0x80 == 0 {
		t.Fatal("lra feedback")
	}
	_ = f.SetCalibration(0x20, 0x80, 3)
	g, _ := f.GetCalibration()
	if g.Comp != 0x20 || g.BEMF != 0x80 || g.BEMFGain != 3 || f.SetCalibration(0, 0, 4) == nil {
		t.Fatalf("get/set calibration: %+v", g)
	}
}

func TestDRV2605Diagnose(t *testing.T) {
	m, f := newFull(t, false)
	if ok, _ := f.Diagnose(); !ok || !m.wrote(0x01, 0x06) {
		t.Fatal("diagnose ok")
	}
	m.set(0x00, 0x68)
	if ok, _ := f.Diagnose(); ok {
		t.Fatal("diagnose fail")
	}
}

func TestDRV2605AudioToVibe(t *testing.T) {
	m, f := newFull(t, false)
	cfg := DRV2605AudioToVibeConfig{ACCouple: true, PeakTimeMs: 30, FilterHz: 150, MinInputV: 0, MaxInputV: 1.8, MinDrive: 0, MaxDrive: 1}
	if err := f.SetAudioToVibe(cfg); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(m.last(0x11), []byte{2<<2 | 2, 0, 255, 0, 255}) || m.lastByte(0x01) != 0x04 || m.lastByte(0x1B)&0x20 == 0 {
		t.Fatalf("atv: %v", m.writes)
	}
	cfg.FilterHz = 300
	if f.SetAudioToVibe(cfg) == nil {
		t.Fatal("bad filter")
	}
}

func TestDRV2605StatusAndReadouts(t *testing.T) {
	m, f := newFull(t, false)
	m.set(0x00, 0x60|0x08|0x01)
	st, _ := f.ReadStatus()
	if !st.DiagFailed || st.FBTimeout || st.OverTemp || !st.OverCurrent {
		t.Fatalf("status: %+v", st)
	}
	if id, _ := f.ReadDeviceID(); id != 3 {
		t.Fatal("device id")
	}
	m.set(0x21, 255)
	if v, _ := f.ReadSupplyVoltage(); math.Abs(float64(v)-5.6) > 1e-4 {
		t.Fatal("supply voltage")
	}
	m.set(0x22, 0)
	if _, ok, _ := f.ReadLRAFrequency(); ok {
		t.Fatal("lra none")
	}
	m.set(0x22, 52)
	if hz, ok, _ := f.ReadLRAFrequency(); !ok || math.Abs(float64(hz)-195.3) > 0.2 {
		t.Fatal("lra freq")
	}
}

func TestDRV2605OTPGuards(t *testing.T) {
	m, f := newFull(t, false)
	n := len(m.writes)
	if f.ProgramOTP(false) != ErrDRV2605InvalidArgument || len(m.writes) != n {
		t.Fatal("confirm required")
	}
	m.set(0x1E, 0x04)
	if ok, _ := f.OTPProgrammed(); !ok {
		t.Fatal("otp status")
	}
	if f.ProgramOTP(true) != ErrDRV2605InvalidState {
		t.Fatal("already programmed")
	}
}
