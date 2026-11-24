#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <errno.h>
#include <sched.h>
#include <stdlib.h>
#include <sys/resource.h>
#include <unistd.h>
#include <signal.h>
#include <string.h>
#include <sys/prctl.h>

int nx_proot_main(int argc, char *const argv[]);

static long consume_positive_long(const char *name) {
    const char *value = getenv(name);
    char *end;
    long number = 0;
    if (value != NULL && value[0] >= '0' && value[0] <= '9') {
        errno = 0;
        number = strtol(value, &end, 10);
        if (errno != 0 || *end != '\0' || number <= 0) number = 0;
    }
    unsetenv(name);
    return number;
}

static int consume_cpu_list(cpu_set_t *set) {
    const char *value = getenv("NX_PROOT_CPU_LIST");
    int valid = 0;
    CPU_ZERO(set);
    while (value != NULL && *value >= '0' && *value <= '9') {
        char *end;
        errno = 0;
        long cpu = strtol(value, &end, 10);
        if (errno != 0 || cpu < 0 || cpu >= CPU_SETSIZE) break;
        CPU_SET((int)cpu, set);
        if (*end == '\0') {
            valid = 1;
            break;
        }
        if (*end != ',') break;
        value = end + 1;
    }
    unsetenv("NX_PROOT_CPU_LIST");
    return valid;
}

static void apply_resource_limits(void) {
    long nice_value = consume_positive_long("NX_PROOT_NICE");
    long nofile = consume_positive_long("NX_PROOT_NOFILE");
    const char *core = getenv("NX_PROOT_DISABLE_CORE");
    int disable_core = core != NULL && strcmp(core, "1") == 0;
    unsetenv("NX_PROOT_DISABLE_CORE");
    cpu_set_t cpus;
    int has_cpu_list = consume_cpu_list(&cpus);

    if (nice_value > 0 && nice_value <= 19) setpriority(PRIO_PROCESS, 0, (int)nice_value);
    if (has_cpu_list) sched_setaffinity(0, sizeof(cpus), &cpus);
    if (nofile > 0) {
        struct rlimit limit;
        if (getrlimit(RLIMIT_NOFILE, &limit) == 0) {
            /* 继承更严格的限制，避免 guest 将软上限重新升高。 */
            if ((rlim_t)nofile < limit.rlim_max) limit.rlim_max = (rlim_t)nofile;
            if (limit.rlim_cur > limit.rlim_max) limit.rlim_cur = limit.rlim_max;
            setrlimit(RLIMIT_NOFILE, &limit);
        }
    }
    if (disable_core) {
        const struct rlimit disabled = {0, 0};
        setrlimit(RLIMIT_CORE, &disabled);
    }
}

/* 进程组由宿主 setsid 建立；主进程退出时终止 tracer，不写共享 pid 文件。 */
int main(int argc, char *const argv[]) {
    if (argc == 2 && strcmp(argv[1], "--shm-helper") == 0) return nx_proot_main(argc, argv);
    pid_t parent = getppid();
    if (parent <= 1 || prctl(PR_SET_PDEATHSIG, SIGKILL) < 0 || getppid() != parent) return 125;
    apply_resource_limits();
    return nx_proot_main(argc, argv);
}
