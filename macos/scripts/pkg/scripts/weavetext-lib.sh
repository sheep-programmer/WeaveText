# shellcheck shell=bash
# 安装包 preinstall / postinstall 共用的函数（以 root 身份由「安装器」运行）。
# Functions shared by the package's preinstall / postinstall (run as root by Installer).
#
# 与系统打交道的几处（控制台用户、uid、家目录、进程、以用户身份运行）都是单独的小函数，测试里换成桩。
# Every touch of the system (console user, uid, home, processes, running as the user) is a small function of its own
# that the tests replace with a stub.

WEAVE_BUNDLE_ID="com.weavetext.inputmethod.WeaveText"
WEAVE_BUNDLE_NAME="WeaveText.app"
WEAVE_EXECUTABLE="Contents/MacOS/WeaveText"
# 等旧副本退出的次数（每次 0.1 秒）。 How many 0.1 s ticks to wait for old copies to quit.
WEAVE_QUIT_TICKS="${WEAVE_QUIT_TICKS:-30}"

# 输出进 /var/log/install.log。 Output goes to /var/log/install.log.
weave_log() { echo "WeaveText: $*"; }

# /dev/console 的属主，就是正在前台登录的用户。 The owner of /dev/console is the user logged in at the screen.
console_owner() { /usr/bin/stat -f%Su /dev/console 2>/dev/null; }

# 前台用户名；登录窗口、设置助理或没人登录时失败。 The console user; fails at the login window, in Setup Assistant or
# when nobody is logged in.
console_user() {
  local user
  user="$(console_owner)"
  case "$user" in
    "" | root | loginwindow | _mbsetupuser) return 1 ;;
  esac
  printf '%s\n' "$user"
}

user_uid() { /usr/bin/id -u "$1" 2>/dev/null; }

user_home() {
  /usr/bin/dscl . -read "/Users/$1" NFSHomeDirectory 2>/dev/null | /usr/bin/sed -n 's/^NFSHomeDirectory: //p'
}

# 以该用户身份、在他的图形会话里运行（输入源接口要求在用户的会话里调用）。
# Run as that user inside their GUI session (the input-source API must be called from the user's session).
as_user() {
  local user="$1" uid="$2"
  shift 2
  /bin/launchctl asuser "$uid" /usr/bin/sudo -u "$user" -H "$@"
}

# 该用户下按包标识找到的织文进程号，外加按可执行文件路径找到的（旧版本或没登记到启动服务的）。
# The user's WeaveText processes found by bundle id, plus those matched by executable path (old or unregistered ones).
weave_pids() {
  local user="$1" uid="$2" asn
  for asn in $(as_user "$user" "$uid" /usr/bin/lsappinfo find "bundleid=$WEAVE_BUNDLE_ID" 2>/dev/null); do
    as_user "$user" "$uid" /usr/bin/lsappinfo info -only pid "$asn" 2>/dev/null | /usr/bin/sed -n 's/.*"pid"=\([0-9]*\).*/\1/p'
  done
  /usr/bin/pgrep -u "$uid" -f "/$WEAVE_BUNDLE_NAME/$WEAVE_EXECUTABLE" 2>/dev/null
}

signal_pids() { /bin/kill "-$1" "${@:2}" 2>/dev/null; }

pause_tick() { /bin/sleep 0.1; }

# 请该用户的织文退出（新版收到 TERM 会先写回用户词），等不到就强制结束。系统切到织文时会重新拉起新装的那份。
# Ask the user's WeaveText to quit (newer versions write the user words back on TERM), force it if it won't. The system
# starts the newly installed copy the next time 织文 is selected.
quit_running_copies() {
  local user="$1" uid="$2" pids tick
  pids="$(weave_pids "$user" "$uid" | /usr/bin/sort -u)"
  [[ -z "$pids" ]] && return 0
  weave_log "quitting running copies: $(echo $pids)"
  # shellcheck disable=SC2086
  signal_pids TERM $pids
  for ((tick = 0; tick < WEAVE_QUIT_TICKS; tick++)); do
    pids="$(weave_pids "$user" "$uid" | /usr/bin/sort -u)"
    [[ -z "$pids" ]] && return 0
    pause_tick
  done
  weave_log "forcing: $(echo $pids)"
  # shellcheck disable=SC2086
  signal_pids KILL $pids
  return 0
}

# 删掉该用户「输入法」文件夹里的旧副本（以前的安装方式），免得两份抢同一个输入源。只删真正的目录，不跟随符号链接；
# 用户词在 Application Support 里，不受影响。
# Remove the old copy in the user's Input Methods folder (the previous way of installing) so two copies don't fight
# over one input source. Only a real directory is removed, never through a symlink; user words live in Application
# Support and are untouched.
remove_user_copy() {
  local home="$1" copy
  [[ -n "$home" && -d "$home" ]] || return 0
  copy="$home/Library/Input Methods/$WEAVE_BUNDLE_NAME"
  if [[ -L "$copy" ]]; then
    weave_log "leaving symlink $copy alone"
    return 0
  fi
  if [[ -d "$copy" ]]; then
    weave_log "removing the per-user copy $copy"
    /bin/rm -rf "$copy"
  fi
  return 0
}

# 包里的程序装到的位置。 Where the payload puts the app.
installed_bundle() { printf '%s/Library/Input Methods/%s\n' "${1%/}" "$WEAVE_BUNDLE_NAME"; }

# preinstall 的参数：$1 包路径，$2 安装位置，$3 目标卷。 preinstall arguments: package, install location, target volume.
preinstall_main() {
  local target="${3:-/}" user uid home
  if [[ "$target" != "/" ]]; then
    weave_log "target volume $target is not the startup disk; nothing to prepare"
    return 0
  fi
  if ! user="$(console_user)"; then
    weave_log "no console user; nothing to quit"
    return 0
  fi
  uid="$(user_uid "$user")" || return 0
  quit_running_copies "$user" "$uid"
  home="$(user_home "$user")"
  remove_user_copy "$home"
  return 0
}

# postinstall：去掉隔离属性，再以前台用户的身份登记、启用并选中「织文拼音」。登记失败不让整个安装失败——文件已经装好，
# 注销再登录后系统同样会列出它。
# postinstall: strip the quarantine attribute, then register, enable and select 织文拼音 as the console user. A failed
# registration does not fail the install: the files are in place and the system lists them after logging out and in.
postinstall_main() {
  local target="${3:-/}" app user uid
  app="$(installed_bundle "$target")"
  if [[ ! -d "$app" ]]; then
    weave_log "missing $app"
    return 1
  fi
  /usr/bin/xattr -dr com.apple.quarantine "$app" 2>/dev/null || true
  if [[ "$target" != "/" ]]; then
    weave_log "installed on $target; it is registered when that disk is started"
    return 0
  fi
  if ! user="$(console_user)"; then
    weave_log "no console user; 织文拼音 shows up after the next login"
    return 0
  fi
  if ! uid="$(user_uid "$user")"; then
    weave_log "no uid for $user"
    return 0
  fi
  if as_user "$user" "$uid" "$app/$WEAVE_EXECUTABLE" --register; then
    weave_log "registered for $user"
  else
    weave_log "registering for $user did not finish (status $?); see ~/Library/Logs/WeaveText-install.log"
  fi
  return 0
}
