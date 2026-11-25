package adcdac

import (
	"errors"
	"math"
	"testing"
	"time"
)

func mcp3422Near(a, b float32) bool { return math.Abs(float64(a-b)) < 1e-5 }

// mcp3422Frame builds a result frame: the two's-complement code in 2 (or 3, at
// 18 bit) bytes followed by the config byte with the RDY flag.
func mcp3422Frame(raw int32, bits int, rdy bool) []byte {
	n := 2
	if bits == 18 {
		n = 3
	}
	v := uint32(raw)
	out := make([]byte, 0, n+1)
	for i := n - 1; i >= 0; i-- {
		out = append(out, byte(v>>(8*uint(i))))
	}
	cfg := byte(0)
	if rdy {
		cfg = 0x80
	}
	return append(out, cfg)
}

func newMCP3422(t *testing.T, frames ...[]byte) (*mockConnection, *MCP3422Full) {
	t.Helper()
	mcp3422Wait = func(time.Duration) {} // skip the conversion-time waits
	m := newMockConnection()
	for _, f := range frames {
		m.queueRead(f)
	}
	chip, err := NewMCP3422Full(m)
	if err != nil {
		t.Fatal(err)
	}
	return m, chip
}

func lastWriteByte(m *mockConnection) byte { return m.writes[len(m.writes)-1][0] }

func TestMCP3422InitAndConfigBytes(t *testing.T) {
	m, chip := newMCP3422(t, mcp3422Frame(0, 16, false), mcp3422Frame(0, 16, false), mcp3422Frame(0, 16, false), mcp3422Frame(0, 16, false))
	if len(m.writes) != 1 || len(m.writes[0]) != 1 || m.writes[0][0] != 0x08 {
		t.Errorf("init writes = %v, want [0x08]", m.writes)
	}
	chip.ReadRaw(0)
	if lastWriteByte(m) != 0x88 {
		t.Errorf("CH1 config = %#x", lastWriteByte(m))
	}
	chip.ReadRaw(1)
	if lastWriteByte(m) != 0xA8 {
		t.Errorf("CH2 config = %#x", lastWriteByte(m))
	}
	chip.ReadRaw(7)
	if lastWriteByte(m) != 0xA8 {
		t.Errorf("clamp high = %#x", lastWriteByte(m))
	}
	chip.ReadRaw(-3)
	if lastWriteByte(m) != 0x88 {
		t.Errorf("clamp low = %#x", lastWriteByte(m))
	}
}

func TestMCP3422Decoding(t *testing.T) {
	cases := []struct {
		bits int
		raw  int32
	}{
		{12, -100}, {12, 2047}, {12, -2048}, {14, -8192}, {14, 8191},
		{16, 12345}, {16, -12345}, {16, -32768}, {18, 131071}, {18, -131072}, {18, -1},
	}
	for _, c := range cases {
		_, chip := newMCP3422(t, mcp3422Frame(c.raw, c.bits, false))
		if err := chip.SetResolution(c.bits); err != nil {
			t.Fatal(err)
		}
		if got, err := chip.ReadRaw(0); err != nil || got != c.raw {
			t.Errorf("%d bit raw %d decoded as %d (%v)", c.bits, c.raw, got, err)
		}
	}
	_, chip := newMCP3422(t, []byte{0xFC, 0x00, 0x05, 0x00}) // byte0's upper six bits are not data
	chip.SetResolution(18)
	if got, _ := chip.ReadRaw(0); got != 5 {
		t.Errorf("18 bit masking: %d", got)
	}
}

func TestMCP3422Voltages(t *testing.T) {
	_, chip := newMCP3422(t, mcp3422Frame(16384, 16, false), mcp3422Frame(-32768, 16, false))
	if v, _ := chip.ReadVoltage(0); !mcp3422Near(v, 16384*2*2.048/65536) {
		t.Errorf("x1 voltage = %v", v)
	}
	chip.SetGain(8)
	if v, _ := chip.ReadVoltage(0); !mcp3422Near(v, -0.256) {
		t.Errorf("x8 full scale = %v", v)
	}
	if !mcp3422Near(chip.FullScale(), 0.256) || !mcp3422Near(chip.Lsb(), 62.5e-6) {
		t.Errorf("full scale %v lsb %v", chip.FullScale(), chip.Lsb())
	}
	chip.SetResolution(12)
	if !mcp3422Near(chip.Lsb(), 1e-3) {
		t.Errorf("12 bit lsb = %v", chip.Lsb())
	}
	chip.SetResolution(18)
	if !mcp3422Near(chip.Lsb(), 15.625e-6) {
		t.Errorf("18 bit lsb = %v", chip.Lsb())
	}
}

func TestMCP3422ReadAllAndScan(t *testing.T) {
	m, chip := newMCP3422(t, mcp3422Frame(100, 16, false), mcp3422Frame(200, 16, false), mcp3422Frame(10, 16, false), mcp3422Frame(20, 16, false), mcp3422Frame(30, 16, false))
	v, err := chip.ReadAll()
	if err != nil || !mcp3422Near(v[0], 100*2*2.048/65536) || !mcp3422Near(v[1], 200*2*2.048/65536) {
		t.Errorf("ReadAll = %v, %v", v, err)
	}
	if m.writes[1][0] != 0x88 || m.writes[2][0] != 0xA8 {
		t.Errorf("ReadAll order: %v", m.writes[1:3])
	}
	out, _ := chip.ReadScan([]int{1, 0, 1})
	if len(out) != 3 || m.writes[3][0] != 0xA8 || m.writes[4][0] != 0x88 || m.writes[5][0] != 0xA8 {
		t.Errorf("ReadScan: %v %v", out, m.writes[3:])
	}
}

func TestMCP3422PollingAndTimeout(t *testing.T) {
	_, chip := newMCP3422(t, mcp3422Frame(1, 16, true), mcp3422Frame(1, 16, true), mcp3422Frame(777, 16, false))
	if got, err := chip.ReadRaw(0); err != nil || got != 777 {
		t.Errorf("polling: %d %v", got, err)
	}
	stale := make([][]byte, 40)
	for i := range stale {
		stale[i] = mcp3422Frame(1, 16, true)
	}
	_, chip2 := newMCP3422(t, stale...)
	if _, err := chip2.ReadRaw(0); !errors.Is(err, ErrMCP3422Timeout) {
		t.Errorf("timeout err = %v", err)
	}
}

func TestMCP3422Selection(t *testing.T) {
	m, chip := newMCP3422(t, mcp3422Frame(0, 18, false))
	if !errors.Is(chip.SetResolution(10), ErrMCP3422Resolution) || !errors.Is(chip.SetGain(3), ErrMCP3422Gain) {
		t.Error("bad values must be rejected")
	}
	chip.SetResolution(18)
	chip.SetGain(8)
	if chip.GetResolution() != 18 || chip.GetGain() != 8 {
		t.Errorf("getters %d %d", chip.GetResolution(), chip.GetGain())
	}
	chip.ReadRaw(0)
	if lastWriteByte(m) != 0x8F {
		t.Errorf("18 bit x8 config = %#x", lastWriteByte(m))
	}
}

func TestMCP3422Continuous(t *testing.T) {
	m, chip := newMCP3422(t, mcp3422Frame(5, 12, false), mcp3422Frame(300, 12, false), mcp3422Frame(301, 12, false))
	chip.SetResolution(12)
	chip.StartContinuous(-1)
	if lastWriteByte(m) != 0x10 || !chip.IsContinuous() {
		t.Errorf("start config %#x", lastWriteByte(m))
	}
	if v, _ := chip.ReadContinuousRaw(); v != 300 {
		t.Errorf("first sample not discarded: %d", v)
	}
	if v, _ := chip.ReadContinuousRaw(); v != 301 {
		t.Errorf("next sample %d", v)
	}
	chip.StartContinuous(1)
	if lastWriteByte(m) != 0x30 {
		t.Errorf("channel config %#x", lastWriteByte(m))
	}
	chip.StopContinuous()
	if lastWriteByte(m) != 0x20 || chip.IsContinuous() {
		t.Errorf("stop config %#x", lastWriteByte(m))
	}
	if _, err := chip.ReadContinuousRaw(); !errors.Is(err, ErrMCP3422NotContinuous) {
		t.Errorf("err = %v", err)
	}

	m2, c2 := newMCP3422(t, mcp3422Frame(1, 12, false), mcp3422Frame(2, 12, false), mcp3422Frame(3, 12, false), mcp3422Frame(4, 12, false))
	c2.SetResolution(12)
	c2.StartContinuous(-1)
	c2.ReadContinuousRaw()
	n := len(m2.writes)
	c2.SetGain(2)
	if len(m2.writes) != n+1 || lastWriteByte(m2) != 0x11 {
		t.Errorf("gain change write: %v", m2.writes[n:])
	}
	if v, _ := c2.ReadContinuousRaw(); v != 4 {
		t.Errorf("sample after gain change = %d", v)
	}

	_, c3 := newMCP3422(t, mcp3422Frame(5, 16, false), mcp3422Frame(16384, 16, false))
	c3.StartContinuous(-1)
	if v, _ := c3.ReadContinuous(); !mcp3422Near(v, 16384*2*2.048/65536) {
		t.Errorf("continuous voltage %v", v)
	}
}

func TestMCP3422ConfigAndGeneralCalls(t *testing.T) {
	_, chip := newMCP3422(t, mcp3422Frame(0, 16, true), mcp3422Frame(0, 16, false))
	if cfg, _ := chip.ReadConfig(); cfg != 0x80 {
		t.Errorf("config %#x", cfg)
	}
	if ok, _ := chip.IsReady(); !ok {
		t.Error("IsReady")
	}
	m, c2 := newMCP3422(t, mcp3422Frame(321, 16, false))
	c2.SetResolution(18)
	c2.SetGain(8)
	c2.GeneralCallReset()
	w := m.writes[len(m.writes)-1]
	if len(w) != 2 || w[0] != 0x00 || w[1] != 0x06 || c2.GetResolution() != 12 || c2.GetGain() != 1 || !c2.IsContinuous() {
		t.Errorf("general call reset: %v %d %d", w, c2.GetResolution(), c2.GetGain())
	}
	c2.StopContinuous()
	c2.SetResolution(16)
	c2.GeneralCallConvert()
	w = m.writes[len(m.writes)-1]
	if len(w) != 2 || w[1] != 0x08 {
		t.Errorf("general call convert: %v", w)
	}
	if v, _ := c2.ReadPendingRaw(); v != 321 {
		t.Errorf("pending = %d", v)
	}
}
