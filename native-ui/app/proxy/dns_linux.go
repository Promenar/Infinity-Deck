//go:build linux

package main

import (
	"context"
	"os"
	"runtime"

	"github.com/miekg/dns"
	"tailscale.com/feature/androiddns"
)

var androidRuntime = func() bool {
	if runtime.GOOS == "android" {
		return true
	}
	// 静态 linux/amd64 二进制也可在 Android 模拟器上运行。
	_, err := os.Stat("/dev/__properties__")
	return err == nil
}()

func androidDNS(ctx context.Context, q *dns.Msg) (*dns.Msg, error) {
	msg, err := q.Pack()
	if err != nil {
		return nil, err
	}
	raw, err := androiddns.Query(ctx, msg)
	if err != nil {
		return nil, err
	}
	r := new(dns.Msg)
	return r, r.Unpack(raw)
}
