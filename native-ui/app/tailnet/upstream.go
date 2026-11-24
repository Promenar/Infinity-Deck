package main

import (
	"bufio"
	"context"
	"encoding/base64"
	"errors"
	"io"
	"math"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"
)

const upstreamAddressPrefix = "127.0.0.1:"

var (
	errUpstreamChanged = errors.New("上游代理配置已切换")
	errUpstreamConnect = errors.New("上游代理 CONNECT 失败")
	publicIPv6Prefix   = netip.MustParsePrefix("2000::/3")
	specialUsePrefixes = []netip.Prefix{
		netip.MustParsePrefix("0.0.0.0/8"),
		netip.MustParsePrefix("192.0.0.0/24"),
		netip.MustParsePrefix("192.0.2.0/24"),
		netip.MustParsePrefix("192.88.99.0/24"),
		netip.MustParsePrefix("198.18.0.0/15"),
		netip.MustParsePrefix("198.51.100.0/24"),
		netip.MustParsePrefix("203.0.113.0/24"),
		netip.MustParsePrefix("240.0.0.0/4"),
		netip.MustParsePrefix("2001::/23"),
		netip.MustParsePrefix("2001:db8::/32"),
		netip.MustParsePrefix("2002::/16"),
		netip.MustParsePrefix("3fff::/20"),
	}
)

// IsGlobalUnicast 也包含部分本地与特殊用途地址，因此需要额外排除。
func isPublicAddress(addr netip.Addr) bool {
	addr = addr.Unmap()
	if !addr.IsGlobalUnicast() || addr.IsPrivate() || IsTailnetAddr(addr) || addr.Zone() != "" {
		return false
	}
	if addr.Is6() && !publicIPv6Prefix.Contains(addr) {
		return false
	}
	for _, prefix := range specialUsePrefixes {
		if prefix.Contains(addr) {
			return false
		}
	}
	return true
}

// 每份上游配置拥有自己的握手与隧道，切换时关闭旧配置的所有连接。
type upstreamProxy struct {
	address string
	auth    string

	mu     sync.Mutex
	active map[*upstreamConn]struct{}
}

func newUpstream(options *UpstreamOptions) (*upstreamProxy, error) {
	if options == nil {
		return nil, nil
	}
	portText, ok := strings.CutPrefix(options.Address, upstreamAddressPrefix)
	port, err := strconv.ParseUint(portText, 10, 16)
	if !ok || err != nil || port == 0 {
		return nil, errors.New("上游地址必须是 127.0.0.1:端口")
	}
	user, password := options.ProxyUser, options.ProxyPassword
	isControl := func(r rune) bool { return r < 32 || r == 127 }
	if user == "" || password == "" || strings.Contains(user, ":") ||
		strings.ContainsFunc(user, isControl) || strings.ContainsFunc(password, isControl) {
		return nil, errors.New("上游 Basic 代理凭据不合法")
	}
	return &upstreamProxy{
		address: upstreamAddressPrefix + strconv.FormatUint(port, 10),
		auth:    "Basic " + base64.StdEncoding.EncodeToString([]byte(user+":"+password)),
		active:  make(map[*upstreamConn]struct{}),
	}, nil
}

func (u *upstreamProxy) sameConfig(other *upstreamProxy) bool {
	if u == nil || other == nil {
		return u == other
	}
	return u.address == other.address && u.auth == other.auth
}

func (u *upstreamProxy) close() {
	u.mu.Lock()
	active := u.active
	u.active = nil
	u.mu.Unlock()
	for conn := range active {
		_ = conn.Conn.Close()
	}
}

func (u *upstreamProxy) dial(ctx context.Context, direct DialFunc, target string) (net.Conn, error) {
	// 上游自身用系统网络拨号，避免再次进入分流器。
	raw, err := direct(ctx, "tcp", u.address)
	if err != nil {
		return nil, errUpstreamConnect
	}
	limited := &io.LimitedReader{R: raw, N: maxLineBytes}
	conn := &upstreamConn{bufferedConn: bufferedConn{Conn: raw, r: bufio.NewReader(limited)}, upstream: u}
	u.mu.Lock()
	if u.active == nil {
		u.mu.Unlock()
		_ = raw.Close()
		return nil, errUpstreamChanged
	}
	u.active[conn] = struct{}{}
	u.mu.Unlock()

	deadline := time.Now().Add(dialTimeout)
	if earlier, ok := ctx.Deadline(); ok && earlier.Before(deadline) {
		deadline = earlier
	}
	stop := context.AfterFunc(ctx, func() { _ = raw.Close() })
	err = raw.SetDeadline(deadline)
	if err == nil {
		err = u.connect(conn.r, raw, target)
	}
	if !stop() || ctx.Err() != nil || err != nil || raw.SetDeadline(time.Time{}) != nil {
		_ = conn.Close()
		return nil, errUpstreamConnect
	}
	// CONNECT 成功后保留已读入的隧道数据，并允许后续读取普通数据流。
	limited.N = math.MaxInt64
	return conn, nil
}

func (u *upstreamProxy) connect(reader *bufio.Reader, conn net.Conn, target string) error {
	req := &http.Request{
		Method: http.MethodConnect,
		URL:    &url.URL{Host: target},
		Host:   target,
		Header: http.Header{
			"Proxy-Authorization":      []string{u.auth},
			"X-Infinity-Public-Target": []string{"1"},
		},
	}
	if err := req.Write(conn); err != nil {
		return errUpstreamConnect
	}
	response, err := http.ReadResponse(reader, req)
	if err != nil || response.StatusCode < 200 || response.StatusCode >= 300 {
		return errUpstreamConnect
	}
	return nil
}

type upstreamConn struct {
	bufferedConn
	upstream *upstreamProxy
}

func (c *upstreamConn) Close() error {
	err := c.Conn.Close()
	c.upstream.mu.Lock()
	delete(c.upstream.active, c)
	c.upstream.mu.Unlock()
	return err
}
