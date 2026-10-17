# Single entry point for running Valuation Loop on this box (no Docker). See ../HANDOFF.md §6.
SHELL := /bin/bash
export JAVA_HOME ?= $(HOME)/jdks/jdk-21.0.12.1+1
# the JDK 21 toolchain Gradle compiles and tests with (gradle.properties: installations.fromEnv), even when the
# Gradle daemon itself runs on another JDK
export JDK21_HOME ?= $(JAVA_HOME)
# Gradle's own JVM kept small: other sessions share this 15 GB box (a jvmargs change starts a separate daemon)
GRADLE := ./gradlew -Dorg.gradle.jvmargs="-Xmx768m -XX:MaxMetaspaceSize=384m"
APP_JAR := build/libs/valuation-loop-0.1.0-SNAPSHOT.jar

KAFKA_HOME ?= $(HOME)/tools/kafka_2.13-4.3.1
KAFKA_DATA ?= $(HOME)/tools/kafka-data
ES_HOME    ?= $(HOME)/tools/elasticsearch-8.19.4
PG_HOME    ?= $(HOME)/tools/postgres
PG_DATA    ?= $(HOME)/tools/pgdata
PG_PORT    ?= 5433
PG_DB      ?= valuation_loop
LOGS       := $(HOME)/tools/logs

.PHONY: help test test-es build jar run kafka-up kafka-down es-up es-down pg-up pg-down services-up services-down status load stats demo demo-reset replay clean-daemons page

# pgrep/pkill -f match the full command line, and the shell make starts for a recipe has the pattern in ITS command
# line too, so a plain pattern always finds that shell. "[k]afka" matches the text "kafka" but not the text "[k]afka".

help:
	@grep -E '^[a-z-]+:' Makefile | cut -d: -f1 | tr '\n' ' '; echo

# ---------- build ----------
test:
	$(GRADLE) test -q

test-es:                 # also runs the two live-Elasticsearch tests (needs make es-up)
	$(GRADLE) test -Pes=true -q

build:
	$(GRADLE) build -x test -q

jar:                     # the runnable jar make demo and make replay start (no Gradle daemon kept beside the app)
	$(GRADLE) bootJar -q

run:
	@free -m | awk 'NR==2 && $$7 < 2500 {print "WARNING: only "$$7" MB RAM available; close something first"}'
	PG_PASSWORD=$$(cat $(PG_PWFILE)) SPRING_PROFILES_ACTIVE=local,demo $(GRADLE) bootRun -q

clean-daemons:
	./gradlew --stop

# ---------- Kafka (KRaft, single node) ----------
kafka-up:
	@mkdir -p $(KAFKA_DATA) $(LOGS)
	@if [ ! -f $(KAFKA_DATA)/meta.properties ]; then \
	  sed -e 's#^log.dirs=.*#log.dirs=$(KAFKA_DATA)#' \
	      -e 's#^listeners=.*#listeners=PLAINTEXT://127.0.0.1:9092,CONTROLLER://127.0.0.1:9093#' \
	      -e 's#^advertised.listeners=.*#advertised.listeners=PLAINTEXT://127.0.0.1:9092#' \
	      -e 's#^controller.quorum.bootstrap.servers=.*#controller.quorum.bootstrap.servers=127.0.0.1:9093#' \
	      $(KAFKA_HOME)/config/server.properties > $(KAFKA_DATA)/server.properties; \
	  $(KAFKA_HOME)/bin/kafka-storage.sh format --standalone -t $$($(KAFKA_HOME)/bin/kafka-storage.sh random-uuid) -c $(KAFKA_DATA)/server.properties >/dev/null; \
	fi
	@pgrep -f "[k]afka.Kafka $(KAFKA_DATA)" >/dev/null && echo "kafka already running" || \
	  (KAFKA_HEAP_OPTS="-Xms256m -Xmx512m" nohup $(KAFKA_HOME)/bin/kafka-server-start.sh $(KAFKA_DATA)/server.properties > $(LOGS)/kafka.log 2>&1 & \
	   for i in $$(seq 1 30); do $(KAFKA_HOME)/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 >/dev/null 2>&1 && break; sleep 1; done; echo "kafka up on 9092")

kafka-down:   # waits (up to 30 s) until the broker has really exited, so a kafka-up right after does not race it
	@if pkill -f "[k]afka.Kafka $(KAFKA_DATA)"; then \
	  for i in $$(seq 1 30); do pgrep -f "[k]afka.Kafka $(KAFKA_DATA)" >/dev/null || break; sleep 1; done; \
	  if pgrep -f "[k]afka.Kafka $(KAFKA_DATA)" >/dev/null; then echo "kafka still running after 30 s"; exit 1; fi; \
	  echo "kafka stopped"; \
	else echo "kafka not running"; fi

# ---------- Elasticsearch (single node, security off for local) ----------
es-up:
	@mkdir -p $(LOGS)
	@pgrep -f "[o]rg.elasticsearch.bootstrap.Elasticsearch" >/dev/null && echo "es already running" || \
	  (ES_JAVA_OPTS="-Xms512m -Xmx1g" nohup $(ES_HOME)/bin/elasticsearch -E discovery.type=single-node -E network.host=127.0.0.1 -E xpack.security.enabled=false -E xpack.ml.enabled=false -E http.port=9200 > $(LOGS)/es.log 2>&1 & \
	   for i in $$(seq 1 60); do curl -s localhost:9200 >/dev/null 2>&1 && break; sleep 2; done; curl -s localhost:9200 | grep -o '"number" : "[^"]*"' | head -1; echo "es up on 9200")

es-down:      # waits (up to 30 s) until Elasticsearch has really exited
	@if pkill -f "[o]rg.elasticsearch.bootstrap.Elasticsearch"; then \
	  for i in $$(seq 1 30); do pgrep -f "[o]rg.elasticsearch.bootstrap.Elasticsearch" >/dev/null || break; sleep 1; done; \
	  if pgrep -f "[o]rg.elasticsearch.bootstrap.Elasticsearch" >/dev/null; then echo "es still running after 30 s"; exit 1; fi; \
	  echo "es stopped"; \
	else echo "es not running"; fi

# ---------- Postgres (user-space binaries, no root) ----------
# Listens on 127.0.0.1 only; TCP needs the scram password in $(PG_PWFILE) (mode 600, generated once);
# the unix socket lives inside the 0700 data dir so only this user can reach it.
PG_PWFILE ?= $(HOME)/tools/pg.pw
pg-up:
	@mkdir -p $(LOGS)
	@if [ ! -f $(PG_PWFILE) ]; then (umask 077; head -c 24 /dev/urandom | base64 | tr -d '/+=' > $(PG_PWFILE)); fi
	@if [ ! -f $(PG_DATA)/PG_VERSION ]; then \
	  $(PG_HOME)/bin/initdb -D $(PG_DATA) -U postgres --auth-local=peer --auth-host=scram-sha-256 --pwfile=$(PG_PWFILE) -E UTF8 >/dev/null && echo "initdb done"; \
	  echo "CREATE DATABASE $(PG_DB);" | $(PG_HOME)/bin/postgres --single -D $(PG_DATA) postgres >/dev/null 2>&1 && echo "database $(PG_DB) created"; \
	fi
	@$(PG_HOME)/bin/pg_ctl -D $(PG_DATA) status >/dev/null 2>&1 && echo "postgres already running" || \
	  $(PG_HOME)/bin/pg_ctl -D $(PG_DATA) -o "-p $(PG_PORT) -k $(PG_DATA) -c listen_addresses=127.0.0.1 -c unix_socket_permissions=0700" -l $(LOGS)/pg.log start >/dev/null
	@echo "postgres up on 127.0.0.1:$(PG_PORT), db $(PG_DB) (password in $(PG_PWFILE); the zonky binaries ship no psql, use the app or a JDBC client)"

pg-down:
	-$(PG_HOME)/bin/pg_ctl -D $(PG_DATA) stop -m fast >/dev/null && echo "postgres stopped"

services-up: pg-up kafka-up es-up
services-down: es-down kafka-down pg-down

# by port, not by process name: a pattern match can find the shell that asked (bash's /dev/tcp opens a connection)
status:
	@(exec 3<>/dev/tcp/127.0.0.1/9092) 2>/dev/null && echo "kafka: up" || echo "kafka: down"
	@(exec 3<>/dev/tcp/127.0.0.1/9200) 2>/dev/null && echo "es: up" || echo "es: down"
	@$(PG_HOME)/bin/pg_ctl -D $(PG_DATA) status >/dev/null 2>&1 && echo "postgres: up" || echo "postgres: down"
	@(exec 3<>/dev/tcp/127.0.0.1/8080) 2>/dev/null && echo "app: up on 8080" || echo "app: down"
	@free -m | awk 'NR==2{print "ram available: "$$7" MB"}'

# ---------- data and demo (filled in as the modules land) ----------
# loader profile: Elasticsearch only (in-memory H2, no Kafka, no web port); see application-loader.yml
load:
	SPRING_PROFILES_ACTIVE=loader $(GRADLE) bootRun -q --args="--loader.run=true"

stats:                   # the comparables-range width statistic (the README's number), from the loaded index
	SPRING_PROFILES_ACTIVE=loader $(GRADLE) bootRun -q --args="--loader.stats=true"

# services up, app started from the jar (local,demo), every scenario, comparison + scorecard + metrics printed, app
# stopped again; the services stay up. Details: scripts/demo.sh and docs/DEMO.md.
demo: services-up jar
	@LOGS=$(LOGS) APP_JAR=$(APP_JAR) PG_PWFILE=$(PG_PWFILE) scripts/demo.sh

# clean numbers: stop the demo app, Kafka and Postgres, delete $(KAFKA_DATA), $(PG_DATA) and the replay snapshot
# (not the Elasticsearch data, not $(PG_PWFILE)); the next make demo re-initialises both. scripts/demo-reset.sh
demo-reset:
	@LOGS=$(LOGS) PG_PWFILE=$(PG_PWFILE) KAFKA_DATA=$(KAFKA_DATA) PG_DATA=$(PG_DATA) scripts/demo-reset.sh

# rebuild scorecard_fact from the topics (ScorecardReplay): snapshot, truncate, reset offsets, replay, compare, exit
replay: services-up jar
	@LOGS=$(LOGS) APP_JAR=$(APP_JAR) PG_PWFILE=$(PG_PWFILE) scripts/replay.sh

# ---------- split run: the same jar as two processes (docs/DEMO.md "Split run", scripts/split.sh) ----------
# api: 8080/8081, everything but delivery. worker: 8090/8091, DeliverLead listener + DLT, deadline scanner, outbox relay.
# Background processes with pidfiles $(LOGS)/api.pid and $(LOGS)/worker.pid; scripts/split.sh stop stops them.
.PHONY: run-api run-worker run-split demo-split
SPLIT_ENV = LOGS=$(LOGS) APP_JAR=$(APP_JAR) PG_PWFILE=$(PG_PWFILE)

run-api: jar
	@$(SPLIT_ENV) scripts/split.sh api

run-worker: jar
	@$(SPLIT_ENV) scripts/split.sh worker

run-split: jar
	@$(SPLIT_ENV) scripts/split.sh both

# services up, both processes up, every scenario against the api, report + worker metrics, both stopped again
demo-split: services-up jar
	@$(SPLIT_ENV) scripts/split.sh demo

# the seller side-by-side page (static, served by the app under every profile): make page ID=<requestId>
page:
	@echo "http://127.0.0.1:8080/comparison.html?requestId=$(or $(ID),<id>)"
