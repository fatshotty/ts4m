package tgengine

import (
	"context"
	"sync/atomic"
	"testing"
	"time"
)

func TestWarmPoolOpensRequestedConnections(t *testing.T) {
	var calls, concurrent, maxConcurrent int32
	open := func(ctx context.Context) error {
		c := atomic.AddInt32(&concurrent, 1)
		for {
			m := atomic.LoadInt32(&maxConcurrent)
			if c <= m || atomic.CompareAndSwapInt32(&maxConcurrent, m, c) {
				break
			}
		}
		atomic.AddInt32(&calls, 1)
		time.Sleep(10 * time.Millisecond) // hold so concurrency is observable
		atomic.AddInt32(&concurrent, -1)
		return nil
	}
	warmPool(context.Background(), 4, open)
	if got := atomic.LoadInt32(&calls); got != 4 {
		t.Fatalf("open called %d times, want 4", got)
	}
	if got := atomic.LoadInt32(&maxConcurrent); got < 2 {
		t.Fatalf("opens were not concurrent, max concurrency = %d", got)
	}
}

func TestWarmPoolSkipsSingleConnection(t *testing.T) {
	var calls int32
	warmPool(context.Background(), 1, func(ctx context.Context) error {
		atomic.AddInt32(&calls, 1)
		return nil
	})
	if got := atomic.LoadInt32(&calls); got != 0 {
		t.Fatalf("open called %d times for conns<=1, want 0", got)
	}
}

func TestPickConns(t *testing.T) {
	const gib = int64(1) << 30
	cases := []struct {
		name  string
		sizeG int64 // file size in GiB
		max   int
		want  int
	}{
		{"tiny file uses one", 4, 5, 1},
		{"just under one step", 19, 5, 1},
		{"exactly one step uses one", 20, 5, 1},
		{"just over one step uses two", 21, 5, 2},
		{"40 GiB uses two", 40, 5, 2},
		{"60 GiB uses three", 60, 5, 3},
		{"80 GiB uses four", 80, 5, 4},
		{"capped at user max", 200, 3, 3},
		{"max below one is floored to one", 5, 0, 1},
		{"zero size still uses one", 0, 5, 1},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := pickConns(c.sizeG*gib, c.max); got != c.want {
				t.Fatalf("pickConns(%d GiB, max=%d) = %d, want %d", c.sizeG, c.max, got, c.want)
			}
		})
	}
}

func TestPoolNeedsResize(t *testing.T) {
	cases := []struct {
		name        string
		existingMax int64
		requested   int64
		want        bool
	}{
		{"larger request grows the pool", 4, 8, true},
		{"equal request reuses the pool", 4, 4, false},
		{"smaller request reuses the pool", 4, 2, false},
		{"unknown existing max forces recreation", 0, 4, true},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := poolNeedsResize(c.existingMax, c.requested); got != c.want {
				t.Fatalf("poolNeedsResize(%d, %d) = %v, want %v",
					c.existingMax, c.requested, got, c.want)
			}
		})
	}
}
