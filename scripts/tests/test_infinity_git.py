"""内置 git 包装（native-ui/app/python/site/infinity_git.py）的测试。

改写与输出整理逻辑不依赖 dulwich；clone/fetch 抓取范围与工作区类型的端到端测试需要 dulwich，
通过环境变量 INFINITY_DULWICH_PATH（解压后的 dulwich 与 urllib3 wheel 所在目录，可用 os.pathsep 分隔多个）
或已安装的 dulwich 导入，导入失败时跳过。远端为临时目录中构造的本地仓库，不访问网络；
HTTPS 凭据代理与 SSH 桥用本机模拟服务代替 App 侧实现。
"""

from __future__ import annotations

import importlib.util
import io
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest
import unittest.mock

SITE_DIR = Path(__file__).resolve().parents[2] / "native-ui/app/python/site"
sys.path.insert(0, str(SITE_DIR))
MODULE_PATH = SITE_DIR / "infinity_git.py"
spec = importlib.util.spec_from_file_location("infinity_git", MODULE_PATH)
infinity_git = importlib.util.module_from_spec(spec)
spec.loader.exec_module(infinity_git)


class RewriteTest(unittest.TestCase):
    def test_proxy_headers_are_http_tokens(self) -> None:
        for name in (infinity_git.NONCE_HEADER, infinity_git.PROXY_ERROR_HEADER):
            self.assertRegex(name, r"^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")

    def test_upstream_auth_failure_from_proxy_keeps_its_guidance(self) -> None:
        class Resp:
            def __init__(self, code, body):
                self.status = 401
                self.headers = {infinity_git.PROXY_ERROR_HEADER: code}
                self._body = body.encode("utf-8")

            def read(self, size):
                return self._body[:size]

        guidance = "GitHub 授权已失效（HTTP 401）。请让用户重新登录 GitHub。"
        failure = infinity_git._proxy_failure(Resp("CREDENTIAL_REJECTED", guidance + "\n"), "https://github.com/o/r.git", "github.com")
        self.assertEqual((failure.code, failure.message), ("CREDENTIAL_REJECTED", guidance))
        failure = infinity_git._proxy_failure(Resp("NONCE_REJECTED", "nonce 无效"), "https://github.com/o/r.git", "github.com")
        self.assertEqual(failure.message, "Git 凭据代理拒绝请求：nonce 无效")

    def test_log_count_shorthand_becomes_max_count(self) -> None:
        self.assertEqual(infinity_git._rewrite("log", ["--oneline", "-5", "src"]), (["--oneline", "-n", "5", "src"], None))
        self.assertEqual(infinity_git._rewrite("log", ["-n", "3"]), (["-n", "3"], None))

    def test_branch_short_listing_options(self) -> None:
        self.assertEqual(infinity_git._rewrite("branch", ["-a"]), (["--all"], None))
        self.assertEqual(infinity_git._rewrite("branch", ["-r"]), (["--remotes"], None))

    def test_add_all_maps_to_dot(self) -> None:
        self.assertEqual(infinity_git._rewrite("add", ["-A"]), (["."], None))

    def test_commit_requires_message(self) -> None:
        rewritten, problem = infinity_git._rewrite("commit", ["-a"])
        self.assertIsNone(rewritten)
        self.assertIn("-m", problem)
        self.assertEqual(infinity_git._rewrite("commit", ["-am", "msg"]), (["-am", "msg"], None))

    def test_ssh_urls_are_detected(self) -> None:
        for url in ("git@github.com:a/b.git", "ssh://git@host/r.git"):
            self.assertTrue(infinity_git._ssh_url(url), url)
        self.assertFalse(infinity_git._ssh_url("https://github.com/a/b.git"))


class ProgressCollapserTest(unittest.TestCase):
    def collapse(self, *chunks: bytes) -> bytes:
        sink = io.BytesIO()
        collapser = infinity_git._ProgressCollapser(sink)
        for chunk in chunks:
            collapser.write(chunk)
        collapser.finish()
        return sink.getvalue()

    def test_keeps_only_final_state_of_carriage_return_updates(self) -> None:
        self.assertEqual(
            self.collapse(b"Counting: 1%\rCounting: 50%\rCounting: 100%, done.\nnext\n"),
            b"Counting: 100%, done.\nnext\n",
        )

    def test_crlf_split_across_writes_is_a_newline(self) -> None:
        self.assertEqual(self.collapse(b"line\r", b"\nafter\n"), b"line\nafter\n")

    def test_trailing_partial_line_is_flushed(self) -> None:
        self.assertEqual(self.collapse(b"writing 0/3\rwriting 3/3"), b"writing 3/3\n")


class ClonePolicyTest(unittest.TestCase):
    def test_depth_implies_single_branch_with_tag_follow(self) -> None:
        policy = infinity_git._clone_policy(["--depth", "1", "https://example.com/r.git"])
        self.assertTrue(policy.single_branch)
        self.assertIsNone(policy.branches)
        self.assertEqual(policy.tags, "follow")
        self.assertTrue(infinity_git._clone_policy(["--depth=3", "u"]).single_branch)

    def test_explicit_single_branch_options(self) -> None:
        policy = infinity_git._clone_policy(["--single-branch", "-b", "dev", "u"])
        self.assertEqual((policy.single_branch, policy.branches), (True, [b"dev"]))
        policy = infinity_git._clone_policy(["--depth", "1", "--no-single-branch", "u"])
        self.assertEqual((policy.single_branch, policy.tags), (False, "all"))
        self.assertEqual(infinity_git._clone_policy(["--depth", "1", "--no-tags", "u"]).tags, "none")

    def test_plain_clone_takes_all_branches_and_tags(self) -> None:
        policy = infinity_git._clone_policy(["--branch=dev", "u"])
        self.assertEqual((policy.single_branch, policy.branches, policy.tags), (False, None, "all"))

    def test_wrapper_only_options_are_not_passed_to_dulwich(self) -> None:
        rewritten, problem = infinity_git._rewrite(
            "clone", ["--depth", "1", "--single-branch", "--no-tags", "--no-single-branch", "https://example.com/r.git"]
        )
        self.assertIsNone(problem)
        self.assertEqual(rewritten, ["--depth", "1", "https://example.com/r.git"])

    def test_fetch_location_skips_option_values(self) -> None:
        self.assertEqual(infinity_git._fetch_location(["--depth", "2", "upstream"]), "upstream")
        self.assertIsNone(infinity_git._fetch_location(["--no-tags", "--depth", "2"]))

    def test_usage_lists_clone_scope_options(self) -> None:
        usage = infinity_git._usage()
        for option in ("--single-branch", "--no-single-branch", "--no-tags", "core.symlinks=false"):
            self.assertIn(option, usage)


def _load_dulwich() -> str:
    """把 INFINITY_DULWICH_PATH 加入 sys.path 后导入 dulwich，返回其所在目录（供子进程的 PYTHONPATH）。"""
    for entry in reversed([part for part in os.environ.get("INFINITY_DULWICH_PATH", "").split(os.pathsep) if part]):
        if entry not in sys.path:
            sys.path.insert(0, entry)
    import dulwich  # noqa: F401  导入失败由调用方转为 skipTest

    return str(Path(dulwich.__file__).resolve().parents[1])


SIGNATURE = b"Test <test@example.com>"


class _RemoteBuilder:
    """直接写对象构造远端，以便在大小写不敏感的文件系统上也能放入仅大小写不同的两个路径。"""

    def __init__(self, path: Path) -> None:
        from dulwich.repo import Repo

        self.repo = Repo.init_bare(str(path), mkdir=True)
        self.store = self.repo.object_store
        self.clock = 1_700_000_000

    def blob(self, data: bytes) -> bytes:
        from dulwich.objects import Blob

        item = Blob.from_string(data)
        self.store.add_object(item)
        return item.id

    def tree(self, entries: dict) -> bytes:
        from dulwich.objects import Tree

        item = Tree()
        for name, (mode, sha) in entries.items():
            item.add(name, mode, sha)
        self.store.add_object(item)
        return item.id

    def commit(self, readme: bytes, parents: list, extra: dict | None = None) -> bytes:
        from dulwich.objects import Commit

        docs = self.tree({
            b"Guide.txt": (0o100644, self.blob(b"upper\n")),
            b"guide.txt": (0o100644, self.blob(b"lower\n")),
        })
        entries = {
            b"README": (0o100644, self.blob(readme)),
            b"link": (0o120000, self.blob(b"README")),
            b"docs": (stat.S_IFDIR, docs),
        }
        entries.update(extra or {})
        self.clock += 60
        item = Commit()
        item.tree = self.tree(entries)
        item.parents = parents
        item.author = item.committer = SIGNATURE
        item.author_time = item.commit_time = self.clock
        item.author_timezone = item.commit_timezone = 0
        item.message = readme
        self.store.add_object(item)
        return item.id

    def annotated(self, name: bytes, target: bytes) -> bytes:
        from dulwich.objects import Commit, Tag

        self.clock += 1
        item = Tag()
        item.name = name
        item.object = (Commit, target)
        item.tagger = SIGNATURE
        item.tag_time = self.clock
        item.tag_timezone = 0
        item.message = name + b"\n"
        self.store.add_object(item)
        self.repo.refs[b"refs/tags/" + name] = item.id
        return item.id


class CloneFetchScopeTest(unittest.TestCase):
    """经 python3 -m infinity_git 跑 clone/fetch，断言抓取的引用、配置与提示。"""

    def setUp(self) -> None:
        try:
            self.dulwich_root = _load_dulwich()
        except ImportError:
            self.skipTest("未找到 dulwich：设置 INFINITY_DULWICH_PATH 指向解压后的 dulwich 与 urllib3 wheel 目录")
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name).resolve()
        (self.root / "home").mkdir()
        self.remote = self.root / "remote.git"
        self.build_remote()
        self.remote_url = str(self.remote)

    def build_remote(self) -> None:
        remote = _RemoteBuilder(self.remote)
        self.builder = remote
        m1 = remote.commit(b"main 1\n", [])
        m2 = remote.commit(b"main 2\n", [m1])
        m3 = remote.commit(b"main 3\n", [m2])
        f1 = remote.commit(b"feature 1\n", [m1])
        p1 = remote.commit(b"pull 1\n", [m1])
        refs = remote.repo.refs
        refs[b"refs/heads/main"] = m3
        refs[b"refs/heads/feature"] = f1
        refs[b"refs/pull/1/head"] = p1
        refs.set_symbolic_ref(b"HEAD", b"refs/heads/main")
        remote.annotated(b"v1", m1)
        refs[b"refs/tags/v2-light"] = m2
        remote.annotated(b"v3", m3)
        remote.annotated(b"feat-annotated", f1)
        refs[b"refs/tags/feat-light"] = f1
        self.commits = {"m1": m1, "m2": m2, "m3": m3, "f1": f1, "p1": p1}

    def git(self, *args: str, cwd: Path | None = None, kind: str | None = "project") -> subprocess.CompletedProcess:
        env = {
            "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
            "HOME": str(self.root / "home"),
            "LANG": "C.UTF-8",
            "PYTHONPATH": os.pathsep.join([str(SITE_DIR), self.dulwich_root]),
            "GIT_CONFIG_NOSYSTEM": "1",
        }
        if kind is not None:
            env["INFINITY_WORKSPACE_KIND"] = kind
        return subprocess.run(
            [sys.executable, "-m", "infinity_git", *args],
            cwd=str(cwd or self.root), env=env, capture_output=True, text=True, timeout=120,
        )

    def clone(self, *options: str, name: str = "work", kind: str | None = "project") -> Path:
        result = self.git("clone", *options, self.remote_url, name, kind=kind)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.last_stderr = result.stderr
        return self.root / name

    def refs_of(self, path: Path) -> tuple[set, set]:
        from dulwich.repo import Repo

        with Repo(str(path)) as repo:
            names = set(repo.refs.allkeys())
        remotes = {name.decode()[len("refs/remotes/origin/"):] for name in names if name.startswith(b"refs/remotes/origin/")}
        tags = {name.decode()[len("refs/tags/"):] for name in names if name.startswith(b"refs/tags/")}
        return remotes - {"HEAD"}, tags

    def has_object(self, path: Path, key: str) -> bool:
        from dulwich.repo import Repo

        with Repo(str(path)) as repo:
            return self.commits[key] in repo.object_store

    def config(self, path: Path, section: tuple, name: bytes) -> bytes | None:
        from dulwich.repo import Repo

        with Repo(str(path)) as repo:
            try:
                return repo.get_config().get(section, name)
            except KeyError:
                return None

    def test_plain_clone_takes_all_branches_and_tags_but_not_pull_refs(self) -> None:
        work = self.clone()
        self.assertEqual(self.refs_of(work), ({"main", "feature"}, {"v1", "v2-light", "v3", "feat-annotated", "feat-light"}))
        self.assertFalse(self.has_object(work, "p1"))
        self.assertEqual(self.config(work, (b"remote", b"origin"), b"fetch"), b"+refs/heads/*:refs/remotes/origin/*")

    def test_depth_one_takes_default_branch_and_tip_tag_only(self) -> None:
        work = self.clone("--depth", "1")
        self.assertEqual(self.refs_of(work), ({"main"}, {"v3"}))
        self.assertTrue(self.has_object(work, "m3"))
        for key in ("m2", "f1", "p1"):
            self.assertFalse(self.has_object(work, key), key)
        self.assertEqual(self.config(work, (b"remote", b"origin"), b"fetch"), b"+refs/heads/main:refs/remotes/origin/main")
        self.assertEqual(self.config(work, (b"branch", b"main"), b"merge"), b"refs/heads/main")
        self.assertEqual((work / "README").read_text(), "main 3\n")
        log = self.git("log", "--oneline", cwd=work)
        self.assertEqual(log.returncode, 0, log.stderr)
        self.assertIn("main 3", log.stdout)
        status = self.git("status", cwd=work)
        self.assertEqual(status.returncode, 0, status.stderr)

    def test_single_branch_follows_tags_reachable_from_branch(self) -> None:
        work = self.clone("--single-branch")
        self.assertEqual(self.refs_of(work), ({"main"}, {"v1", "v2-light", "v3"}))
        self.assertFalse(self.has_object(work, "f1"))

    def test_single_branch_with_branch_option(self) -> None:
        work = self.clone("--single-branch", "-b", "feature")
        self.assertEqual(self.refs_of(work), ({"feature"}, {"v1", "feat-annotated", "feat-light"}))
        self.assertEqual((work / "README").read_text(), "feature 1\n")
        from dulwich.repo import Repo

        with Repo(str(work)) as repo:
            self.assertEqual(repo.refs.read_ref(b"HEAD"), b"ref: refs/heads/feature")

    def test_missing_branch_fails_like_git(self) -> None:
        result = self.git("clone", "--depth", "1", "-b", "nope", self.remote_url, "work")
        self.assertEqual(result.returncode, 128)
        self.assertIn("Remote branch nope not found", result.stderr)
        self.assertFalse((self.root / "work").exists())

    def test_no_tags(self) -> None:
        work = self.clone("--depth", "1", "--no-tags")
        self.assertEqual(self.refs_of(work), ({"main"}, set()))
        self.assertEqual(self.config(work, (b"remote", b"origin"), b"tagOpt"), b"--no-tags")
        work = self.clone("--no-tags", name="all-branches")
        self.assertEqual(self.refs_of(work), ({"main", "feature"}, set()))

    def test_no_single_branch_restores_all_branches_and_tags(self) -> None:
        work = self.clone("--depth", "1", "--no-single-branch")
        self.assertEqual(self.refs_of(work), ({"main", "feature"}, {"v1", "v2-light", "v3", "feat-annotated", "feat-light"}))

    # 单分支克隆后再 fetch 的克隆选项与 fetch 后应有的标签。
    fetch_clone_options = ("--depth", "1")
    fetch_expected_tags = {"v3", "v4"}

    def test_fetch_after_single_branch_clone_stays_on_branch(self) -> None:
        work = self.clone(*self.fetch_clone_options)
        remote = self.builder
        m4 = remote.commit(b"main 4\n", [self.commits["m3"]])
        f2 = remote.commit(b"feature 2\n", [self.commits["f1"]])
        remote.repo.refs[b"refs/heads/main"] = m4
        remote.repo.refs[b"refs/heads/feature"] = f2
        remote.repo.refs[b"refs/heads/new-branch"] = f2
        remote.annotated(b"v4", m4)
        remote.annotated(b"feat-2", f2)
        result = self.git("fetch", cwd=work)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.refs_of(work), ({"main"}, self.fetch_expected_tags))
        from dulwich.repo import Repo

        with Repo(str(work)) as repo:
            self.assertEqual(repo.refs[b"refs/remotes/origin/main"], m4)
            self.assertNotIn(f2, repo.object_store)
        self.assertEqual(self.git("log", "--oneline", cwd=work).returncode, 0)

    def test_fetch_in_full_clone_takes_new_branches(self) -> None:
        work = self.clone()
        f2 = self.builder.commit(b"feature 2\n", [self.commits["f1"]])
        self.builder.repo.refs[b"refs/heads/new-branch"] = f2
        result = self.git("fetch", "origin", "--no-tags", cwd=work)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("new-branch", self.refs_of(work)[0])

    def test_private_clone_prints_backup_hint(self) -> None:
        self.clone("--depth", "1", kind="private")
        self.assertIn("私有工作区", self.last_stderr)
        self.assertIn("备份", self.last_stderr)
        self.clone("--depth", "1", name="missing-kind", kind=None)
        self.assertIn("私有工作区", self.last_stderr)
        self.clone("--depth", "1", name="project", kind="project")
        self.assertNotIn("私有工作区", self.last_stderr)

    def test_shared_clone_writes_config_before_checkout(self) -> None:
        work = self.clone("--depth", "1", kind="shared")
        for name, value in ((b"symlinks", b"false"), (b"filemode", b"false"), (b"ignorecase", b"true")):
            self.assertEqual(self.config(work, (b"core",), name), value, name)
        link = work / "link"
        self.assertFalse(link.is_symlink())
        self.assertEqual(link.read_text(), "README")
        self.assertIn("符号链接", self.last_stderr)
        self.assertIn("link", self.last_stderr)
        self.assertIn("大小写", self.last_stderr)
        self.assertIn("docs/Guide.txt 与 docs/guide.txt", self.last_stderr)
        self.assertNotIn("私有工作区", self.last_stderr)
        status = self.git("status", cwd=work, kind="shared")
        self.assertEqual(status.returncode, 0, status.stderr)

    def test_project_clone_keeps_symlink(self) -> None:
        work = self.clone("--depth", "1")
        self.assertTrue((work / "link").is_symlink())
        self.assertNotIn("符号链接", self.last_stderr)

    def test_shared_init_and_existing_repo_fill_missing_config(self) -> None:
        result = self.git("init", "fresh", kind="shared")
        self.assertEqual(result.returncode, 0, result.stderr)
        fresh = self.root / "fresh"
        self.assertEqual(self.config(fresh, (b"core",), b"filemode"), b"false")
        self.assertEqual(self.config(fresh, (b"core",), b"ignorecase"), b"true")
        self.assertEqual(self.config(fresh, (b"core",), b"symlinks"), b"false")

        self.assertEqual(self.git("init", "existing").returncode, 0)
        existing = self.root / "existing"
        from dulwich.repo import Repo

        with Repo(str(existing)) as repo:
            config = repo.get_config()
            config.set((b"core",), b"symlinks", b"true")
            config.write_to_path()
        result = self.git("status", cwd=existing, kind="shared")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.config(existing, (b"core",), b"symlinks"), b"true")  # 显式设置不覆盖
        self.assertEqual(self.config(existing, (b"core",), b"ignorecase"), b"true")
        self.assertIsNotNone(self.config(existing, (b"core",), b"filemode"))


class HttpCloneFetchScopeTest(CloneFetchScopeTest):
    """同一组用例改经 127.0.0.1 上的 dulwich 智能 HTTP 服务跑，覆盖 HTTP 客户端的 ref_prefix、剥离标签与二次抓取路径。

    dulwich 自带服务端只支持协议 v0，并要求客户端在 shallow 行之后发送 deepen
    （server.UploadPackHandler._handle_shallow_request），向浅仓库做不带 --depth 的 fetch 会返回 500；
    git 的 upload-pack 没有这一限制。因此这里的“克隆后 fetch”用不带 --depth 的单分支克隆。
    """

    fetch_clone_options = ("--single-branch",)
    fetch_expected_tags = {"v1", "v2-light", "v3", "v4"}

    def setUp(self) -> None:
        super().setUp()
        import threading
        from wsgiref.simple_server import WSGIRequestHandler, make_server

        from dulwich.repo import Repo
        from dulwich.server import DictBackend
        from dulwich.web import make_wsgi_chain

        class QuietHandler(WSGIRequestHandler):
            def log_message(self, *args) -> None:
                pass

        served = Repo(str(self.remote))
        self.addCleanup(served.close)
        # dulwich 服务端只在后端对象带 .repo 属性时才响应 include-tag（server.UploadPackHandler.get_tagged），
        # 补上后与 git upload-pack 一样随包附上指向所发提交的附注标签。
        served.repo = served
        self.served = served
        server = make_server("127.0.0.1", 0, make_wsgi_chain(DictBackend({"/": served})), handler_class=QuietHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        self.remote_url = "http://127.0.0.1:%d/" % server.server_port

    def test_plain_clone_takes_all_branches_and_tags_but_not_pull_refs(self) -> None:
        work = self.clone()
        self.assertEqual(self.refs_of(work), ({"main", "feature"}, {"v1", "v2-light", "v3", "feat-annotated", "feat-light"}))
        self.assertFalse(self.has_object(work, "p1"))

    def test_tag_follow_second_fetch_sends_only_tag_objects(self) -> None:
        # 不响应 include-tag 的服务端（dulwich 在协议 v2 也不请求 include-tag）走第二次抓取补齐附注标签。
        del self.served.repo
        work = self.clone("--single-branch", "-b", "feature")
        self.assertEqual(self.refs_of(work), ({"feature"}, {"v1", "feat-annotated", "feat-light"}))
        from dulwich.objects import Tag
        from dulwich.repo import Repo

        with Repo(str(work)) as repo:
            packs = [list(pack) for pack in repo.object_store.packs]
            tag_packs = [ids for ids in packs if all(isinstance(repo[sha], Tag) for sha in ids)]
            self.assertEqual([len(ids) for ids in tag_packs], [2], packs)


class InspectUsageTest(unittest.TestCase):
    """检查入口是 App 内部接口，不出现在给模型看的用法与支持列表中。"""

    def test_inspect_entry_is_hidden_from_model(self) -> None:
        self.assertNotIn("inspect", infinity_git._usage())
        self.assertNotIn("--infinity-inspect", infinity_git.SUPPORTED)

    def test_argument_errors_exit_2_without_output(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "out.json"
            for argv in (
                ["status"],
                ["bogus", "--output", str(output)],
                ["read", "--output", str(output)],
                ["commit", "--output", str(output)],
                ["log", "--limit", "0", "--output", str(output)],
                ["branches", "--limit", "0", "--output", str(output)],
            ):
                stderr = io.StringIO()
                original = sys.stderr
                sys.stderr = stderr
                try:
                    code = infinity_git.main(["--infinity-inspect", *argv])
                finally:
                    sys.stderr = original
                self.assertEqual(code, 2, argv)
                self.assertIn("--infinity-inspect", stderr.getvalue())
                self.assertFalse(output.exists(), argv)

    def test_tree_path_rejects_escape(self) -> None:
        for path in ("", "/etc/passwd", "../x", "a/../b", "a//b", "a/./b", "a\\b", "a\0b", "a/"):
            with self.assertRaises(infinity_git._InspectError, msg=path):
                infinity_git._tree_path(path)
        self.assertEqual(infinity_git._tree_path("src/中文.txt"), "src/中文.txt".encode())


class WorktreeInspectPathMappingTest(unittest.TestCase):
    """只构造指针与空元数据目录，不创建 Git 仓库或执行 Git。"""

    def setUp(self) -> None:
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.rootfs = Path(temp.name).resolve() / "rootfs"
        self.work = self.rootfs / "root/branch"
        self.control = self.rootfs / "root/repo/.git/worktrees/branch"
        self.common = self.rootfs / "root/repo/.git"
        self.work.mkdir(parents=True)
        self.control.mkdir(parents=True)
        (self.work / ".git").write_text("gitdir: /root/repo/.git/worktrees/branch\n")
        (self.control / "commondir").write_text("../..\n")
        self.mapping = {"/": str(self.rootfs)}

    def test_linux_absolute_git_pointer_and_relative_common_directory(self) -> None:
        args = infinity_git._inspect_mapped_control_paths(str(self.work), self.mapping)
        self.assertEqual(args, {"controldir": str(self.control), "commondir": str(self.common), "worktree": str(self.work)})

    def test_relative_git_pointer_is_resolved_inside_mapping(self) -> None:
        (self.work / ".git").write_text("gitdir: ../repo/.git/worktrees/branch\n")
        self.assertEqual(infinity_git._inspect_mapped_control_paths(str(self.work), self.mapping)["controldir"], str(self.control))

    def test_inspect_opener_passes_mapped_paths_to_dulwich_without_rewriting_pointer(self) -> None:
        import json
        import types

        repo = unittest.mock.Mock(bare=False)
        factory = unittest.mock.Mock(return_value=repo)
        modules = {"dulwich.repo": types.SimpleNamespace(Repo=factory),
                   "dulwich.errors": types.SimpleNamespace(NotGitRepository=type("NotGitRepository", (Exception,), {}))}
        pointer = (self.work / ".git").read_bytes()
        with unittest.mock.patch.dict(sys.modules, modules), unittest.mock.patch.dict(os.environ, {
            "INFINITY_GIT_LINUX_PATH_MAP": json.dumps(self.mapping), "INFINITY_WORKSPACE_KIND": "project",
        }):
            self.assertIs(infinity_git._inspect_open(str(self.work)), repo)
        factory.assert_called_once_with(str(self.work), controldir=str(self.control), commondir=str(self.common), worktree=str(self.work))
        self.assertEqual((self.work / ".git").read_bytes(), pointer)

    def test_missing_mapping_or_unmapped_mount_fails_closed(self) -> None:
        with self.assertRaises(infinity_git._InspectError):
            infinity_git._inspect_mapped_control_paths(str(self.work), {})
        (self.work / ".git").write_text("gitdir: /workspace/repo/.git/worktrees/branch\n")
        with self.assertRaises(infinity_git._InspectError):
            infinity_git._inspect_mapped_control_paths(str(self.work), self.mapping)

    def test_mapped_mount_uses_explicit_host_root(self) -> None:
        (self.work / ".git").write_text("gitdir: /workspace/.git/worktrees/branch\n")
        mapping = {**self.mapping, "/workspace": str(self.rootfs / "root/repo")}
        self.assertEqual(infinity_git._inspect_mapped_control_paths(str(self.work), mapping)["controldir"], str(self.control))

    def test_relative_escape_and_symlink_are_rejected(self) -> None:
        (self.work / ".git").write_text("gitdir: ../../../../outside\n")
        with self.assertRaises(infinity_git._InspectError):
            infinity_git._inspect_mapped_control_paths(str(self.work), self.mapping)
        (self.work / ".git").write_text("gitdir: /root/link\n")
        (self.rootfs / "root/link").symlink_to(self.control, target_is_directory=True)
        with self.assertRaises(infinity_git._InspectError):
            infinity_git._inspect_mapped_control_paths(str(self.work), self.mapping)

    def test_unmapped_absolute_common_and_object_alternates_are_rejected(self) -> None:
        (self.control / "commondir").write_text("/mnt/foreign/.git\n")
        with self.assertRaises(infinity_git._InspectError):
            infinity_git._inspect_mapped_control_paths(str(self.work), self.mapping)
        (self.control / "commondir").write_text("../..\n")
        alternate = self.common / "objects/info/alternates"
        alternate.parent.mkdir(parents=True)
        alternate.write_text("/host/foreign/objects\n")
        with self.assertRaises(infinity_git._InspectError):
            infinity_git._inspect_mapped_control_paths(str(self.work), self.mapping)


class MidxOffsetTest(unittest.TestCase):
    """多包索引的对象偏移按 git 的规则读取：只有存在 LOFF 表时最高位才是大偏移下标。"""

    def setUp(self) -> None:
        try:
            _load_dulwich()
        except ImportError:
            self.skipTest("未找到 dulwich：设置 INFINITY_DULWICH_PATH 指向解压后的 dulwich 与 urllib3 wheel 目录")
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.path = Path(temp.name) / "multi-pack-index"
        infinity_git._patch_midx_offsets()

    def write(self, offset: int):
        from dulwich import midx

        midx.write_midx_file(str(self.path), [("pack-a.idx", [(b"\x11" * 20, offset, None)])])
        return midx

    def test_offset_between_2_and_4_gib_without_loff_is_read_as_is(self) -> None:
        midx = self.write(0x10)
        index = midx.load_midx(str(self.path))
        position = index._ooff_offset + 4
        index.close()
        data = bytearray(self.path.read_bytes())
        # git 写 2–4 GiB 的偏移时不建 LOFF，直接存 32 位值，最高位为 1。
        data[position:position + 4] = (0xDA77F000).to_bytes(4, "big")
        self.path.write_bytes(bytes(data))
        index = midx.load_midx(str(self.path))
        self.addCleanup(index.close)
        self.assertNotIn(midx.CHUNK_LOFF, index._chunks)
        self.assertEqual(index._get_pack_info(0), (0, 0xDA77F000))

    def test_offset_in_loff_table_still_resolves(self) -> None:
        midx = self.write(0x1_2345_6789)
        index = midx.load_midx(str(self.path))
        self.addCleanup(index.close)
        self.assertIn(midx.CHUNK_LOFF, index._chunks)
        self.assertEqual(index._get_pack_info(0), (0, 0x1_2345_6789))


class InspectTest(unittest.TestCase):
    """经 python -m infinity_git --infinity-inspect 读取状态、内容与历史，断言 JSON 结构且不改动仓库。"""

    def setUp(self) -> None:
        try:
            self.dulwich_root = _load_dulwich()
        except ImportError:
            self.skipTest("未找到 dulwich：设置 INFINITY_DULWICH_PATH 指向解压后的 dulwich 与 urllib3 wheel 目录")
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name).resolve()
        (self.root / "home").mkdir()
        self.work = self.root / "work"
        self.work.mkdir()
        self.clock = 1_700_000_000
        self.outputs = 0

    # —— 构造仓库 ——
    def init(self) -> None:
        from dulwich import porcelain

        porcelain.init(str(self.work)).close()

    def write(self, name: str, data: bytes | str) -> Path:
        target = self.work / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data.encode() if isinstance(data, str) else data)
        return target

    def add(self, *names: str) -> None:
        from dulwich import porcelain

        porcelain.add(str(self.work), paths=[str(self.work / name) for name in names])

    def commit(self, message: str) -> str:
        from dulwich import porcelain

        self.clock += 60
        sha = porcelain.commit(
            str(self.work), message=message.encode(), author=SIGNATURE, committer=SIGNATURE,
            author_timezone=0, commit_timezone=0, commit_timestamp=self.clock, author_timestamp=self.clock,
        )
        return sha.decode()

    def rm(self, *names: str) -> None:
        from dulwich import porcelain

        porcelain.remove(str(self.work), paths=[str(self.work / name) for name in names], cached=True)

    # —— 调用检查入口 ——
    def inspect(self, operation: str, *args: str, kind: str = "project", repo: Path | None = None,
                workspace: Path | None = None, path_mappings: dict | None = None) -> dict:
        import json

        self.outputs += 1
        output = self.root / ("out-%d.json" % self.outputs)
        env = {
            "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
            "HOME": str(self.root / "home"),
            "LANG": "C.UTF-8",
            "PYTHONPATH": os.pathsep.join([str(SITE_DIR), self.dulwich_root]),
            "GIT_CONFIG_NOSYSTEM": "1",
            "INFINITY_WORKSPACE_KIND": kind,
        }
        if workspace is not None:
            env["INFINITY_WORKSPACE"] = str(workspace)
        if path_mappings is not None:
            env["INFINITY_GIT_LINUX_PATH_MAP"] = json.dumps(path_mappings)
        result = subprocess.run(
            [sys.executable, "-m", "infinity_git", "--infinity-inspect", operation, "--output", str(output), *args],
            cwd=str(repo or self.work), env=env, capture_output=True, text=True, timeout=120,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(Path(str(output) + ".tmp").exists())
        data = json.loads(output.read_text(encoding="utf-8"))
        output.unlink()
        self.assertEqual(data["version"], 1)
        return data

    def paths(self, status: dict, group: str) -> dict:
        return {item["path"]: item["change"] for item in status[group]}

    def blob(self, rev: str, path: str) -> dict:
        data = self.inspect("read", "--blob", "%s:%s" % (rev, path))
        self.assertTrue(data["repository"], data)
        self.assertNotIn("error", data)
        (item,) = data["blobs"]
        self.assertEqual((item["rev"], item["path"]), (rev, path))
        return item

    # —— 用例 ——
    def test_non_repository_directory(self) -> None:
        self.assertEqual(self.inspect("status"), {"version": 1, "repository": False})
        self.assertFalse(self.inspect("log")["repository"])

    def test_subdirectory_of_repository_is_not_discovered_upwards(self) -> None:
        self.init()
        (self.work / "sub").mkdir()
        self.assertEqual(self.inspect("status", repo=self.work / "sub"), {"version": 1, "repository": False})

    def test_bare_repository_is_not_a_workspace_repository(self) -> None:
        from dulwich.repo import Repo

        Repo.init_bare(str(self.work / "bare.git"), mkdir=True).close()
        self.assertFalse(self.inspect("status", repo=self.work / "bare.git")["repository"])

    def test_empty_repository(self) -> None:
        self.init()
        status = self.inspect("status")
        self.assertTrue(status["repository"])
        self.assertTrue(status["unborn"])
        self.assertIsNone(status["head"])
        self.assertFalse(status["detached"])
        self.assertIn(status["branch"], ("master", "main"))
        for group in ("staged", "unstaged", "untracked"):
            self.assertEqual(status[group], [], group)
            self.assertFalse(status[group + "_truncated"])
        self.assertEqual(self.inspect("log"), {"version": 1, "repository": True, "commits": [], "truncated": False})
        self.assertFalse(self.blob("HEAD", "a.txt")["exists"])
        self.assertFalse(self.blob("INDEX", "a.txt")["exists"])

    def _linked_worktree_fixture(self, namespace: str, absolute_common: bool) -> None:
        """系统 Git 只在临时目录内建仓和提交；检查阶段把真实指针表达成 Linux guest 路径。"""
        import shutil

        git = shutil.which("git")
        if git is None:
            self.skipTest("离线 linked worktree 夹具需要系统 Git")
        rootfs = self.root / "rootfs"
        rootfs.mkdir()
        if namespace == "root":
            self.work = rootfs / "root/repo"
            linked = rootfs / "root/linked"
            guest_repo, guest_linked = "/root/repo", "/root/linked"
            mappings = {"/": str(rootfs)}
        else:
            self.work = self.root / "workspace"
            linked = self.work / "branches/linked"
            guest_repo, guest_linked = "/workspace", "/workspace/branches/linked"
            mappings = {"/": str(rootfs), "/workspace": str(self.work)}
        self.work.mkdir(parents=True)
        env = {"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "HOME": str(self.root / "home"),
               "GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": os.devnull, "LANG": "C.UTF-8"}

        def local_git(*arguments, cwd=None):
            result = subprocess.run([git, "-c", "user.name=Offline Fixture", "-c", "user.email=fixture@example.invalid",
                                     "-c", "commit.gpgsign=false", "-c", "core.hooksPath=" + os.devnull, *arguments],
                                    cwd=str(cwd or self.work), env=env, capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stderr)
            return result.stdout.strip()

        local_git("init", "--quiet", "-b", "main")
        self.write("a.txt", "base\n")
        self.write("delete.txt", "delete base\n")
        local_git("add", "--", "a.txt", "delete.txt")
        local_git("commit", "--quiet", "--no-verify", "-m", "offline fixture")
        head = local_git("rev-parse", "HEAD")
        local_git("worktree", "add", "--quiet", "-b", "feature", str(linked), "HEAD")
        (linked / "a.txt").write_text("staged\n")
        (linked / "staged-new.txt").write_text("staged new\n")
        local_git("add", "--", "a.txt", "staged-new.txt", cwd=linked)
        (linked / "a.txt").write_text("working tree\n")
        (linked / "delete.txt").unlink()
        (linked / "新文件.txt").write_text("untracked\n")

        pointer = linked / ".git"
        control = Path(pointer.read_text().removeprefix("gitdir: ").strip())
        self.assertTrue(control.is_dir())
        common_pointer = control / "commondir"
        self.assertEqual(common_pointer.read_text().strip(), "../..")
        pointer.write_text("gitdir: " + guest_repo + "/" + control.relative_to(self.work).as_posix() + "\n")
        (control / "gitdir").write_text(guest_linked + "/.git\n")
        if absolute_common:
            common_pointer.write_text(guest_repo + "/.git\n")
        before_pointer = pointer.read_bytes()

        def metadata_snapshot():
            return {path.relative_to(self.root): path.read_bytes() for path in (self.work / ".git").rglob("*") if path.is_file()}

        before_metadata = metadata_snapshot()
        status = self.inspect("status", repo=linked, workspace=linked, path_mappings=mappings)
        self.assertNotIn("error", status, status)
        self.assertTrue(status["repository"])
        self.assertEqual((status["branch"], status["head"]), ("feature", head))
        self.assertEqual(self.paths(status, "staged"), {"a.txt": "modified", "staged-new.txt": "added"})
        self.assertEqual(self.paths(status, "unstaged"), {"a.txt": "modified", "delete.txt": "deleted"})
        self.assertEqual(self.paths(status, "untracked"), {"新文件.txt": "added"})
        modified = next(item for item in status["unstaged"] if item["path"] == "a.txt")
        self.assertEqual((modified["added_lines"], modified["removed_lines"]), (1, 1))
        blobs = self.inspect("read", "--blob", "HEAD:a.txt", "--blob", "INDEX:a.txt", repo=linked, path_mappings=mappings)
        self.assertNotIn("error", blobs, blobs)
        self.assertEqual([blob["text"] for blob in blobs["blobs"]], ["base\n", "staged\n"])
        log = self.inspect("log", repo=linked, path_mappings=mappings)
        self.assertEqual([entry["sha"] for entry in log["commits"]], [head])
        branches = self.inspect("branches", repo=linked, path_mappings=mappings)
        self.assertEqual({entry["name"]: entry["current"] for entry in branches["branches"]}, {"feature": True, "main": False})
        detail = self.inspect("commit", "--commit", head, repo=linked, path_mappings=mappings)
        self.assertEqual(detail["commit"]["sha"], head)
        self.assertEqual(pointer.read_bytes(), before_pointer)
        self.assertEqual(metadata_snapshot(), before_metadata)

    def test_linked_worktree_linux_root_pointer_and_absolute_common_are_read_only(self) -> None:
        self._linked_worktree_fixture("root", absolute_common=True)

    def test_linked_worktree_workspace_pointer_and_relative_common_are_read_only(self) -> None:
        self._linked_worktree_fixture("workspace", absolute_common=False)

    def test_local_internal_gitignore_hides_untracked_files_without_repository_writes(self) -> None:
        self.init()
        self.write(".infinity/.gitignore", "*\n")
        self.write(".infinity/internal", "internal\n")
        self.write("visible.txt", "visible\n")
        before = (self.work / ".git/config").read_bytes()
        exclude = (self.work / ".git/info/exclude").read_bytes()
        status = self.inspect("status", "--limit", "1", workspace=self.work)
        self.assertEqual(self.paths(status, "untracked"), {"visible.txt": "added"})
        self.assertFalse(status["untracked_truncated"])
        self.assertEqual((status["untracked"][0]["added_lines"], status["untracked"][0]["removed_lines"]), (1, 0))
        self.assertEqual((self.work / ".git/config").read_bytes(), before)
        self.assertFalse((self.work / ".gitignore").exists())
        self.assertEqual((self.work / ".git/info/exclude").read_bytes(), exclude)

    def test_forced_tracking_of_ignored_internal_files_is_visible(self) -> None:
        from dulwich.repo import Repo

        self.init()
        self.write(".infinity/.gitignore", "*\n")
        for path in (".infinity/modified", ".infinity/deleted", ".infinity/staged"):
            self.write(path, "before\n")
            # 绕过忽略规则直接暂存，与用户执行 git add -f 得到相同的索引状态。
            with Repo(str(self.work)) as repo:
                repo.get_worktree().stage([path])
        self.assertEqual(set(self.paths(self.inspect("status", workspace=self.work), "staged")),
                         {".infinity/modified", ".infinity/deleted", ".infinity/staged"})
        self.commit("init")
        self.write(".infinity/modified", "after\n")
        (self.work / ".infinity/deleted").unlink()
        (self.work / ".infinity/staged").unlink()
        self.rm(".infinity/staged")
        for kind in ("project", "shared"):
            status = self.inspect("status", kind=kind, workspace=self.work)
            self.assertEqual(self.paths(status, "staged"), {".infinity/staged": "deleted"})
            self.assertEqual(self.paths(status, "unstaged"),
                             {".infinity/modified": "modified", ".infinity/deleted": "deleted"})
            self.assertEqual(status["untracked"], [])

    def test_user_can_remove_or_edit_internal_gitignore(self) -> None:
        self.init()
        ignore = self.write(".infinity/.gitignore", "*\n")
        self.write(".infinity/user", "user\n")
        self.assertEqual(self.inspect("status", workspace=self.work)["untracked"], [])
        ignore.write_text("*.tmp\n")
        self.assertEqual(set(self.paths(self.inspect("status", workspace=self.work), "untracked")),
                         {".infinity/.gitignore", ".infinity/user"})
        self.assertEqual(ignore.read_text(), "*.tmp\n")
        ignore.unlink()
        self.assertEqual(set(self.paths(self.inspect("status", workspace=self.work), "untracked")), {".infinity/user"})
        self.assertFalse(ignore.exists())

    def test_internal_looking_names_follow_normal_git_rules_in_any_workspace(self) -> None:
        self.init()
        names = {".infinity_marker", ".recycle_bin/old", "sub/.infinity_user"}
        for name in names:
            self.write(name, "data\n")
        alias = self.root / "alias"
        alias.symlink_to(self.work, target_is_directory=True)
        for workspace in (self.work, alias, self.root, self.work / "sub", None):
            self.assertEqual(set(self.paths(self.inspect("status", workspace=workspace), "untracked")), names)

    def test_commit_rename_into_internal_looking_path_is_visible(self) -> None:
        self.init()
        self.write("visible", "unique rename content\n")
        self.add("visible")
        self.commit("init")
        (self.work / "visible").rename(self.work / ".infinity_marker")
        self.add("visible", ".infinity_marker")
        sha = self.commit("move in")
        detail = self.inspect("commit", "--commit", sha, workspace=self.work)
        self.assertEqual(detail["files"], [{"path": ".infinity_marker", "change": "renamed", "old_path": "visible"}])
        (self.work / ".infinity_marker").rename(self.work / "visible")
        self.add("visible", ".infinity_marker")
        sha = self.commit("move out")
        detail = self.inspect("commit", "--commit", sha, workspace=self.work)
        self.assertEqual(detail["files"], [{"path": "visible", "change": "renamed", "old_path": ".infinity_marker"}])

    def test_commit_file_list_includes_internal_looking_names_and_applies_limit(self) -> None:
        self.init()
        for path in (".infinity_marker", ".recycle_bin/old", "visible"):
            self.write(path, "data\n")
            self.add(path)
        sha = self.commit("init")
        detail = self.inspect("commit", "--commit", sha, workspace=self.work)
        self.assertEqual({item["path"] for item in detail["files"]}, {".infinity_marker", ".recycle_bin/old", "visible"})
        limited = self.inspect("commit", "--commit", sha, "--limit", "1", workspace=self.work)
        self.assertEqual(len(limited["files"]), 1)
        self.assertTrue(limited["files_truncated"])

    def test_repository_name_uses_origin_or_only_remote_and_never_returns_url(self) -> None:
        from dulwich.repo import Repo

        self.init()
        cases = (
            ({"origin": "https://example.test/owner/repo.git"}, "owner/repo"),
            ({"origin": "git@example.test:team/project.git"}, "team/project"),
            ({"origin": "example.test:team/project.git"}, "team/project"),
            ({"origin": "ssh://git@example.test:2222/team/project.git"}, "team/project"),
            ({"origin": "git+ssh://synthetic-user:synthetic-token@example.test/team/project.git"}, "team/project"),
            ({"origin": "https://synthetic-user:synthetic-token@example.test/owner/repo.git?key=synthetic-query"}, "owner/repo"),
            ({"upstream": "https://example.test/team/unique.git"}, "team/unique"),
            ({"other": "https://example.test/other/ignored.git", "origin": "https://example.test/main/selected.git"}, "main/selected"),
            ({"one": "https://example.test/a/one.git", "two": "https://example.test/b/two.git"}, "work"),
            ({"origin": "https://synthetic-user:synthetic-token@example.test"}, "work"),
            ({"origin": "https://synthetic-user:synthetic-token@[invalid/owner/repo.git"}, "work"),
            ({}, "work"),
        )
        for remotes, expected in cases:
            with self.subTest(remotes=tuple(remotes)):
                with Repo(str(self.work)) as repo:
                    config = repo.get_config()
                    for section in list(config.sections()):
                        if section[0] == b"remote":
                            del config[section]
                    for name, url in remotes.items():
                        config.set((b"remote", name.encode()), b"url", url.encode())
                    config.write_to_path()
                result = self.inspect("status")
                self.assertEqual(result.get("repository_name"), expected)
                self.assertNotIn("synthetic-token", str(result))
                self.assertNotIn("synthetic-query", str(result))

    def test_empty_repository_with_staged_and_untracked_files(self) -> None:
        self.init()
        self.write("a.txt", "a\n")
        self.write("b.txt", "b\n")
        self.add("a.txt")
        status = self.inspect("status")
        self.assertEqual(self.paths(status, "staged"), {"a.txt": "added"})
        self.assertEqual(self.paths(status, "untracked"), {"b.txt": "added"})
        self.assertEqual(status["unstaged"], [])
        self.assertEqual(self.blob("INDEX", "a.txt")["text"], "a\n")

    def test_staged_unstaged_and_untracked_groups(self) -> None:
        self.init()
        self.write("a.txt", "one\n")
        self.write("b.txt", "keep\n")
        self.write("c.txt", "remove me\n")
        self.write(".gitignore", "*.log\nbuild/\n")
        self.add("a.txt", "b.txt", "c.txt", ".gitignore")
        head = self.commit("init")
        index_file = self.work / ".git" / "index"
        config_file = self.work / ".git" / "config"

        self.write("a.txt", "one\ntwo\n")
        self.add("a.txt")
        self.write("a.txt", "one\ntwo\nthree\n")  # 同一文件既有已暂存也有未暂存改动
        (self.work / "b.txt").unlink()  # 工作区删除、未暂存
        self.rm("c.txt")  # 已暂存删除，工作区文件保留为未跟踪
        (self.work / "c.txt").unlink()
        self.write("new.txt", "fresh\n")
        self.write("docs/guide.md", "# 指南\n")
        self.write("debug.log", "ignored\n")
        self.write("build/out.bin", b"\0ignored")
        # 内容不变但 stat 漂移：只读检查不得回写索引的 stat 缓存。
        os.utime(self.work / ".gitignore", (self.clock + 10_000, self.clock + 10_000))
        before_index = index_file.read_bytes()
        before_config = config_file.read_bytes()

        status = self.inspect("status")
        self.assertEqual(status["head"], head)
        self.assertFalse(status["unborn"])
        self.assertEqual(self.paths(status, "staged"), {"a.txt": "modified", "c.txt": "deleted"})
        self.assertEqual(self.paths(status, "unstaged"), {"a.txt": "modified", "b.txt": "deleted"})
        self.assertEqual(self.paths(status, "untracked"), {"new.txt": "added", "docs/guide.md": "added"})
        self.assertEqual([item["path"] for item in status["untracked"]], ["docs/guide.md", "new.txt"])
        self.assertEqual(index_file.read_bytes(), before_index)
        self.assertEqual(config_file.read_bytes(), before_config)

        self.assertEqual(self.blob("HEAD", "a.txt")["text"], "one\n")
        self.assertEqual(self.blob("INDEX", "a.txt")["text"], "one\ntwo\n")
        self.assertFalse(self.blob("INDEX", "c.txt")["exists"])
        self.assertEqual(self.blob("HEAD", "c.txt")["text"], "remove me\n")
        both = self.inspect("read", "--blob", "HEAD:a.txt", "--blob", "INDEX:a.txt")["blobs"]
        self.assertEqual([item["text"] for item in both], ["one\n", "one\ntwo\n"])
        item = self.blob("HEAD", "a.txt")
        self.assertEqual((item["mode"], item["size"], item["binary"], item["truncated"]), ("100644", 4, False, False))

    def test_detached_head(self) -> None:
        self.init()
        self.write("a.txt", "a\n")
        self.add("a.txt")
        sha = self.commit("init")
        from dulwich.repo import Repo

        with Repo(str(self.work)) as repo:
            repo.refs.remove_if_equals(b"HEAD", None)
            repo.refs[b"HEAD"] = sha.encode()
        status = self.inspect("status")
        self.assertTrue(status["detached"])
        self.assertIsNone(status["branch"])
        self.assertEqual(status["head"], sha)

    def test_binary_and_oversized_files(self) -> None:
        self.init()
        self.write("image.png", b"\x89PNG\r\n\x1a\n\0\0\0data")
        large = ("x" * 1023 + "\n") * 600  # 600 KiB，超过 512 KiB 上限
        self.write("large.txt", large)
        self.write("edge.txt", "y" * (512 * 1024))  # 恰好等于上限
        self.write("latin.txt", b"caf\xe9\n")
        self.add("image.png", "large.txt", "edge.txt", "latin.txt")
        self.commit("assets")
        image = self.blob("HEAD", "image.png")
        self.assertEqual((image["exists"], image["binary"], image["text"], image["size"]), (True, True, None, 15))
        oversized = self.blob("INDEX", "large.txt")
        self.assertEqual((oversized["truncated"], oversized["text"], oversized["size"]), (True, None, len(large)))
        edge = self.blob("HEAD", "edge.txt")
        self.assertFalse(edge["truncated"])
        self.assertEqual(len(edge["text"]), 512 * 1024)
        self.assertEqual(self.blob("HEAD", "latin.txt")["text"], "caf�\n")

    def test_symlink_and_directory_entries(self) -> None:
        self.init()
        self.write("README", "hello\n")
        os.symlink("README", self.work / "link")
        self.write("dir/file.txt", "f\n")
        self.add("README", "link", "dir/file.txt")
        self.commit("links")
        link = self.blob("HEAD", "link")
        self.assertEqual((link["symlink"], link["text"], link["mode"]), (True, "README", "120000"))
        self.assertFalse(self.blob("HEAD", "dir")["exists"])
        self.assertFalse(self.blob("HEAD", "README/x")["exists"])

    def test_entry_limit_truncates_each_group(self) -> None:
        self.init()
        for number in range(3):
            self.write("u%d.txt" % number, "u\n")
        status = self.inspect("status", "--limit", "2")
        self.assertEqual(len(status["untracked"]), 2)
        self.assertTrue(status["untracked_truncated"])
        self.assertFalse(status["staged_truncated"])
        self.assertFalse(self.inspect("status", "--limit", "3")["untracked_truncated"])

    def test_log_lists_newest_first_with_limit(self) -> None:
        self.init()
        shas = []
        for number in range(3):
            self.write("a.txt", "v%d\n" % number)
            self.add("a.txt")
            shas.append(self.commit("提交 %d\n\n正文 %d" % (number, number)))
        log = self.inspect("log", "--limit", "2")
        self.assertTrue(log["truncated"])
        self.assertEqual([entry["sha"] for entry in log["commits"]], [shas[2], shas[1]])
        first = log["commits"][0]
        self.assertEqual(first["short_sha"], shas[2][:7])
        self.assertEqual((first["author_name"], first["author_email"]), ("Test", "test@example.com"))
        self.assertEqual(first["title"], "提交 2")
        self.assertEqual(first["parents"], [shas[1]])
        self.assertEqual(first["author_time"], self.clock)
        self.assertEqual(first["author_tz"], 0)
        full = self.inspect("log")
        self.assertFalse(full["truncated"])
        self.assertEqual(len(full["commits"]), 3)
        self.assertEqual(full["commits"][-1]["parents"], [])

    def test_commit_changes_and_both_sides(self) -> None:
        self.init()
        body = "".join("line %d of the moved file\n" % number for number in range(40))
        self.write("keep.txt", "one\n")
        self.write("gone.txt", "bye\n")
        self.write("old/name.txt", body)
        self.add("keep.txt", "gone.txt", "old/name.txt")
        root = self.commit("root")
        self.write("keep.txt", "one\ntwo\n")
        self.write("added.bin", b"\0\1\2")
        os.rename(self.work / "old/name.txt", self.work / "new-name.txt")
        self.add("keep.txt", "added.bin", "new-name.txt")
        self.rm("gone.txt", "old/name.txt")
        second = self.commit("second")

        root_commit = self.inspect("commit", "--commit", root)
        self.assertEqual(
            {item["path"]: item["change"] for item in root_commit["files"]},
            {"keep.txt": "added", "gone.txt": "added", "old/name.txt": "added"},
        )
        self.assertEqual(root_commit["commit"]["parents"], [])
        self.assertFalse(self.blob(root + "^", "keep.txt")["exists"])

        detail = self.inspect("commit", "--commit", second)
        self.assertEqual(detail["commit"]["sha"], second)
        self.assertEqual(detail["commit"]["message"], "second")
        self.assertFalse(detail["files_truncated"])
        files = {item["path"]: item for item in detail["files"]}
        self.assertEqual(files["keep.txt"]["change"], "modified")
        self.assertEqual(files["added.bin"]["change"], "added")
        self.assertEqual(files["gone.txt"]["change"], "deleted")
        self.assertEqual(files["new-name.txt"]["change"], "renamed")
        self.assertEqual(files["new-name.txt"]["old_path"], "old/name.txt")
        sides = self.inspect("read", "--blob", second + "^:keep.txt", "--blob", second + ":keep.txt")["blobs"]
        self.assertEqual([item["text"] for item in sides], ["one\n", "one\ntwo\n"])
        self.assertEqual(self.blob(second + "^", "old/name.txt")["text"], body)
        self.assertEqual(self.blob(second, "new-name.txt")["text"], body)
        self.assertTrue(self.blob(second, "added.bin")["binary"])
        limited = self.inspect("commit", "--commit", second, "--limit", "1")
        self.assertEqual(len(limited["files"]), 1)
        self.assertTrue(limited["files_truncated"])

    def configure_tracking(self, remote: bytes = b"origin", merge: bytes = b"refs/heads/main") -> None:
        from dulwich.repo import Repo

        with Repo(str(self.work)) as repo:
            branch = repo.refs.read_ref(b"HEAD")[len(b"ref: refs/heads/"):].strip()
            config = repo.get_config()
            config.set((b"branch", branch), b"remote", remote)
            config.set((b"branch", branch), b"merge", merge)
            config.write_to_path()

    def fork_commit(self, parents: list[str], title: str) -> str:
        from dulwich.objects import Commit
        from dulwich.repo import Repo

        with Repo(str(self.work)) as repo:
            item = Commit()
            item.tree = repo[parents[0].encode()].tree
            item.parents = [sha.encode() for sha in parents]
            item.author = item.committer = SIGNATURE
            self.clock += 60
            item.author_time = item.commit_time = self.clock
            item.author_timezone = item.commit_timezone = 0
            item.message = title.encode()
            repo.object_store.add_object(item)
            return item.id.decode()

    def test_tracking_divergence_fetch_time_and_read_only(self) -> None:
        from dulwich.repo import Repo

        self.init()
        base = self.commit("base")
        local = self.commit("local")
        remote = self.fork_commit([base], "remote")
        self.configure_tracking()
        with Repo(str(self.work)) as repo:
            repo.refs[b"refs/remotes/origin/main"] = remote.encode()
        fetch = self.work / ".git/FETCH_HEAD"
        fetch.write_text(remote + "\n")
        os.utime(fetch, (1_700_000_123, 1_700_000_123))
        before = {p.relative_to(self.work): p.read_bytes() for p in (self.work / ".git").rglob("*") if p.is_file()}
        status = self.inspect("status")
        self.assertEqual(status["head"], local)
        self.assertEqual((status.get("upstream"), status.get("ahead"), status.get("behind")), ("origin/main", 1, 1))
        self.assertIs(status.get("upstream_missing"), False)
        self.assertIs(status.get("tracking_truncated"), False)
        self.assertEqual(status.get("last_fetch_seconds"), 1_700_000_123)
        branches = self.inspect("branches")["branches"]
        self.assertEqual((branches[0]["ahead"], branches[0]["behind"]), (1, 1))
        after = {p.relative_to(self.work): p.read_bytes() for p in (self.work / ".git").rglob("*") if p.is_file()}
        self.assertEqual(before, after)

    def test_tracking_absent_missing_detached_and_unborn(self) -> None:
        from dulwich.repo import Repo

        self.init()
        self.configure_tracking()
        empty = self.inspect("status")
        self.assertIsNone(empty.get("upstream"))
        self.assertIsNone(empty.get("ahead"))
        self.assertIsNone(empty.get("last_fetch_seconds"))
        self.assertEqual(self.inspect("branches").get("branches"), [])
        sha = self.commit("base")
        missing = self.inspect("status")
        self.assertEqual(missing.get("upstream"), "origin/main")
        self.assertIs(missing.get("upstream_missing"), True)
        self.assertIsNone(missing.get("ahead"))
        self.assertIsNone(missing.get("behind"))
        with Repo(str(self.work)) as repo:
            config = repo.get_config()
            branch = repo.refs.read_ref(b"HEAD")[len(b"ref: refs/heads/"):].strip()
            del config[(b"branch", branch)]
            config.write_to_path()
        plain = self.inspect("status")
        self.assertIsNone(plain.get("upstream"))
        self.assertIs(plain.get("upstream_missing"), False)
        self.configure_tracking()
        with Repo(str(self.work)) as repo:
            repo.refs.remove_if_equals(b"HEAD", None)
            repo.refs[b"HEAD"] = sha.encode()
        detached = self.inspect("status")
        self.assertIsNone(detached.get("upstream"))
        self.assertIsNone(detached.get("behind"))
        self.assertFalse(any(b["current"] for b in self.inspect("branches")["branches"]))

    def test_tracking_merge_counts_and_local_upstream(self) -> None:
        from dulwich.repo import Repo

        self.init()
        base = self.commit("base")
        local = self.commit("local")
        remote = self.fork_commit([base], "remote")
        merged = self.fork_commit([local, remote], "merge")
        with Repo(str(self.work)) as repo:
            repo.refs[b"HEAD"] = merged.encode()
            repo.refs[b"refs/heads/base"] = remote.encode()
        self.configure_tracking(remote=b".", merge=b"refs/heads/base")
        status = self.inspect("status")
        self.assertEqual((status.get("upstream"), status.get("ahead"), status.get("behind")), ("base", 2, 0))

    def test_tracking_count_and_graph_budgets(self) -> None:
        from dulwich.repo import Repo

        self.init()
        base = self.commit("base")
        self.commit("one")
        self.commit("two")
        self.commit("three")
        self.configure_tracking()
        with Repo(str(self.work)) as repo:
            repo.refs[b"refs/remotes/origin/main"] = base.encode()
            with unittest.mock.patch.object(infinity_git, "INSPECT_MAX_TRACKING_COUNT", 2, create=True):
                status = infinity_git._inspect_status(repo, 10)
            self.assertEqual((status.get("ahead"), status.get("behind"), status.get("tracking_truncated")), (2, 0, True))
            with unittest.mock.patch.object(infinity_git, "INSPECT_MAX_GRAPH_COMMITS", 1, create=True):
                status = infinity_git._inspect_status(repo, 10)
            self.assertIsNone(status.get("ahead"))
            self.assertIs(status.get("tracking_truncated"), True)

    def test_line_counts_match_git_numstat_for_all_groups(self) -> None:
        self.init()
        self.write("both.txt", "keep\nold\nend\n")
        self.write("deleted.txt", "one\ntwo")
        self.write("unstaged-delete.txt", "gone\n")
        self.write("newline.txt", "same\n")
        self.write("cr.txt", b"one\rtwo\n")
        self.add("both.txt", "deleted.txt", "unstaged-delete.txt", "newline.txt", "cr.txt")
        self.commit("base")
        self.write("both.txt", "keep\nnew\nextra\nend\n")
        self.write("added.txt", "first\nsecond")
        self.write("newline.txt", "same")
        self.write("cr.txt", b"one\rthree\n")
        self.add("both.txt", "added.txt", "newline.txt", "cr.txt")
        self.rm("deleted.txt")
        (self.work / "deleted.txt").unlink()
        self.write("both.txt", "keep\nnew\nend\n")
        (self.work / "unstaged-delete.txt").unlink()
        self.write("fresh.txt", "one\ntwo\n")
        self.write("empty.txt", b"")
        status = self.inspect("status")
        counts = lambda group: {f["path"]: (f.get("added_lines"), f.get("removed_lines")) for f in status[group]}
        self.assertEqual(counts("staged"), {"both.txt": (2, 1), "added.txt": (2, 0), "deleted.txt": (0, 2), "newline.txt": (1, 1), "cr.txt": (1, 1)})
        self.assertEqual(counts("unstaged"), {"both.txt": (0, 1), "unstaged-delete.txt": (0, 1)})
        self.assertEqual(counts("untracked"), {"fresh.txt": (2, 0), "empty.txt": (0, 0)})
        env = dict(os.environ, GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull)
        for group, options in (("staged", ["--cached"]), ("unstaged", [])):
            reference = subprocess.run(["git", "diff", "--numstat", "--no-renames", *options], cwd=self.work, env=env, check=True, capture_output=True, text=True)
            expected = {path: (int(added), int(removed)) for added, removed, path in (line.split("\t") for line in reference.stdout.splitlines())}
            self.assertEqual(counts(group), expected)

    def test_line_counts_skip_binary_large_symlink_and_submodule(self) -> None:
        from dulwich.index import IndexEntry
        from dulwich.repo import Repo

        self.init()
        self.write("binary", b"a\0b\n")
        self.write("large", b"x" * (512 * 1024 + 1))
        os.symlink("large", self.work / "link")
        self.add("binary", "large", "link")
        with Repo(str(self.work)) as repo:
            index = repo.open_index()
            index[b"module"] = IndexEntry((0, 0), (0, 0), 0, 0, 0o160000, 0, 0, 0, b"1" * 40, 0)
            index.write()
        status = self.inspect("status")
        for entry in status["staged"]:
            self.assertIsNone(entry.get("added_lines"))
            self.assertIsNone(entry.get("removed_lines"))
            self.assertEqual(entry.get("lines_skipped"), {"binary": "binary", "large": "too_large", "link": "symlink", "module": "submodule"}[entry["path"]])

    def test_line_count_file_byte_and_diff_work_budgets(self) -> None:
        from dulwich.repo import Repo

        self.init()
        self.write("a", "old\n")
        self.add("a")
        self.commit("base")
        self.write("a", "new\n")
        self.add("a")
        self.write("b", "b\n")
        with Repo(str(self.work)) as repo:
            for name, value in (("INSPECT_MAX_STAT_FILES", 1), ("INSPECT_MAX_STAT_BYTES", 3), ("INSPECT_MAX_DIFF_WORK", 0)):
                with unittest.mock.patch.object(infinity_git, name, value, create=True):
                    status = infinity_git._inspect_status(repo, 10)
                target = status["untracked"][0] if name.endswith("FILES") else status["staged"][0]
                self.assertIsNone(target.get("added_lines"))
                self.assertEqual(target.get("lines_skipped"), "budget", name)

    def test_line_counts_skip_unstaged_and_untracked_content(self) -> None:
        self.init()
        for path in ("binary", "large", "link"):
            self.write(path, "old\n")
        self.add("binary", "large", "link")
        self.commit("base")
        self.write("binary", b"new\0\n")
        self.write("large", b"x" * (512 * 1024 + 1))
        (self.work / "link").unlink()
        os.symlink("binary", self.work / "link")
        self.write("new-binary", b"new\0\n")
        self.write("new-large", b"x" * (512 * 1024 + 1))
        os.symlink("binary", self.work / "new-link")
        status = self.inspect("status")
        for group in ("unstaged", "untracked"):
            entries = status[group]
            self.assertEqual(len(entries), 3)
            for entry in entries:
                self.assertIsNone(entry.get("added_lines"))
                self.assertIsNone(entry.get("removed_lines"))
                name = entry["path"].removeprefix("new-")
                self.assertEqual(entry.get("lines_skipped"), {"binary": "binary", "large": "too_large", "link": "symlink"}[name])

    def test_tracking_shallow_history_and_equal_tips(self) -> None:
        from dulwich.repo import Repo

        self.init()
        base = self.commit("base")
        tip = self.commit("tip")
        self.configure_tracking()
        with Repo(str(self.work)) as repo:
            repo.refs[b"refs/remotes/origin/main"] = tip.encode()
        equal = self.inspect("status")
        self.assertEqual((equal.get("ahead"), equal.get("behind"), equal.get("tracking_truncated")), (0, 0, False))
        with Repo(str(self.work)) as repo:
            repo.refs[b"refs/remotes/origin/main"] = base.encode()
        (self.work / ".git/shallow").write_text(tip + "\n")
        shallow = self.inspect("status")
        # 与 Git 的本地浅历史视图一致，shallow 根不穿透到其父提交。
        self.assertEqual((shallow.get("ahead"), shallow.get("behind")), (1, 1))

    def test_branches_are_local_sorted_current_and_limited(self) -> None:
        from dulwich.repo import Repo

        self.init()
        first = self.commit("first")
        second = self.commit("second")
        third = self.fork_commit([first], "third\n\nbody")
        with Repo(str(self.work)) as repo:
            current = repo.refs.read_ref(b"HEAD")[len(b"ref: refs/heads/"):].strip().decode()
            repo.refs[b"refs/heads/old"] = first.encode()
            repo.refs[b"refs/heads/recent"] = third.encode()
            repo.refs[b"refs/remotes/origin/hidden"] = third.encode()
        branches = self.inspect("branches", "--limit", "2")
        self.assertTrue(branches.get("truncated"))
        self.assertEqual([b["name"] for b in branches["branches"]], ["recent", current])
        self.assertEqual([b["current"] for b in branches["branches"]], [False, True])
        self.assertEqual((branches["branches"][0]["short_sha"], branches["branches"][0]["author_time"], branches["branches"][0]["title"]), (third[:7], self.clock, "third"))
        all_branches = self.inspect("branches", "--limit", "3")
        self.assertFalse(all_branches["truncated"])
        self.assertEqual([b["name"] for b in all_branches["branches"]], ["recent", current, "old"])
        self.assertEqual(all_branches["branches"][1]["short_sha"], second[:7])

    def test_invalid_arguments_are_reported_as_json_errors(self) -> None:
        self.init()
        self.write("a.txt", "a\n")
        self.add("a.txt")
        self.commit("init")
        cases = [
            (("read", "--blob", "HEAD:../escape"), "INVALID_ARGUMENT"),
            (("read", "--blob", "nocolon"), "INVALID_ARGUMENT"),
            (("read", "--blob", "main:a.txt"), "INVALID_ARGUMENT"),
            (("read", "--blob", "0" * 40 + ":a.txt"), "NOT_FOUND"),
            (("commit", "--commit", "abc"), "INVALID_ARGUMENT"),
            (("commit", "--commit", "0" * 40), "NOT_FOUND"),
        ]
        for args, code in cases:
            data = self.inspect(*args)
            self.assertTrue(data["repository"], args)
            self.assertEqual(data["error"]["code"], code, args)
            self.assertTrue(data["error"]["message"], args)

    def test_shared_storage_honours_ignorecase_and_symlinks_in_memory(self) -> None:
        self.init()
        self.write("Readme.txt", "hello\n")
        self.write("target.txt", "t\n")
        os.symlink("target.txt", self.work / "link")
        self.add("Readme.txt", "target.txt", "link")
        self.commit("init")
        # 模拟共享存储检出：符号链接是内容为目标的普通文件，文件名大小写与索引不同。
        (self.work / "link").unlink()
        self.write("link", "target.txt")
        os.rename(self.work / "Readme.txt", self.work / "readme-tmp")
        os.rename(self.work / "readme-tmp", self.work / "README.TXT")
        config_file = self.work / ".git" / "config"
        before_config = config_file.read_bytes()

        shared = self.inspect("status", kind="shared")
        self.assertNotIn("README.TXT", self.paths(shared, "untracked"))
        self.assertNotIn("link", self.paths(shared, "unstaged"))
        self.assertEqual(config_file.read_bytes(), before_config)  # 兼容配置不写回

        project = self.inspect("status", kind="project")
        self.assertIn("README.TXT", self.paths(project, "untracked"))

        # 仓库显式设置的值优先于共享存储默认值。
        from dulwich.repo import Repo

        with Repo(str(self.work)) as repo:
            config = repo.get_config()
            config.set((b"core",), b"ignorecase", b"false")
            config.write_to_path()
        explicit = self.inspect("status", kind="shared")
        self.assertIn("README.TXT", self.paths(explicit, "untracked"))


class NumstatTest(unittest.TestCase):
    def test_repeated_lines_and_lf_boundaries(self) -> None:
        from infinity_git_numstat import numstat

        for old, new, expected in (
            (b"a\na\na\n", b"a\nb\na\na\n", (1, 0)),
            (b"a\n", b"a", (1, 1)),
            (b"a\rb\n", b"a\rc\n", (1, 1)),
            (b"", b"\n", (1, 0)),
            (b"\xff\n", b"\xfe\n", (1, 1)),
        ):
            with self.subTest(old=old, new=new):
                self.assertEqual(numstat(old, new, lambda: None), expected)

    def test_short_sequences_match_independent_lcs_oracle(self) -> None:
        import itertools
        from infinity_git_numstat import numstat

        sequences = [seq for length in range(5) for seq in itertools.product((b"a\n", b"b\n"), repeat=length)]
        for a in sequences:
            for b in sequences:
                table = [[0] * (len(b) + 1) for _ in range(len(a) + 1)]
                for i, left in enumerate(a, 1):
                    for j, right in enumerate(b, 1):
                        table[i][j] = table[i - 1][j - 1] + 1 if left == right else max(table[i - 1][j], table[i][j - 1])
                common = table[-1][-1]
                self.assertEqual(numstat(b"".join(a), b"".join(b), lambda: None), (len(b) - common, len(a) - common), (a, b))


TOKEN = "nxt_SyntheticTokenForInfinityTests0123456789"
NONCE = "N" * 43


class RemoteAuthRewriteTest(unittest.TestCase):
    """凭据代理的地址改写、环境变量解析与用法说明，不依赖 dulwich。"""

    def proxy(self) -> object:
        return infinity_git._CredentialProxy(4321, NONCE, {"github.com", "git.example.com:8443"})

    def test_configured_host_is_rewritten_to_proxy_without_userinfo(self) -> None:
        proxy = self.proxy()
        self.assertEqual(
            proxy.outbound("https://github.com/owner/repo.git/info/refs?service=git-upload-pack"),
            "http://127.0.0.1:4321/github.com/owner/repo.git/info/refs?service=git-upload-pack",
        )
        self.assertEqual(
            proxy.outbound("https://user:secret@GitHub.com:443/owner/repo.git/git-receive-pack"),
            "http://127.0.0.1:4321/github.com/owner/repo.git/git-receive-pack",
        )
        self.assertEqual(
            proxy.outbound("https://git.example.com:8443/team/r.git/git-upload-pack"),
            "http://127.0.0.1:4321/git.example.com:8443/team/r.git/git-upload-pack",
        )

    def test_unconfigured_hosts_and_plain_http_stay_direct(self) -> None:
        proxy = self.proxy()
        for url in (
            "https://gitlab.com/owner/repo.git/info/refs",
            "https://git.example.com/team/r.git/info/refs",  # 443 端口未配置
            "http://github.com/owner/repo.git/info/refs",
            "/local/path",
        ):
            self.assertIsNone(proxy.outbound(url), url)

    def test_proxy_url_maps_back_to_original_host(self) -> None:
        proxy = self.proxy()
        self.assertEqual(
            proxy.inbound("http://127.0.0.1:4321/github.com/moved/repo.git/info/refs?service=git-upload-pack"),
            "https://github.com/moved/repo.git/info/refs?service=git-upload-pack",
        )
        self.assertEqual(proxy.inbound("https://elsewhere/x"), "https://elsewhere/x")
        # urllib3 未发生重定向时 geturl() 只给出请求路径。
        self.assertEqual(
            proxy.inbound("/github.com/owner/repo.git/info/refs?service=git-upload-pack"),
            "https://github.com/owner/repo.git/info/refs?service=git-upload-pack",
        )

    def test_environment_requires_loopback_address_nonce_and_hosts(self) -> None:
        base = {"INFINITY_GIT_PROXY": "127.0.0.1:4321", "INFINITY_GIT_NONCE": NONCE, "INFINITY_GIT_CREDENTIAL_HOSTS": "github.com"}
        with unittest.mock.patch.dict(os.environ, base, clear=True):
            proxy = infinity_git._CredentialProxy.from_environment()
            self.assertEqual((proxy.prefix, proxy.hosts), ("http://127.0.0.1:4321/", {"github.com"}))
        for key, value in (
            ("INFINITY_GIT_PROXY", "10.0.0.5:4321"),
            ("INFINITY_GIT_PROXY", "localhost:4321"),
            ("INFINITY_GIT_PROXY", "127.0.0.1:0"),
            ("INFINITY_GIT_NONCE", "short"),
            ("INFINITY_GIT_NONCE", "bad nonce with spaces and more characters"),
            ("INFINITY_GIT_CREDENTIAL_HOSTS", ""),
        ):
            with unittest.mock.patch.dict(os.environ, {**base, key: value}, clear=True):
                self.assertIsNone(infinity_git._CredentialProxy.from_environment(), (key, value))
        with unittest.mock.patch.dict(os.environ, {"INFINITY_GIT_SSH_BRIDGE": "127.0.0.1:5555", "INFINITY_GIT_NONCE": NONCE}, clear=True):
            self.assertEqual(infinity_git._BridgeVendor.from_environment().address, ("127.0.0.1", 5555))
        with unittest.mock.patch.dict(os.environ, {"INFINITY_GIT_SSH_BRIDGE": "192.168.1.2:5555", "INFINITY_GIT_NONCE": NONCE}, clear=True):
            self.assertIsNone(infinity_git._BridgeVendor.from_environment())

    def test_usage_points_to_credential_settings_instead_of_token_urls(self) -> None:
        usage = infinity_git._usage()
        self.assertIn("Git 凭据", usage)
        self.assertIn("SSH 主机", usage)
        self.assertNotIn("URL 中提供令牌", usage)

    def run_main(self, *argv: str, env: dict | None = None) -> tuple[int, str]:
        stderr = io.StringIO()
        with unittest.mock.patch.dict(os.environ, env or {}, clear=True), unittest.mock.patch.object(sys, "stderr", stderr):
            code = infinity_git.main(list(argv))
        return code, stderr.getvalue()

    def test_ssh_remote_without_bridge_is_unsupported(self) -> None:
        code, stderr = self.run_main("clone", "git@github.com:owner/repo.git")
        self.assertEqual(code, infinity_git.UNSUPPORTED_EXIT)
        self.assertIn("GIT_UNSUPPORTED", stderr)
        self.assertIn("SSH 桥", stderr)

    def test_bridge_vendor_rejects_password_and_key_file(self) -> None:
        vendor = infinity_git._BridgeVendor(("127.0.0.1", 1), NONCE)
        for kwargs in ({"password": "x"}, {"key_filename": "/tmp/id"}):
            with self.assertRaises(infinity_git._RemoteAuthError):
                vendor.run_command("github.com", b"git-upload-pack 'a/b.git'", **kwargs)


class _FakeCredentialProxy:
    """模拟 Kotlin 凭据注入代理：校验 nonce 与智能协议路径，加 Authorization 后转发到本机 dulwich 服务。"""

    SMART = ("/info/refs", "/git-upload-pack", "/git-receive-pack")

    def __init__(self, upstream_port: int, host_key: str = "git.test") -> None:
        import threading
        from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

        self.requests: list[dict] = []
        self.mode = "forward"
        fake = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *args) -> None:
                pass

            def body(self) -> bytes:
                if "chunked" in (self.headers.get("Transfer-Encoding") or "").lower():
                    data = b""
                    while True:
                        size = int(self.rfile.readline().split(b";")[0].strip(), 16)
                        if size == 0:
                            while self.rfile.readline() not in (b"\r\n", b"\n", b""):
                                pass
                            return data
                        data += self.rfile.read(size)
                        self.rfile.readline()
                return self.rfile.read(int(self.headers.get("Content-Length") or 0))

            def reply(self, status: int, headers: dict, data: bytes) -> None:
                self.send_response(status)
                for name, value in headers.items():
                    self.send_header(name, value)
                self.send_header("Content-Length", str(len(data)))
                self.send_header("Connection", "close")
                self.end_headers()
                self.wfile.write(data)

            def handle_any(self) -> None:
                import http.client

                data = self.body() if self.command == "POST" else None
                fake.requests.append({
                    "path": self.path, "nonce": self.headers.get("X-Infinity-Git-Nonce"),
                    "authorization": self.headers.get("Authorization"),
                })
                prefix = "/" + host_key + "/"
                path = self.path.split("?", 1)[0]
                if self.headers.get("X-Infinity-Git-Nonce") != NONCE:
                    return self.reply(403, {"X-Infinity-Git-Error": "NONCE_REJECTED", "Content-Type": "text/plain; charset=utf-8"},
                                      "nonce 缺失、无效或所属命令已结束。".encode())
                if not self.path.startswith(prefix) or not path.endswith(fake.SMART):
                    return self.reply(403, {"X-Infinity-Git-Error": "PATH_REJECTED"}, "只允许 git 智能协议路径。".encode())
                if fake.mode == "unauthorized":
                    return self.reply(401, {"WWW-Authenticate": 'Basic realm="x"'}, b"")
                if fake.mode == "redirect" and "/old.git/" in self.path:
                    return self.reply(301, {"Location": "http://127.0.0.1:%d%s" % (fake.port, self.path.replace("/old.git/", "/"))}, b"")
                upstream = http.client.HTTPConnection("127.0.0.1", upstream_port, timeout=60)
                headers = {name: value for name, value in self.headers.items()
                           if name.lower() in ("accept", "content-type", "git-protocol", "content-encoding")}
                headers["Authorization"] = "Basic " + __import__("base64").b64encode(("user:" + TOKEN).encode()).decode()
                upstream.request(self.command, self.path[len(prefix) - 1:], body=data, headers=headers)
                response = upstream.getresponse()
                payload = response.read()
                forwarded = {name: value for name, value in response.getheaders() if name.lower() == "content-type"}
                self.reply(response.status, forwarded, payload)
                upstream.close()

            do_GET = handle_any
            do_POST = handle_any

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.port = self.server.server_port
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self) -> None:
        self.server.shutdown()
        self.server.server_close()


class CredentialProxyCloneTest(unittest.TestCase):
    """经模拟凭据代理对本机 dulwich 智能 HTTP 服务 clone/push，断言令牌只出现在代理转发侧。"""

    def setUp(self) -> None:
        try:
            self.dulwich_root = _load_dulwich()
        except ImportError:
            self.skipTest("未找到 dulwich：设置 INFINITY_DULWICH_PATH 指向解压后的 dulwich 与 urllib3 wheel 目录")
        import threading
        from wsgiref.simple_server import WSGIRequestHandler, make_server

        from dulwich.repo import Repo
        from dulwich.server import DictBackend
        from dulwich.web import make_wsgi_chain

        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name).resolve()
        (self.root / "home").mkdir()
        remote = _RemoteBuilder(self.root / "remote.git")
        first = remote.commit(b"main 1\n", [])
        remote.repo.refs[b"refs/heads/main"] = first
        remote.repo.refs.set_symbolic_ref(b"HEAD", b"refs/heads/main")
        self.remote_repo = remote.repo
        self.addCleanup(remote.repo.close)
        self.upstream_auth: list = []
        application = make_wsgi_chain(DictBackend({"/repo.git": remote.repo}))
        seen = self.upstream_auth

        def recording(environ, start_response):
            seen.append(environ.get("HTTP_AUTHORIZATION"))
            return application(environ, start_response)

        class QuietHandler(WSGIRequestHandler):
            def log_message(self, *args) -> None:
                pass

        upstream = make_server("127.0.0.1", 0, recording, handler_class=QuietHandler)
        threading.Thread(target=upstream.serve_forever, daemon=True).start()
        self.addCleanup(upstream.server_close)
        self.addCleanup(upstream.shutdown)
        self.upstream_port = upstream.server_port
        self.proxy = _FakeCredentialProxy(self.upstream_port)
        self.addCleanup(self.proxy.close)

    def git(self, *args: str, cwd: Path | None = None, nonce: str = NONCE) -> subprocess.CompletedProcess:
        env = {
            "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
            "HOME": str(self.root / "home"),
            "LANG": "C.UTF-8",
            "PYTHONPATH": os.pathsep.join([str(SITE_DIR), self.dulwich_root]),
            "GIT_CONFIG_NOSYSTEM": "1",
            "INFINITY_WORKSPACE_KIND": "project",
            "INFINITY_GIT_NONCE": nonce,
            "INFINITY_GIT_PROXY": "127.0.0.1:%d" % self.proxy.port,
            "INFINITY_GIT_CREDENTIAL_HOSTS": "git.test",
            "GIT_AUTHOR_NAME": "T", "GIT_AUTHOR_EMAIL": "t@example.com",
            "GIT_COMMITTER_NAME": "T", "GIT_COMMITTER_EMAIL": "t@example.com",
        }
        result = subprocess.run(
            [sys.executable, "-m", "infinity_git", *args],
            cwd=str(cwd or self.root), env=env, capture_output=True, text=True, timeout=120,
        )
        self.assertNotIn(TOKEN, result.stdout + result.stderr)
        return result

    def test_clone_and_push_through_proxy(self) -> None:
        result = self.git("clone", "https://git.test/repo.git", "work")
        self.assertEqual(result.returncode, 0, result.stderr)
        work = self.root / "work"
        self.assertEqual((work / "README").read_text(), "main 1\n")
        self.assertTrue(self.proxy.requests)
        self.assertTrue(all(item["nonce"] == NONCE and item["authorization"] is None for item in self.proxy.requests))
        self.assertTrue(all(item["path"].startswith("/git.test/repo.git/") for item in self.proxy.requests))
        self.assertTrue(self.upstream_auth and all(value and value.startswith("Basic ") for value in self.upstream_auth))
        with Path(work / ".git" / "config").open() as handle:
            self.assertIn("https://git.test/repo.git", handle.read())  # 远端仍记为原地址

        (work / "new.txt").write_text("pushed\n")
        self.assertEqual(self.git("add", "new.txt", cwd=work).returncode, 0)
        self.assertEqual(self.git("commit", "-m", "add", cwd=work).returncode, 0)
        pushed = self.git("push", "origin", "main", cwd=work)
        self.assertEqual(pushed.returncode, 0, pushed.stderr + repr(self.proxy.requests))
        from dulwich.repo import Repo

        with Repo(str(work)) as local:
            head = local.refs[b"refs/heads/main"]
        self.assertEqual(self.remote_repo.refs[b"refs/heads/main"], head)
        self.assertTrue(any(item["path"].endswith("/git-receive-pack") for item in self.proxy.requests))

    def test_unconfigured_host_connects_directly(self) -> None:
        result = self.git("clone", "http://127.0.0.1:%d/repo.git" % self.upstream_port, "direct")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.proxy.requests, [])
        self.assertEqual(self.upstream_auth[0], None)

    def test_proxy_rejection_is_reported_without_secrets(self) -> None:
        result = self.git("clone", "https://git.test/repo.git", "work", nonce="Z" * 43)
        self.assertEqual(result.returncode, infinity_git.FATAL_EXIT)
        self.assertIn("NONCE_REJECTED", result.stderr)
        self.assertIn("Git 凭据代理拒绝请求", result.stderr)
        self.assertNotIn("Z" * 43, result.stderr)
        self.assertEqual(self.upstream_auth, [])

    def test_rejected_credentials_point_to_settings(self) -> None:
        self.proxy.mode = "unauthorized"
        result = self.git("ls-remote", "https://git.test/repo.git")
        self.assertEqual(result.returncode, infinity_git.FATAL_EXIT)
        self.assertIn("CREDENTIAL_REJECTED", result.stderr)
        self.assertIn("设置 → 执行环境与连接 → Git 凭据", result.stderr)

    def test_redirect_to_proxy_keeps_original_host(self) -> None:
        self.proxy.mode = "redirect"
        result = self.git("clone", "https://git.test/old.git/repo.git", "moved")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.root / "moved" / "README").read_text(), "main 1\n")
        posts = [item["path"] for item in self.proxy.requests if item["path"].endswith("/git-upload-pack")]
        self.assertTrue(posts and all("/old.git/" not in path for path in posts), self.proxy.requests)


class _FakeSshBridge:
    """模拟 Kotlin SSH 桥：校验 nonce 与命令格式，把 git-upload-pack/receive-pack 交给本机 dulwich 子进程执行。"""

    COMMAND = __import__("re").compile(r"^(git-upload-pack|git-receive-pack) '([^']*)'$")
    # dulwich.cli 的 receive-pack 用缓冲 read 读 pack，从管道读会一直等到 EOF；按 TCP 服务端的方式给出 recv。
    SERVE = (
        "import os, sys\n"
        "from dulwich.protocol import ReceivableProtocol\n"
        "from dulwich.server import FileSystemBackend, ReceivePackHandler, UploadPackHandler\n"
        "def write(data):\n"
        "    view = memoryview(data)\n"
        "    while view:\n"
        "        view = view[os.write(1, view):]\n"
        "service, path = sys.argv[1], sys.argv[2]\n"
        "handler = ReceivePackHandler if service == 'git-receive-pack' else UploadPackHandler\n"
        "handler(FileSystemBackend(path), [path], ReceivableProtocol(lambda size: os.read(0, size), write)).handle()\n"
    )

    def __init__(self, repositories: Path, dulwich_root: str) -> None:
        import socket
        import threading

        self.repositories = repositories
        self.dulwich_root = dulwich_root
        self.requests: list[dict] = []
        self.refusal: dict | None = None
        self.server = socket.create_server(("127.0.0.1", 0))
        self.port = self.server.getsockname()[1]
        self.thread = threading.Thread(target=self.serve, daemon=True)
        self.thread.start()

    def serve(self) -> None:
        import threading

        while True:
            try:
                client, _ = self.server.accept()
            except OSError:
                return
            threading.Thread(target=self.handle, args=(client,), daemon=True).start()

    def handle(self, client) -> None:
        import json
        import threading

        reader = client.makefile("rb")
        request = json.loads(reader.readline().decode())
        self.requests.append(request)

        def refuse(code: str, message: str) -> None:
            client.sendall((json.dumps({"ok": False, "code": code, "message": message}) + "\n").encode())
            client.close()

        if request.get("nonce") != NONCE:
            return refuse("NONCE_REJECTED", "nonce 无效")
        if self.refusal:
            return refuse(self.refusal["code"], self.refusal["message"])
        match = self.COMMAND.match(request.get("command", ""))
        if not match:
            return refuse("COMMAND_REJECTED", "命令不在白名单")
        service, path = match.groups()
        process = subprocess.Popen(
            [sys.executable, "-c", self.SERVE, service, str(self.repositories / path.lstrip("/~"))],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            env={"PATH": os.environ.get("PATH", ""), "PYTHONPATH": self.dulwich_root},
        )
        client.sendall(b'{"ok": true}\n')
        lock = threading.Lock()

        def frame(kind: int, data: bytes) -> None:
            with lock:
                client.sendall(bytes([kind]) + len(data).to_bytes(4, "big") + data)

        def pump(stream, kind: int) -> None:
            while True:
                data = stream.read1(65536)
                if not data:
                    return
                frame(kind, data)

        def feed() -> None:
            try:
                while True:
                    head = reader.read(5)
                    if len(head) < 5:
                        break
                    size = int.from_bytes(head[1:], "big")
                    payload = reader.read(size)
                    if head[0] == 4:
                        break
                    process.stdin.write(payload)
                    process.stdin.flush()
            except OSError:
                pass
            finally:
                try:
                    process.stdin.close()
                except OSError:
                    pass

        threading.Thread(target=feed, daemon=True).start()
        errors = threading.Thread(target=pump, args=(process.stderr, 2), daemon=True)
        errors.start()
        try:
            pump(process.stdout, 1)
            errors.join(10)
            frame(3, process.wait(30).to_bytes(4, "big", signed=True))
        except OSError:
            process.kill()
        finally:
            client.close()

    def close(self) -> None:
        self.server.close()


class SshBridgeCloneTest(unittest.TestCase):
    """经模拟 SSH 桥 clone/push git@主机:路径 远端，覆盖 SSH vendor 的握手、帧协议与错误信息。"""

    def setUp(self) -> None:
        try:
            self.dulwich_root = _load_dulwich()
        except ImportError:
            self.skipTest("未找到 dulwich：设置 INFINITY_DULWICH_PATH 指向解压后的 dulwich 与 urllib3 wheel 目录")
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name).resolve()
        (self.root / "home").mkdir()
        (self.root / "srv").mkdir()
        remote = _RemoteBuilder(self.root / "srv" / "repo.git")
        first = remote.commit(b"ssh 1\n", [])
        remote.repo.refs[b"refs/heads/main"] = first
        remote.repo.refs.set_symbolic_ref(b"HEAD", b"refs/heads/main")
        remote.repo.close()
        self.bridge = _FakeSshBridge(self.root / "srv", self.dulwich_root)
        self.addCleanup(self.bridge.close)

    def git(self, *args: str, cwd: Path | None = None) -> subprocess.CompletedProcess:
        env = {
            "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
            "HOME": str(self.root / "home"),
            "LANG": "C.UTF-8",
            "PYTHONPATH": os.pathsep.join([str(SITE_DIR), self.dulwich_root]),
            "GIT_CONFIG_NOSYSTEM": "1",
            "INFINITY_WORKSPACE_KIND": "project",
            "INFINITY_GIT_NONCE": NONCE,
            "INFINITY_GIT_SSH_BRIDGE": "127.0.0.1:%d" % self.bridge.port,
            "GIT_AUTHOR_NAME": "T", "GIT_AUTHOR_EMAIL": "t@example.com",
            "GIT_COMMITTER_NAME": "T", "GIT_COMMITTER_EMAIL": "t@example.com",
        }
        return subprocess.run(
            [sys.executable, "-m", "infinity_git", *args],
            cwd=str(cwd or self.root), env=env, capture_output=True, text=True, timeout=120,
        )

    def test_scp_like_clone_and_push(self) -> None:
        result = self.git("clone", "git@fake.host:repo.git", "work")
        self.assertEqual(result.returncode, 0, result.stderr)
        work = self.root / "work"
        self.assertEqual((work / "README").read_text(), "ssh 1\n")
        request = self.bridge.requests[0]
        self.assertEqual((request["host"], request["user"], request["port"]), ("fake.host", "git", None))
        self.assertEqual(request["command"], "git-upload-pack 'repo.git'")
        self.assertIn(request["protocol"], (0, 1, 2))

        (work / "more.txt").write_text("over ssh\n")
        self.assertEqual(self.git("add", "more.txt", cwd=work).returncode, 0)
        self.assertEqual(self.git("commit", "-m", "ssh push", cwd=work).returncode, 0)
        pushed = self.git("push", "origin", "main", cwd=work)
        self.assertEqual(pushed.returncode, 0, pushed.stderr)
        self.assertEqual(self.bridge.requests[-1]["command"], "git-receive-pack 'repo.git'")
        from dulwich.repo import Repo

        with Repo(str(work)) as local, Repo(str(self.root / "srv" / "repo.git")) as served:
            self.assertEqual(served.refs[b"refs/heads/main"], local.refs[b"refs/heads/main"])

    def test_ssh_url_with_port_passes_port_and_absolute_path(self) -> None:
        result = self.git("ls-remote", "ssh://deploy@fake.host:2222/repo.git")
        self.assertEqual(result.returncode, 0, result.stderr)
        request = self.bridge.requests[0]
        self.assertEqual((request["user"], request["port"], request["command"]), ("deploy", 2222, "git-upload-pack '/repo.git'"))
        self.assertIn("refs/heads/main", result.stdout)

    def test_refusal_message_is_shown(self) -> None:
        self.bridge.refusal = {"code": "SSH_HOST_NOT_CONFIGURED", "message": "没有与 git@fake.host 匹配的 SSH 主机。"}
        result = self.git("clone", "git@fake.host:repo.git", "work")
        self.assertEqual(result.returncode, infinity_git.FATAL_EXIT)
        self.assertIn("SSH_HOST_NOT_CONFIGURED", result.stderr)
        self.assertIn("没有与 git@fake.host 匹配的 SSH 主机", result.stderr)
        self.assertNotIn(NONCE, result.stderr)


if __name__ == "__main__":
    unittest.main()
