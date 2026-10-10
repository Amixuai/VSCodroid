package com.vscodroid.setup

/**
 * The shell text that lets `npm install` and `npm ci` finish on shared storage.
 *
 * The workspace is `/storage/emulated/0/VSCodroid/projects`, and shared storage
 * is served through FUSE, which has no `symlink(2)`. npm writes
 * `node_modules/.bin/<name>` as a link for every package that ships an
 * executable, so the first such package killed the install with EPERM.
 *
 * Two halves, and neither works alone:
 *
 *  1. **[NOLINKS_FUNCTION]** decides, per command, whether npm is told not to
 *     write links. The answer comes from asking the folder (one `ln -s` probe),
 *     not from matching a path, so a folder that can hold links keeps them and
 *     `npm install -g`, which writes into the app's own `usr/` where links work
 *     and are needed, is never touched. When it says yes, npm runs with
 *     `npm_config_bin_links=false` (the environment spelling of `--no-bin-links`)
 *     and `npm_config_install_links=true` (a `file:` dependency is copied rather
 *     than linked). An environment variable instead of a literal flag, because it
 *     reaches the npm a lifecycle script starts as well, and because it adds no
 *     argument for a caller to see.
 *
 *  2. **[BIN_FALLBACK_FUNCTIONS]** is what runs the tools that now have no
 *     `.bin` entry. npm puts `node_modules/.bin` on PATH for `npm run`, and with
 *     no links there the script's `tsc` or `vite` is "command not found". Bash
 *     asks `command_not_found_handle` before giving up, so the handler reads the
 *     `bin` field out of the installed packages' own `package.json` and runs the
 *     file it names with the interpreter its first line asks for. That is the
 *     same thing the link would have led to, reached without the link. The
 *     handler is defined where `npm()` is, so it is in `.bashrc` for a terminal
 *     and in the `BASH_ENV` file for an npm script, whose shell is the bundled
 *     bash (`script-shell` in `.npmrc`).
 *
 * What this does not cover, so nobody assumes it does: npm workspaces and
 * `npm link`, which exist to make links; pnpm, which is built on them; and a
 * caller that is neither bash nor npm, such as an extension spawning `tsc` by
 * name, which has no PATH entry to find and no shell to ask.
 *
 * The text is POSIX `sh` wherever [NOLINKS_FUNCTION] is concerned, because the
 * launcher the exec trampoline runs for a non-bash caller uses it too, under
 * the system shell. [BIN_FALLBACK_FUNCTIONS] is bash only.
 *
 * Written with `§` for the shell's dollar sign so a Kotlin raw string does not
 * read `§__vn_rc` as one of its own templates.
 */
internal object NpmBinLinks {

    /**
     * Opens [NOLINKS_FUNCTION]. [FirstRunSetup.createNpmWrappers] looks for it to
     * decide whether a `.bashrc` already carries the current block, so it has to
     * change whenever the block must reach installs that already have an older one.
     */
    const val BLOCK_MARKER = "__vscodroid_npm_nolinks()"

    /**
     * `__vscodroid_npm_nolinks ARGS...` returns 0 when npm should be run without
     * links: the command is not global, and the current folder cannot hold a
     * symbolic link. Returns 1 otherwise, including when the folder is not
     * writable, since a probe that fails for that reason says nothing about links.
     *
     * Arguments after `--` belong to a script (`npm run x -- -g`) and are not read.
     * `VSCODROID_KEEP_BIN_LINKS` set to anything is the way back for a user who
     * knows their folder is fine.
     */
    val NOLINKS_FUNCTION: String = """
__vscodroid_npm_nolinks() {
    __vn_rc=0
    if [ -n "§{VSCODROID_KEEP_BIN_LINKS-}" ]; then
        __vn_rc=1
    else
        __vn_prev=
        for __vn_arg in "§@"; do
            case "§__vn_arg" in
                --) break ;;
                -g|--global|--location=global) __vn_rc=1; break ;;
                global) if [ "§__vn_prev" = "--location" ]; then __vn_rc=1; break; fi ;;
            esac
            __vn_prev=§__vn_arg
        done
        if [ "§__vn_rc" -eq 0 ]; then
            if [ -w . ]; then
                __vn_probe=".vscodroid-symlink-probe.§§"
                if ln -s . "§__vn_probe" 2>/dev/null; then
                    rm -f "§__vn_probe" 2>/dev/null
                    __vn_rc=1
                fi
            else
                __vn_rc=1
            fi
        fi
    fi
    set -- "§__vn_rc"
    unset __vn_rc __vn_prev __vn_arg __vn_probe
    return "§1"
}
""".replace('§', '$')

    /**
     * Bash only. `__vscodroid_find_local_bin NAME` prints the file the installed
     * package that provides command NAME points its `bin` at, searching
     * `node_modules` from the current folder upward, or prints nothing and fails.
     * `__vscodroid_run_bin_file FILE ARGS...` runs it. `command_not_found_handle`
     * ties them together for a bare command name.
     *
     * The lookup is an index of every package's `bin`, kept in
     * `node_modules/.vscodroid-bin-index.json` and rebuilt when the top-level
     * `node_modules` directory changes, so the cost of scanning a thousand
     * `package.json` files over FUSE is paid once per install and not once per
     * command. A hit whose file is gone forces a rebuild before it is believed.
     *
     * The handler guards the lookup against calling itself: if `node` were the
     * missing command, resolving it would need it. The guard covers the lookup only,
     * never the tool that is then run, so it is not exported into the tool's own
     * children.
     */
    val BIN_FALLBACK_FUNCTIONS: String = """
# Tools whose node_modules/.bin link was never written (shared storage cannot hold
# one): found through the package's own "bin" entry instead, and run directly.
__vscodroid_find_local_bin() {
    command -v node >/dev/null 2>&1 || return 1
    node - "§1" <<'VSCODROID_BIN_JS'
var fs = require("fs"), path = require("path");
var want = process.argv[2];
function bins(pkgDir) {
  var out = {}, p;
  try { p = JSON.parse(fs.readFileSync(path.join(pkgDir, "package.json"), "utf8")); } catch (e) { return out; }
  var b = p.bin;
  if (!b) return out;
  if (typeof b === "string") {
    var n = String(p.name || "").split("/").pop();
    if (n) out[n] = b;
  } else if (typeof b === "object") {
    Object.keys(b).forEach(function (k) { if (typeof b[k] === "string") out[k] = b[k]; });
  }
  Object.keys(out).forEach(function (k) { out[k] = path.resolve(pkgDir, out[k]); });
  return out;
}
function scan(nm) {
  var index = {}, names;
  try { names = fs.readdirSync(nm); } catch (e) { return index; }
  names.forEach(function (name) {
    if (name.charAt(0) === ".") return;
    var dirs = [];
    if (name.charAt(0) === "@") {
      try { fs.readdirSync(path.join(nm, name)).forEach(function (s) { dirs.push(path.join(nm, name, s)); }); } catch (e) {}
    } else {
      dirs.push(path.join(nm, name));
    }
    dirs.forEach(function (d) {
      var o = bins(d);
      Object.keys(o).forEach(function (k) { if (!Object.prototype.hasOwnProperty.call(index, k)) index[k] = o[k]; });
    });
  });
  return index;
}
function has(index, k) { return !!index && Object.prototype.hasOwnProperty.call(index, k); }
function lookup(nm) {
  var cache = path.join(nm, ".vscodroid-bin-index.json"), stamp, index = null;
  try { stamp = String(fs.statSync(nm).mtimeMs); } catch (e) { return null; }
  try { var c = JSON.parse(fs.readFileSync(cache, "utf8")); if (c.stamp === stamp) index = c.bins; } catch (e) {}
  var hit = has(index, want) ? index[want] : null;
  if (!index || (hit && !fs.existsSync(hit))) {
    index = scan(nm);
    try {
      fs.writeFileSync(cache, JSON.stringify({ stamp: stamp, bins: index }));
      stamp = String(fs.statSync(nm).mtimeMs);
      fs.writeFileSync(cache, JSON.stringify({ stamp: stamp, bins: index }));
    } catch (e) {}
    hit = has(index, want) ? index[want] : null;
  }
  return hit;
}
var dir = process.cwd();
for (;;) {
  var found = lookup(path.join(dir, "node_modules"));
  if (found && fs.existsSync(found)) { process.stdout.write(found + "\n"); process.exit(0); }
  var up = path.dirname(dir);
  if (up === dir) break;
  dir = up;
}
process.exit(1);
VSCODROID_BIN_JS
}

__vscodroid_run_bin_file() {
    local __vr_file="§1" __vr_first=
    shift
    IFS= read -r __vr_first < "§__vr_file" 2>/dev/null || __vr_first=
    case "§__vr_first" in
        '#!'*node*) node "§__vr_file" "§@" ;;
        '#!'*python*) python3 "§__vr_file" "§@" ;;
        '#!'*bash*|'#!'*sh*) bash "§__vr_file" "§@" ;;
        *) node "§__vr_file" "§@" ;;
    esac
}

command_not_found_handle() {
    if [ -z "§{__VSCODROID_IN_CNF-}" ]; then
        case "§1" in
            ""|*/*) ;;
            *)
                local __cnf_name="§1" __cnf_file
                __cnf_file="§(__VSCODROID_IN_CNF=1 __vscodroid_find_local_bin "§__cnf_name" 2>/dev/null)"
                if [ -n "§__cnf_file" ]; then
                    shift
                    # Not guarded: the tool is free to need the handler itself, and an
                    # assignment here would be exported into everything it starts.
                    __vscodroid_run_bin_file "§__cnf_file" "§@"
                    return §?
                fi
                ;;
        esac
    fi
    echo "bash: §1: command not found" >&2
    return 127
}
""".replace('§', '$')
}
