package connection

import "testing"

type recordingPin struct{ levels []bool }

func (p *recordingPin) Set(high bool) error {
	p.levels = append(p.levels, high)
	return nil
}

func TestEnPolarity(t *testing.T) {
	for _, activeHigh := range []bool{true, false} {
		pin := &recordingPin{}
		b := &connectionBase{enPin: pin}
		b.SetEnActiveHigh(activeHigh)
		b.Disable()
		b.Enable()
		if pin.levels[0] != !activeHigh {
			t.Errorf("activeHigh=%v: Disable drove %v, want %v", activeHigh, pin.levels[0], !activeHigh)
		}
		if pin.levels[1] != activeHigh {
			t.Errorf("activeHigh=%v: Enable drove %v, want %v", activeHigh, pin.levels[1], activeHigh)
		}
		if !b.IsEnabled() {
			t.Errorf("activeHigh=%v: software gate closed after Enable", activeHigh)
		}
	}
}

func TestEnPolarityDefaultActiveHigh(t *testing.T) {
	pin := &recordingPin{}
	b := &connectionBase{enPin: pin}
	b.Enable()
	if pin.levels[0] != true {
		t.Errorf("default Enable drove %v, want true", pin.levels[0])
	}
}
