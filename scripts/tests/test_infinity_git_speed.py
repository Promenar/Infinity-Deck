"""Git 状态提速的只读和结果一致性回归测试。"""

from pathlib import Path
import os
import hashlib
import subprocess
import sys
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import test_infinity_git as fixtures


class GitSpeedTest(unittest.TestCase):
    setUp = fixtures.InspectTest.setUp
    init = fixtures.InspectTest.init
    write = fixtures.InspectTest.write
    add = fixtures.InspectTest.add
    commit = fixtures.InspectTest.commit
    rm = fixtures.InspectTest.rm
    inspect = fixtures.InspectTest.inspect
    paths = fixtures.InspectTest.paths
    configure_tracking = fixtures.InspectTest.configure_tracking
    fork_commit = fixtures.InspectTest.fork_commit

    def test_status_reports_each_timing_without_changing_git_files(self):
        self.init()
        self.write("tracked.txt", "line\n")
        self.add("tracked.txt")
        self.commit("base")
        before = {p.relative_to(self.work): p.read_bytes() for p in (self.work / ".git").rglob("*") if p.is_file()}
        status = self.inspect("status")
        expected = {"startup", "imports", "index", "staged", "unstaged", "untracked", "tracking", "lines", "total"}
        self.assertTrue(expected.issubset(status["timings_ms"]))
        self.assertTrue(all(isinstance(status["timings_ms"][key], (int, float)) and status["timings_ms"][key] >= 0 for key in expected))
        after = {p.relative_to(self.work): p.read_bytes() for p in (self.work / ".git").rglob("*") if p.is_file()}
        self.assertEqual(before, after)

    def test_stat_cache_reuses_hash_and_rechecks_changed_size_or_mtime(self):
        from dulwich import index as index_module
        from dulwich.repo import Repo

        self.init()
        path = self.write("tracked.txt", "one\n")
        self.add("tracked.txt")
        self.commit("base")
        os.utime(path, ns=(path.stat().st_atime_ns, path.stat().st_mtime_ns - 10_000_000_000))
        cache = self.root / "cache"
        with Repo(str(self.work)) as repo:
            with mock.patch.object(index_module, "blob_from_path_and_stat", wraps=index_module.blob_from_path_and_stat) as read:
                first = fixtures.infinity_git._inspect_status(repo, 100, cache_dir=str(cache))
                self.assertEqual(read.call_count, 1)
                second = fixtures.infinity_git._inspect_status(repo, 100, cache_dir=str(cache))
                self.assertEqual(read.call_count, 1)
                self.assertEqual(self.paths(first, "unstaged"), self.paths(second, "unstaged"))
                path.write_text("changed length\n")
                changed = fixtures.infinity_git._inspect_status(repo, 100, cache_dir=str(cache))
                self.assertEqual(read.call_count, 2)
                self.assertEqual(self.paths(changed, "unstaged"), {"tracked.txt": "modified"})
                os.utime(path, ns=(path.stat().st_atime_ns, path.stat().st_mtime_ns + 2_000_000_000))
                fixtures.infinity_git._inspect_status(repo, 100, cache_dir=str(cache))
                self.assertEqual(read.call_count, 3)

    def test_optimized_untracked_matches_legacy_with_nested_ignores(self):
        self.init()
        self.write("tracked.txt", "base\n")
        self.write("old.txt", "rename\n")
        self.write(".gitignore", "ignored/\n*.log\nnested/*\n!nested/keep.txt\n")
        self.add("tracked.txt", "old.txt", ".gitignore")
        self.commit("base")
        self.write("tracked.txt", "modified\n")
        self.write("new/sub.txt", "new\n")
        self.write("renamed/new.txt", "rename\n")
        self.rm("old.txt")
        (self.work / "old.txt").unlink()
        self.add("renamed/new.txt")
        self.write("ignored/file.txt", "ignored\n")
        self.write("nested/keep.txt", "keep\n")
        self.write("nested/drop.log", "ignored\n")
        optimized = self.inspect("status")
        legacy = self.inspect("status", "--legacy-untracked")
        for group in ("staged", "unstaged", "untracked"):
            self.assertEqual(optimized[group], legacy[group])

    def test_tracking_cache_matches_legacy_after_tips_change(self):
        from dulwich.repo import Repo

        self.init()
        base = self.commit("base")
        self.commit("local")
        remote = self.fork_commit([base], "remote")
        self.configure_tracking()
        with Repo(str(self.work)) as repo:
            repo.refs[b"refs/remotes/origin/main"] = remote.encode()
        args = ("--cache-dir", str(self.root / "cache"))
        for _ in range(2):
            optimized = self.inspect("status", *args)
            legacy = self.inspect("status", "--legacy-tracking")
            self.assertEqual((optimized["ahead"], optimized["behind"], optimized["tracking_truncated"]),
                             (legacy["ahead"], legacy["behind"], legacy["tracking_truncated"]))
        self.commit("new local")
        optimized = self.inspect("status", *args)
        self.assertEqual((optimized["ahead"], optimized["behind"]), (2, 1))

    def test_shared_ctime_drift_does_not_hash_unchanged_file(self):
        from dulwich import index as index_module
        from dulwich.repo import Repo

        self.init()
        path = self.write("tracked.txt", "same\n")
        self.add("tracked.txt")
        self.commit("base")
        os.chmod(path, path.stat().st_mode ^ 0o100)
        with Repo(str(self.work)) as repo:
            with mock.patch.object(index_module, "blob_from_path_and_stat", wraps=index_module.blob_from_path_and_stat) as read:
                with mock.patch.object(fixtures.infinity_git, "_workspace_kind", return_value="shared"):
                    status = fixtures.infinity_git._inspect_status(repo, 100)
                self.assertEqual(self.paths(status, "unstaged"), {})
                self.assertEqual(read.call_count, 0)
                with mock.patch.object(fixtures.infinity_git, "_workspace_kind", return_value="project"):
                    fixtures.infinity_git._inspect_status(repo, 100)
                self.assertEqual(read.call_count, 1)

    def test_corrupt_cache_and_external_index_change_force_rehash(self):
        from dulwich import index as index_module
        from dulwich.repo import Repo

        self.init()
        path = self.write("tracked.txt", "same\n")
        self.add("tracked.txt")
        self.commit("base")
        os.utime(path, ns=(path.stat().st_atime_ns, path.stat().st_mtime_ns - 10_000_000_000))
        cache = self.root / "cache"
        with Repo(str(self.work)) as repo:
            with mock.patch.object(index_module, "blob_from_path_and_stat", wraps=index_module.blob_from_path_and_stat) as read:
                fixtures.infinity_git._inspect_status(repo, 100, cache_dir=str(cache))
                self.assertEqual(read.call_count, 1)
                cache_file = next(cache.glob("*/files.json"))
                cache_file.write_text("{broken")
                fixtures.infinity_git._inspect_status(repo, 100, cache_dir=str(cache))
                self.assertEqual(read.call_count, 2)
                self.write("new.txt", "new\n")
                self.add("new.txt")
                before_inspect = read.call_count
                fixtures.infinity_git._inspect_status(repo, 100, cache_dir=str(cache))
                self.assertEqual(read.call_count, before_inspect + 1)

    def test_status_does_not_import_porcelain_and_rejects_cache_under_git(self):
        self.init()
        self.write("tracked.txt", "a\n")
        self.add("tracked.txt")
        self.commit("base")
        before = {p.relative_to(self.work): p.read_bytes() for p in (self.work / ".git").rglob("*") if p.is_file()}
        status = self.inspect("status", "--cache-dir", str(self.work / ".git" / "cache"))
        self.assertNotIn("error", status)
        after = {p.relative_to(self.work): p.read_bytes() for p in (self.work / ".git").rglob("*") if p.is_file()}
        self.assertEqual(before, after)
        env = dict(os.environ, PYTHONPATH=os.pathsep.join([str(fixtures.SITE_DIR), self.dulwich_root]))
        check = subprocess.run(
            [sys.executable, "-c", "import sys; import infinity_git; from dulwich.repo import Repo; "
             "r=Repo(sys.argv[1]); infinity_git._inspect_status(r, 100); r.close(); "
             "assert 'dulwich.porcelain' not in sys.modules", str(self.work)],
            env=env, capture_output=True, text=True, timeout=30,
        )
        self.assertEqual(check.returncode, 0, check.stderr)

    def test_cache_subdirectory_symlink_into_git_is_not_written(self):
        self.init()
        self.write("tracked.txt", "a\n")
        self.add("tracked.txt")
        self.commit("base")
        cache = self.root / "cache"
        cache.mkdir()
        identity = hashlib.sha256(os.fsencode(os.path.realpath(self.work))).hexdigest()
        (cache / identity).symlink_to(self.work / ".git", target_is_directory=True)
        before = {p.relative_to(self.work): p.read_bytes() for p in (self.work / ".git").rglob("*") if p.is_file()}
        result = self.inspect("status", "--cache-dir", str(cache))
        self.assertNotIn("error", result)
        after = {p.relative_to(self.work): p.read_bytes() for p in (self.work / ".git").rglob("*") if p.is_file()}
        self.assertEqual(before, after)

    def test_tracking_matches_reachability_for_diverged_and_merged_histories(self):
        import random

        class Provider:
            def __init__(self, graph):
                self.graph = graph

            def get_parents(self, sha):
                return self.graph[sha]

        class Config:
            def get(self, section, name):
                return {b"remote": b".", b"merge": b"refs/heads/upstream"}[name]

        class Repo:
            def __init__(self, graph, upstream):
                self.graph = graph
                self.refs = {b"refs/heads/upstream": upstream}

            def parents_provider(self):
                return Provider(self.graph)

            def get_config_stack(self):
                return Config()

        def reachable(graph, start):
            seen, todo = set(), [start]
            while todo:
                node = todo.pop()
                if node not in seen:
                    seen.add(node)
                    todo.extend(graph[node])
            return seen

        randomizer = random.Random(20261105)
        for _ in range(300):
            nodes = [str(i).encode() for i in range(30)]
            graph = {nodes[0]: []}
            for index in range(1, len(nodes)):
                count = randomizer.randrange(3)
                graph[nodes[index]] = randomizer.sample(nodes[:index], min(count, index))
            left, right = randomizer.sample(nodes, 2)
            local, other = reachable(graph, left), reachable(graph, right)
            status = fixtures.infinity_git._TrackingGraph(Repo(graph, right)).tracking(b"main", left)
            self.assertEqual((status["ahead"], status["behind"], status["tracking_truncated"]),
                             (len(local - other), len(other - local), False))

    def test_linear_tracking_stops_at_meeting_point(self):
        class Provider:
            def get_parents(self, sha):
                number = int(sha)
                return [str(number - 1).encode()] if number else []

        class Repo:
            refs = {b"refs/heads/upstream": b"98"}
            def parents_provider(self):
                return Provider()
            def get_config_stack(self):
                class Config:
                    def get(self, section, name):
                        return {b"remote": b".", b"merge": b"refs/heads/upstream"}[name]
                return Config()

        with mock.patch.object(fixtures.infinity_git, "INSPECT_MAX_GRAPH_COMMITS", 2):
            result = fixtures.infinity_git._TrackingGraph(Repo()).tracking(b"main", b"99")
            self.assertEqual((result["ahead"], result["behind"], result["tracking_truncated"]), (1, 0, False))
