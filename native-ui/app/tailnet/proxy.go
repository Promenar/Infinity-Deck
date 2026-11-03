package main

import (
	"bufio"
	"context"
	"crypto/subtle"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httputil"
	"net/netip"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"

	"tailscale.com/net/proxymux"
)

// 本机分流代理：同一个 127.0.0.1 端口按首字节区分 SOCKS5（0x05）与 HTTP（CONNECT 及 http:// 绝对 URI 转发）。
// 两种协议都必须携带本次启动的用户名与口令，口令比较为常数时间。

const (
	socksVersion       = 5
	socksAuthPassword  = 2
	socksNoAcceptable  = 0xff
	socksCmdConnect    = 1
	socksAtypIPv4      = 1
	socksAtypDomain    = 3
	socksAtypIPv6      = 4
	socksReplyOK       = 0
	socksReplyFailure  = 1
	socksReplyHostUnr  = 4
	socksReplyRefused  = 5
	socksReplyCmdUnsup = 7
	socksReplyAddrUnsp = 8

	proxyRealm       = "infinity-tailnet"
	handshakeTimeout = 15 * time.Second
	dialTimeout      = 30 * time.Second
)

// Credentials 是本次启动的代理凭据。
type Credentials struct {
	User     string
	Password string
}

// Match 以常数时间比较；两项都参与计算，不因用户名不符提前返回。
func (c Credentials) Match(user, password string) bool {
	u := subtle.ConstantTimeCompare([]byte(user), []byte(c.User))
	p := subtle.ConstantTimeCompare([]byte(password), []byte(c.Password))
	return u&p == 1
}

// Proxy 是分流代理服务。
type Proxy struct {
	Credentials Credentials
	Dial        DialFunc

	listener net.Listener
	httpSrv  *http.Server
	fwd      *httputil.ReverseProxy
	wg       sync.WaitGroup
}

// Listen 只绑定 127.0.0.1 的随机端口。
func (p *Proxy) Listen() (int, error) {
	ln, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		return 0, err
	}
	p.listener = ln
	p.fwd = p.newForwarder()
	socksLn, httpLn := proxymux.SplitSOCKSAndHTTP(ln)
	p.httpSrv = &http.Server{
		Handler:           http.HandlerFunc(p.serveHTTP),
		ReadHeaderTimeout: handshakeTimeout,
		ErrorLog:          discardLogger(),
	}
	p.wg.Add(2)
	go func() { defer p.wg.Done(); p.serveSOCKS(socksLn) }()
	go func() { defer p.wg.Done(); _ = p.httpSrv.Serve(httpLn) }()
	return ln.Addr().(*net.TCPAddr).Port, nil
}

// Close 停止接受新连接；已建立的隧道随进程退出结束。
func (p *Proxy) Close() {
	if p.listener != nil {
		_ = p.listener.Close()
	}
	if p.httpSrv != nil {
		_ = p.httpSrv.Close()
	}
}

func (p *Proxy) serveSOCKS(ln net.Listener) {
	for {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		go p.handleSOCKS(c)
	}
}

func (p *Proxy) handleSOCKS(c net.Conn) {
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(handshakeTimeout))
	r := bufio.NewReader(c)
	// 问候：VER NMETHODS METHODS...
	var head [2]byte
	if _, err := io.ReadFull(r, head[:]); err != nil || head[0] != socksVersion {
		return
	}
	methods := make([]byte, head[1])
	if _, err := io.ReadFull(r, methods); err != nil {
		return
	}
	if !containsByte(methods, socksAuthPassword) {
		_, _ = c.Write([]byte{socksVersion, socksNoAcceptable})
		return
	}
	_, _ = c.Write([]byte{socksVersion, socksAuthPassword})
	// RFC 1929：VER ULEN UNAME PLEN PASSWD
	user, password, ok := readSOCKSAuth(r)
	if !ok || !p.Credentials.Match(user, password) {
		_, _ = c.Write([]byte{1, 1})
		return
	}
	_, _ = c.Write([]byte{1, 0})
	// 请求：VER CMD RSV ATYP DST.ADDR DST.PORT
	var req [4]byte
	if _, err := io.ReadFull(r, req[:]); err != nil || req[0] != socksVersion {
		return
	}
	host, ok := readSOCKSAddr(r, req[3])
	if !ok {
		writeSOCKSReply(c, socksReplyAddrUnsp)
		return
	}
	var portBytes [2]byte
	if _, err := io.ReadFull(r, portBytes[:]); err != nil {
		return
	}
	if req[1] != socksCmdConnect {
		writeSOCKSReply(c, socksReplyCmdUnsup)
		return
	}
	target := net.JoinHostPort(host, strconv.Itoa(int(binary.BigEndian.Uint16(portBytes[:]))))
	ctx, cancel := context.WithTimeout(context.Background(), dialTimeout)
	back, err := p.Dial(ctx, "tcp", target)
	cancel()
	if err != nil {
		writeSOCKSReply(c, socksReplyFor(err))
		return
	}
	defer back.Close()
	writeSOCKSReply(c, socksReplyOK)
	_ = c.SetDeadline(time.Time{})
	pipe(&bufferedConn{Conn: c, r: r}, back)
}

func containsByte(b []byte, v byte) bool {
	for _, x := range b {
		if x == v {
			return true
		}
	}
	return false
}

func readSOCKSAuth(r *bufio.Reader) (string, string, bool) {
	var ver [1]byte
	if _, err := io.ReadFull(r, ver[:]); err != nil || ver[0] != 1 {
		return "", "", false
	}
	user, ok := readShortString(r)
	if !ok {
		return "", "", false
	}
	password, ok := readShortString(r)
	return user, password, ok
}

func readShortString(r *bufio.Reader) (string, bool) {
	n, err := r.ReadByte()
	if err != nil {
		return "", false
	}
	b := make([]byte, n)
	if _, err := io.ReadFull(r, b); err != nil {
		return "", false
	}
	return string(b), true
}

func readSOCKSAddr(r *bufio.Reader, atyp byte) (string, bool) {
	switch atyp {
	case socksAtypIPv4:
		var b [4]byte
		if _, err := io.ReadFull(r, b[:]); err != nil {
			return "", false
		}
		return net.IP(b[:]).String(), true
	case socksAtypIPv6:
		var b [16]byte
		if _, err := io.ReadFull(r, b[:]); err != nil {
			return "", false
		}
		return net.IP(b[:]).String(), true
	case socksAtypDomain:
		name, ok := readShortString(r)
		return name, ok && name != ""
	}
	return "", false
}

// writeSOCKSReply 回复固定的绑定地址 0.0.0.0:0；客户端不使用该字段。
func writeSOCKSReply(c net.Conn, code byte) {
	_, _ = c.Write([]byte{socksVersion, code, 0, socksAtypIPv4, 0, 0, 0, 0, 0, 0})
}

func socksReplyFor(err error) byte {
	switch {
	case errors.Is(err, errTailnetNotRunning), errors.Is(err, errUnknownTailnet):
		return socksReplyHostUnr
	case isConnRefused(err):
		return socksReplyRefused
	}
	var dnsErr *net.DNSError
	if errors.As(err, &dnsErr) {
		return socksReplyHostUnr
	}
	return socksReplyFailure
}

func isConnRefused(err error) bool {
	return err != nil && strings.Contains(err.Error(), "connection refused")
}

// serveHTTP 处理 CONNECT 隧道与 http:// 绝对 URI 的转发请求。
func (p *Proxy) serveHTTP(w http.ResponseWriter, r *http.Request) {
	if !p.authorized(r.Header.Get("Proxy-Authorization")) {
		w.Header().Set("Proxy-Authenticate", `Basic realm="`+proxyRealm+`"`)
		http.Error(w, "需要代理认证", http.StatusProxyAuthRequired)
		return
	}
	if r.Method == http.MethodConnect {
		p.serveConnect(w, r)
		return
	}
	if r.URL.Scheme != "http" || r.URL.Host == "" {
		http.Error(w, "只转发 http:// 绝对 URI；HTTPS 请使用 CONNECT", http.StatusBadRequest)
		return
	}
	p.fwd.ServeHTTP(w, r)
}

func (p *Proxy) authorized(header string) bool {
	const prefix = "basic "
	if len(header) < len(prefix) || !strings.EqualFold(header[:len(prefix)], prefix) {
		return false
	}
	raw, err := base64.StdEncoding.DecodeString(strings.TrimSpace(header[len(prefix):]))
	if err != nil {
		return false
	}
	user, password, ok := strings.Cut(string(raw), ":")
	return ok && p.Credentials.Match(user, password)
}

func (p *Proxy) serveConnect(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), dialTimeout)
	back, err := p.Dial(ctx, "tcp", r.Host)
	cancel()
	if err != nil {
		http.Error(w, err.Error(), httpStatusFor(err))
		return
	}
	hj, ok := w.(http.Hijacker)
	if !ok {
		back.Close()
		http.Error(w, "无法建立隧道", http.StatusInternalServerError)
		return
	}
	c, brw, err := hj.Hijack()
	if err != nil {
		back.Close()
		return
	}
	if _, err := io.WriteString(c, "HTTP/1.1 200 Connection established\r\n\r\n"); err != nil {
		c.Close()
		back.Close()
		return
	}
	pipe(&bufferedConn{Conn: c, r: brw.Reader}, back)
	c.Close()
	back.Close()
}

func httpStatusFor(err error) int {
	switch {
	case errors.Is(err, errTailnetNotRunning):
		return http.StatusServiceUnavailable
	}
	return http.StatusBadGateway
}

const tailnetOriginHostHeader = "X-Infinity-Origin-Host"

// 固定 tailnet IP 的 HTTP 请求保留原始虚拟主机；内部头只携带合法 authority。
func forwardedOriginHost(target, authority string) string {
	addr, err := netip.ParseAddr(target)
	if err != nil || !IsTailnetAddr(addr) || authority == "" || strings.ContainsAny(authority, " \t\r\n") {
		return ""
	}
	origin, err := url.Parse("http://" + authority)
	if err != nil || origin.Host != authority || origin.Hostname() == "" || origin.User != nil ||
		origin.Path != "" || origin.RawQuery != "" || origin.Fragment != "" || strings.HasSuffix(authority, ":") {
		return ""
	}
	if !strings.HasPrefix(authority, "[") && strings.Count(authority, ":") > 1 {
		return ""
	}
	if strings.HasPrefix(authority, "[") {
		literal, err := netip.ParseAddr(origin.Hostname())
		if err != nil || !literal.Is6() {
			return ""
		}
	}
	if port := origin.Port(); port != "" {
		value, err := strconv.Atoi(port)
		if err != nil || value < 1 || value > 65535 {
			return ""
		}
	}
	return authority
}

// newForwarder 转发 http:// 绝对 URI 请求；不添加 X-Forwarded-* 头，Proxy-Authorization 等逐跳头由 ReverseProxy 去除。
func (p *Proxy) newForwarder() *httputil.ReverseProxy {
	return &httputil.ReverseProxy{
		Rewrite: func(pr *httputil.ProxyRequest) {
			pr.Out.URL = pr.In.URL
			pr.Out.Host = pr.In.Host
			origin := pr.In.Header.Get(tailnetOriginHostHeader)
			pr.Out.Header.Del(tailnetOriginHostHeader)
			if host := forwardedOriginHost(pr.In.URL.Hostname(), origin); host != "" {
				pr.Out.Host = host
			}
			pr.Out.Header.Del("Proxy-Connection")
		},
		Transport: &http.Transport{
			DialContext:           p.Dial,
			Proxy:                 nil,
			DisableCompression:    true,
			MaxIdleConns:          16,
			IdleConnTimeout:       60 * time.Second,
			ResponseHeaderTimeout: 120 * time.Second,
		},
		FlushInterval: -1,
		ErrorLog:      discardLogger(),
		ErrorHandler: func(w http.ResponseWriter, r *http.Request, err error) {
			http.Error(w, err.Error(), httpStatusFor(err))
		},
	}
}

// bufferedConn 让隧道先读出握手阶段已缓冲的字节。
type bufferedConn struct {
	net.Conn
	r *bufio.Reader
}

func (b *bufferedConn) Read(p []byte) (int, error) { return b.r.Read(p) }

func (b *bufferedConn) CloseWrite() error {
	if cw, ok := b.Conn.(interface{ CloseWrite() error }); ok {
		return cw.CloseWrite()
	}
	return nil
}

// pipe 双向转发，一侧读到 EOF 后半关闭另一侧的写方向，两个方向都结束后返回。
func pipe(client, backend net.Conn) {
	var wg sync.WaitGroup
	wg.Add(2)
	copyHalf := func(dst, src net.Conn) {
		defer wg.Done()
		_, err := io.Copy(dst, src)
		if cw, ok := dst.(interface{ CloseWrite() error }); ok && err == nil {
			_ = cw.CloseWrite()
		} else {
			// 读写出错时关闭两侧，避免另一方向永久阻塞。
			_ = client.Close()
			_ = backend.Close()
		}
	}
	go copyHalf(backend, client)
	go copyHalf(client, backend)
	wg.Wait()
}
