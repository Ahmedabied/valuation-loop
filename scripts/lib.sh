# Shared helpers for scripts/demo.sh and scripts/replay.sh (sourced, not run). Plain bash, no dependencies.
API=http://127.0.0.1:8080
ACTUATOR=http://127.0.0.1:8081
LOGS=${LOGS:-$HOME/tools/logs}
PIDFILE=$LOGS/app.pid
APP_JAR=${APP_JAR:-build/libs/valuation-loop-0.1.0-SNAPSHOT.jar}
APP_JAVA_OPTS=${APP_JAVA_OPTS:--Xms128m -Xmx512m -XX:MaxMetaspaceSize=256m}
PG_PWFILE=${PG_PWFILE:-$HOME/tools/pg.pw}

# true when something listens on 127.0.0.1:$1 (bash opens a TCP connection; no extra tools needed)
port_in_use() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

# the pid in app.pid, if that process is still alive
app_pid() { [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null && cat "$PIDFILE"; }

# Waits (3 x 60 s at most) while available RAM is under 2500 MB: other sessions share this box.
wait_for_ram() {
  for i in 1 2 3 4; do
    avail=$(free -m | awk 'NR==2{print $7}')
    [ "$avail" -ge 2500 ] && { echo "ram available: $avail MB"; return 0; }
    [ "$i" -eq 4 ] && { echo "only $avail MB RAM available after 3 minutes; not starting the app" >&2; return 1; }
    echo "only $avail MB RAM available; waiting 60 s ($i/3)"; sleep 60
  done
}

# Starts the app jar in the background under local,demo with logs to app.log; extra args are passed to Spring.
start_app() {
  mkdir -p "$LOGS"
  rm -f "$PIDFILE"
  PG_PASSWORD=$(cat "$PG_PWFILE") nohup "$JAVA_HOME/bin/java" $APP_JAVA_OPTS -jar "$APP_JAR" \
      --spring.profiles.active=local,demo "$@" > "$LOGS/app.log" 2>&1 &
  echo $! > "$PIDFILE"
}

# Waits for actuator health UP (90 s); fails early, with the end of the log, if the process dies.
wait_for_health() {
  for i in $(seq 1 90); do
    curl -s "$ACTUATOR/actuator/health" 2>/dev/null | grep -q '"status":"UP"' && { echo "app healthy after ${i} s"; return 0; }
    if ! app_pid >/dev/null; then echo "the app exited during startup; last log lines:" >&2; tail -20 "$LOGS/app.log" >&2; return 1; fi
    sleep 1
  done
  echo "app not healthy after 90 s; see $LOGS/app.log" >&2; return 1
}

# Stops the app named in app.pid (only ever one this script started), waiting up to 30 s for a clean shutdown.
stop_app() {
  pid=$(app_pid) || { rm -f "$PIDFILE"; return 0; }
  kill "$pid"
  for i in $(seq 1 30); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
  kill -0 "$pid" 2>/dev/null && { echo "app $pid did not stop in 30 s; sending SIGKILL"; kill -9 "$pid"; }
  rm -f "$PIDFILE"
  echo "app stopped (pid $pid)"
}
