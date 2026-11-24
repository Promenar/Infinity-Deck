#define _POSIX_C_SOURCE 200809L
#define _DARWIN_C_SOURCE

#include "bridge-protocol.h"

#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <termios.h>

static const int forwarded_signals[] = {SIGHUP, SIGINT, SIGTERM, SIGWINCH};
static volatile sig_atomic_t pending[sizeof(forwarded_signals) / sizeof(forwarded_signals[0])];

static void on_signal(int signum) {
    for (size_t i = 0; i < sizeof(pending) / sizeof(pending[0]); ++i) {
        if (forwarded_signals[i] == signum) {
            pending[i] = 1;
            return;
        }
    }
}

#ifdef NX_SHIM_HOST_TEST
#include "bridge-posix.h"

#include <sys/select.h>

static sigset_t wait_mask;
static sigset_t forwarded_mask;

static long shim_ioctl(int fd, unsigned long request, void *value) {
    return ioctl(fd, request, value);
}

static bool setup_signals(void) {
    sigemptyset(&forwarded_mask);
    for (size_t i = 0; i < sizeof(pending) / sizeof(pending[0]); ++i) {
        sigaddset(&forwarded_mask, forwarded_signals[i]);
    }
    if (sigprocmask(SIG_BLOCK, &forwarded_mask, &wait_mask) < 0) {
        return false;
    }
    struct sigaction action = {.sa_handler = on_signal, .sa_mask = forwarded_mask};
    for (size_t i = 0; i < sizeof(pending) / sizeof(pending[0]); ++i) {
        sigdelset(&wait_mask, forwarded_signals[i]);
        if (sigaction(forwarded_signals[i], &action, NULL) < 0) {
            return false;
        }
    }
    return true;
}

static void collect_signals(void) {
    sigset_t queued;
    if (sigpending(&queued) < 0) {
        return;
    }
    for (size_t i = 0; i < sizeof(pending) / sizeof(pending[0]); ++i) {
        if (sigismember(&queued, forwarded_signals[i]) == 1) {
            sigprocmask(SIG_UNBLOCK, &forwarded_mask, NULL);
            sigprocmask(SIG_BLOCK, &forwarded_mask, NULL);
            return;
        }
    }
}

long nx_poll(struct pollfd *fds, size_t count) {
    fd_set readable, writable;
    FD_ZERO(&readable);
    FD_ZERO(&writable);
    int limit = 0;
    for (size_t i = 0; i < count; ++i) {
        int fd = fds[i].fd;
        if (fd < 0) {
            continue;
        }
        if (fd >= FD_SETSIZE) {
            return -EINVAL;
        }
        if (fds[i].events & POLLIN) {
            FD_SET(fd, &readable);
        }
        if (fds[i].events & POLLOUT) {
            FD_SET(fd, &writable);
        }
        if (fd >= limit) {
            limit = fd + 1;
        }
    }
    int result = pselect(limit, &readable, &writable, NULL, NULL, &wait_mask);
    if (result < 0) {
        return -errno;
    }
    result = poll(fds, (nfds_t)count, 0);
    return result < 0 ? -errno : result;
}

static int connect_unix(struct sockaddr_un *address) {
    int fd = nx_stream_socket(AF_UNIX);
    if (fd < 0 || connect(fd, (struct sockaddr *)address, sizeof(*address)) < 0) {
        return -1;
    }
    return fd;
}

static bool cwd(char *path, size_t size) {
    return getcwd(path, size) != NULL;
}

#else

#if !defined(__aarch64__) || !defined(__linux__)
#error 独立 shim 需要 AArch64 Linux 目标
#endif
#include <asm/unistd.h>

#define NX_STRINGIFY_IMPL(value) #value
#define NX_STRINGIFY(value) NX_STRINGIFY_IMPL(value)

static inline long syscall6(long nr, long a, long b, long c, long d, long e, long f) {
    register long x8 __asm__("x8") = nr;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    register long x3 __asm__("x3") = d;
    register long x4 __asm__("x4") = e;
    register long x5 __asm__("x5") = f;
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5) : "memory", "cc");
    return x0;
}

static inline long syscall3(long nr, long a, long b, long c) {
    return syscall6(nr, a, b, c, 0, 0, 0);
}

static long shim_ioctl(int fd, unsigned long request, void *value) {
    return syscall3(__NR_ioctl, fd, (long)request, (long)value);
}

bool nx_nonblocking_socket(int fd) {
    long flags = syscall3(__NR_fcntl, fd, F_GETFL, 0);
    return flags >= 0 && syscall3(__NR_fcntl, fd, F_SETFL, flags | O_NONBLOCK) == 0;
}

bool nx_stat(int fd, struct stat *state) {
    return syscall3(__NR_fstat, fd, (long)state, 0) == 0;
}

bool nx_is_tty(int fd) {
    struct termios attributes;
    return shim_ioctl(fd, TCGETS, &attributes) == 0;
}

static long keep_standard_fds(long fd) {
    if (fd >= 0 && fd < 3) {
        long moved = syscall3(__NR_fcntl, fd, F_DUPFD_CLOEXEC, 3);
        syscall3(__NR_close, fd, 0, 0);
        return moved;
    }
    return fd;
}

int nx_open_nonblocking(const char *path, int original) {
    long flags = syscall3(__NR_fcntl, original, F_GETFL, 0);
    if (flags < 0) {
        return -1;
    }
    flags = (flags & O_ACCMODE) | O_NONBLOCK | O_CLOEXEC | O_NOCTTY;
    return (int)keep_standard_fds(syscall6(__NR_openat, AT_FDCWD, (long)path, flags, 0, 0, 0));
}

void nx_close(int fd) {
    syscall3(__NR_close, fd, 0, 0);
}

long nx_read(int fd, void *data, size_t size) {
    return syscall3(__NR_read, fd, (long)data, (long)size);
}

long nx_write(int fd, const void *data, size_t size) {
    return syscall3(__NR_write, fd, (long)data, (long)size);
}

long nx_send(int socket, const void *data, size_t size, bool nonblocking) {
    int flags = MSG_NOSIGNAL | (nonblocking ? MSG_DONTWAIT : 0);
    return syscall6(__NR_sendto, socket, (long)data, (long)size, flags, 0, 0);
}

long nx_receive(int socket, void *data, size_t size) {
    return syscall6(__NR_recvfrom, socket, (long)data, (long)size, MSG_DONTWAIT, 0, 0);
}

static int connect_unix(struct sockaddr_un *address) {
    long fd = keep_standard_fds(syscall3(__NR_socket, AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0));
    if (fd < 0 || syscall3(__NR_connect, fd, (long)address, sizeof(*address)) < 0) {
        return -1;
    }
    return (int)fd;
}

static bool cwd(char *path, size_t size) {
    // Linux 对非当前根之下的目录可返回 "(unreachable)"，只接受桥可翻译的绝对路径。
    return syscall3(__NR_getcwd, (long)path, (long)size, 0) > 0 && path[0] == '/';
}

static uint64_t wait_mask;
static uint64_t forwarded_mask;
static const unsigned long kernel_sigset_size = sizeof(uint64_t);
enum { KERNEL_SA_RESTORER = 0x04000000 };

struct kernel_sigaction {
    void (*handler)(int);
    unsigned long flags;
    void (*restorer)(void);
    uint64_t mask;
};

__attribute__((naked)) static void restore_signal(void) {
    __asm__ volatile("mov x8, #" NX_STRINGIFY(__NR_rt_sigreturn) "\nsvc #0");
}

static bool setup_signals(void) {
    for (size_t i = 0; i < sizeof(pending) / sizeof(pending[0]); ++i) {
        forwarded_mask |= UINT64_C(1) << (forwarded_signals[i] - 1);
    }
    if (syscall6(__NR_rt_sigprocmask, SIG_BLOCK, (long)&forwarded_mask, (long)&wait_mask, kernel_sigset_size, 0, 0) < 0) {
        return false;
    }
    wait_mask &= ~forwarded_mask;
    struct kernel_sigaction action = {on_signal, KERNEL_SA_RESTORER, restore_signal, forwarded_mask};
    for (size_t i = 0; i < sizeof(pending) / sizeof(pending[0]); ++i) {
        if (syscall6(__NR_rt_sigaction, forwarded_signals[i], (long)&action, 0, kernel_sigset_size, 0, 0) < 0) {
            return false;
        }
    }
    return true;
}

static void collect_signals(void) {
    uint64_t queued = 0;
    if (syscall3(__NR_rt_sigpending, (long)&queued, kernel_sigset_size, 0) == 0 && (queued & forwarded_mask)) {
        syscall6(__NR_rt_sigprocmask, SIG_UNBLOCK, (long)&forwarded_mask, 0, kernel_sigset_size, 0, 0);
        syscall6(__NR_rt_sigprocmask, SIG_BLOCK, (long)&forwarded_mask, 0, kernel_sigset_size, 0, 0);
    }
}

long nx_poll(struct pollfd *fds, size_t count) {
    return syscall6(__NR_ppoll, (long)fds, (long)count, 0, (long)&wait_mask, kernel_sigset_size, 0);
}

#endif

enum { SHIM_FAILURE = 126, NOT_AVAILABLE = 127, MAX_REQUEST = 256 * 1024, MAX_CWD = 4096 };

static const char *const supported_tools[] = {
    "git", "rg", "fd", "jq", "sqlite3", "zstd", "bsdtar", "rsync", "ffmpeg", "ffprobe",
};

static const char *const allowed_environment[] = {
    "TERM", "NO_COLOR", "COLORTERM", "CLICOLOR", "CLICOLOR_FORCE", "TZ", "LANG", "LC_ALL",
    "LC_CTYPE", "COLUMNS", "LINES", "GIT_AUTHOR_NAME", "GIT_AUTHOR_EMAIL", "GIT_AUTHOR_DATE",
    "GIT_COMMITTER_NAME", "GIT_COMMITTER_EMAIL", "GIT_COMMITTER_DATE", "GIT_DIR", "GIT_WORK_TREE",
    "GIT_INDEX_FILE", "GIT_OBJECT_DIRECTORY", "GIT_ALTERNATE_OBJECT_DIRECTORIES", "GIT_TERMINAL_PROMPT",
    "GIT_PAGER", "PAGER", "INFINITY_WORKSPACE", "INFINITY_PROXY_HTTP", "INFINITY_PROXY_SOCKS",
    "INFINITY_PROXY_NO_PROXY", "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY",
    "http_proxy", "https_proxy", "all_proxy", "no_proxy",
};

struct terminal_state {
    bool tty[3];
    uint16_t cols;
    uint16_t rows;
};

static struct terminal_state terminal_state(void) {
    struct terminal_state state = {0};
    bool found_size = false;
    for (int fd = 0; fd < 3; ++fd) {
        state.tty[fd] = nx_is_tty(fd);
        if (state.tty[fd] && !found_size) {
            struct winsize dimensions;
            if (shim_ioctl(fd, TIOCGWINSZ, &dimensions) == 0) {
                state.cols = dimensions.ws_col;
                state.rows = dimensions.ws_row;
                found_size = true;
            }
        }
    }
    return state;
}

unsigned nx_pending_control(unsigned char payload[4]) {
    // 描述符持续就绪时 ppoll 可先恢复掩码再回到用户态，主动处理内核待转发信号。
    collect_signals();
    for (size_t i = 0; i < sizeof(pending) / sizeof(pending[0]); ++i) {
        if (!pending[i]) {
            continue;
        }
        pending[i] = 0;
        if (forwarded_signals[i] == SIGWINCH) {
            struct terminal_state state = terminal_state();
            payload[0] = (unsigned char)(state.cols >> 8);
            payload[1] = (unsigned char)state.cols;
            payload[2] = (unsigned char)(state.rows >> 8);
            payload[3] = (unsigned char)state.rows;
            return NX_FRAME_RESIZE;
        }
        nx_be32(payload, (uint32_t)forwarded_signals[i]);
        return NX_FRAME_SIGNAL;
    }
    return 0;
}

static const char *tool_name(const char *arg0) {
    const char *name = arg0;
    for (const char *cursor = arg0; *cursor; ++cursor) {
        if (*cursor == '/') {
            name = cursor + 1;
        }
    }
    for (size_t i = 0; i < sizeof(supported_tools) / sizeof(supported_tools[0]); ++i) {
        if (nx_equal(name, supported_tools[i])) {
            return name;
        }
    }
    return NULL;
}

static void append_env(struct nx_buffer *request, char **env) {
    bool next = false;
    for (size_t i = 0; i < sizeof(allowed_environment) / sizeof(allowed_environment[0]); ++i) {
        const char *key = allowed_environment[i];
        const char *value = nx_env(env, key);
        if (value) {
            if (next) {
                nx_append(request, ",");
            }
            nx_json_string(request, key, nx_length(key));
            nx_append(request, ":");
            nx_json_string(request, value, nx_length(value));
            next = true;
        }
    }
}

static void request_header(struct nx_buffer *request, const char *nonce, const char *tool, const char *path,
                           char **arguments, char **env) {
    struct terminal_state tty = terminal_state();
    nx_append(request, "{\"v\":1,\"nonce\":");
    nx_json_string(request, nonce, nx_length(nonce));
    nx_append(request, ",\"tool\":");
    nx_json_string(request, tool, nx_length(tool));
    nx_append(request, ",\"cwd\":");
    nx_json_string(request, path, nx_length(path));
    nx_append(request, ",\"argv\":[");
    for (char **arg = arguments; *arg; ++arg) {
        if (arg != arguments) {
            nx_append(request, ",");
        }
        nx_json_string(request, *arg, nx_length(*arg));
    }
    nx_append(request, "],\"env\":{");
    append_env(request, env);
    nx_append(request, "},\"tty\":{\"in\":");
    nx_append(request, tty.tty[0] ? "true" : "false");
    nx_append(request, ",\"out\":");
    nx_append(request, tty.tty[1] ? "true" : "false");
    nx_append(request, ",\"err\":");
    nx_append(request, tty.tty[2] ? "true" : "false");
    nx_append(request, ",\"cols\":");
    nx_number(request, tty.cols);
    nx_append(request, ",\"rows\":");
    nx_number(request, tty.rows);
    nx_append(request, "}}\n");
}

static int shim_main(int argc, char **argv, char **env) {
    const char *nonce = nx_env(env, "INFINITY_TOOL_BRIDGE_NONCE");
    if (!nonce || !*nonce) {
        nx_error("缺少 INFINITY_TOOL_BRIDGE_NONCE，无法连接宿主工具桥。");
        return NOT_AVAILABLE;
    }
    const char *tool = argc ? tool_name(argv[0]) : NULL;
    if (!tool) {
        nx_error("工具名称不受宿主工具桥支持。");
        return NOT_AVAILABLE;
    }
    const char *socket_path = nx_env(env, "INFINITY_TOOL_BRIDGE_SOCKET");
    if (!socket_path || !*socket_path) {
        socket_path = "/run/infinity/bridge/tools.sock";
    }
    struct sockaddr_un address;
    size_t socket_length = nx_length(socket_path);
    if (socket_length >= sizeof(address.sun_path)) {
        nx_error("宿主工具桥 socket 路径过长。");
        return SHIM_FAILURE;
    }
#if defined(NX_SHIM_HOST_TEST) && defined(__APPLE__)
    address.sun_len = sizeof(address);
#endif
    address.sun_family = AF_UNIX;
    for (size_t i = 0; i < sizeof(address.sun_path); ++i) {
        address.sun_path[i] = i < socket_length ? socket_path[i] : '\0';
    }
    char path[MAX_CWD];
    if (!cwd(path, sizeof(path))) {
        nx_error("无法读取宿主工具的当前目录。");
        return SHIM_FAILURE;
    }
    static char bytes[MAX_REQUEST];
    struct nx_buffer request = {bytes, sizeof(bytes), 0, false};
    request_header(&request, nonce, tool, path, argv + 1, env);
    if (request.invalid) {
        nx_error("宿主工具请求超过 256 KiB 或包含无效 UTF-8。");
        return SHIM_FAILURE;
    }
    int fd = connect_unix(&address);
    if (fd < 0 || !nx_send_header(fd, &request)) {
        nx_error("无法连接宿主工具桥或发送请求。");
        return SHIM_FAILURE;
    }
    if (!nx_accept_reply(fd)) {
        return SHIM_FAILURE;
    }
    if (!setup_signals()) {
        nx_error("无法注册宿主工具信号转发。");
        return SHIM_FAILURE;
    }
    return nx_relay(fd, SHIM_FAILURE, true);
}

#ifdef NX_SHIM_HOST_TEST
int main(int argc, char **argv, char **env) {
    return shim_main(argc, argv, env);
}
#else
__attribute__((used, noreturn)) static void nx_shim_start(unsigned long *stack) {
    int argc = (int)stack[0];
    char **argv = (char **)(stack + 1);
    char **env = argv + argc + 1;
    syscall3(__NR_exit_group, shim_main(argc, argv, env), 0, 0);
    __builtin_unreachable();
}

__attribute__((naked, noreturn)) void _start(void) {
    __asm__ volatile("mov x0, sp\nb nx_shim_start");
}
#endif
