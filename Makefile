# Single entry point for running Valuation Loop on this box (no Docker). See ../HANDOFF.md §6.
SHELL := /bin/bash
export JAVA_HOME ?= $(HOME)/jdks/jdk-21.0.12.1+1

KAFKA_HOME ?= $(HOME)/tools/kafka_2.13-4.3.1
KAFKA_DATA ?= $(HOME)/tools/kafka-data
ES_HOME    ?= $(HOME)/tools/elasticsearch-8.19.4
PG_HOME    ?= $(HOME)/tools/postgres
PG_DATA    ?= $(HOME)/tools/pgdata
PG_PORT    ?= 5433
PG_DB      ?= valuation_loop
LOGS       := $(HOME)/tools/logs

.PHONY: help test test-es build run kafka-up kafka-down es-up es-down pg-up pg-down services-up services-down status load stats demo replay clean-daemons

# pgrep/pkill -f match the full command line, and the shell make starts for a recipe has the pattern in ITS command
# line too, so a plain pattern always finds that shell. "[k]afka" matches the text "kafka" but not the text "[k]afka".

help:
	@grep -E '^[a-z-]+:' Makefile | cut -d: -f1 | tr '\n' ' '; echo

# ---------- build ----------
test:
	./gradlew test -q

test-es:                 # also runs the two live-Elasticsearch tests (needs make es-up)
	./gradlew test -Pes=true -q

build:
	./gradlew build -x test -q

run:
	@free -m | awk 'NR==2 && $$7 < 2500 {print "WARNING: only "$$7" MB RAM available; close something first"}'
	PG_PASSWORD=$$(cat $(PG_PWFILE)) SPRING_PROFILES_ACTIVE=local,demo ./gradlew bootRun -q

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
	  $(KAFKA_HOME)/bin/kafka-storage.sh format -t $$($(KAFKA_HOME)/bin/kafka-storage.sh random-uuid) -c $(KAFKA_DATA)/server.properties >/dev/null; \
	fi
	@pgrep -f "[k]afka.Kafka $(KAFKA_DATA)" >/dev/null && echo "kafka already running" || \
	  (KAFKA_HEAP_OPTS="-Xms256m -Xmx512m" nohup $(KAFKA_HOME)/bin/kafka-server-start.sh $(KAFKA_DATA)/server.properties > $(LOGS)/kafka.log 2>&1 & \
	   for i in $$(seq 1 30); do $(KAFKA_HOME)/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 >/dev/null 2>&1 && break; sleep 1; done; echo "kafka up on 9092")

kafka-down:
	@pkill -f "[k]afka.Kafka $(KAFKA_DATA)" && echo "kafka stopped" || echo "kafka not running"

# ---------- Elasticsearch (single node, security off for local) ----------
es-up:
	@mkdir -p $(LOGS)
	@pgrep -f "[o]rg.elasticsearch.bootstrap.Elasticsearch" >/dev/null && echo "es already running" || \
	  (ES_JAVA_OPTS="-Xms512m -Xmx1g" nohup $(ES_HOME)/bin/elasticsearch -E discovery.type=single-node -E network.host=127.0.0.1 -E xpack.security.enabled=false -E xpack.ml.enabled=false -E http.port=9200 > $(LOGS)/es.log 2>&1 & \
	   for i in $$(seq 1 60); do curl -s localhost:9200 >/dev/null 2>&1 && break; sleep 2; done; curl -s localhost:9200 | grep -o '"number" : "[^"]*"' | head -1; echo "es up on 9200")

es-down:
	@pkill -f "[o]rg.elasticsearch.bootstrap.Elasticsearch" && echo "es stopped" || echo "es not running"

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

status:
	@pgrep -f "[k]afka.Kafka" >/dev/null && echo "kafka: up" || echo "kafka: down"
	@curl -s localhost:9200 >/dev/null 2>&1 && echo "es: up" || echo "es: down"
	@$(PG_HOME)/bin/pg_ctl -D $(PG_DATA) status >/dev/null 2>&1 && echo "postgres: up" || echo "postgres: down"
	@free -m | awk 'NR==2{print "ram available: "$$7" MB"}'

# ---------- data and demo (filled in as the modules land) ----------
# loader profile: Elasticsearch only (in-memory H2, no Kafka, no web port); see application-loader.yml
load:
	SPRING_PROFILES_ACTIVE=loader ./gradlew bootRun -q --args="--loader.run=true"

stats:                   # the comparables-range width statistic (the README's number), from the loaded index
	SPRING_PROFILES_ACTIVE=loader ./gradlew bootRun -q --args="--loader.stats=true"

demo:
	@echo "make demo: implemented on build day 2 (scenario runner + comparison + scorecard)"

# rebuild scorecard_fact from the topics (ScorecardReplay): truncate, reset offsets, replay, write a snapshot, exit
replay:
	@mkdir -p $(LOGS)
	PG_PASSWORD=$$(cat $(PG_PWFILE)) SPRING_PROFILES_ACTIVE=local,demo ./gradlew bootRun -q --args="--scorecard.replay=true --valuationloop.scorecard.listener.auto-startup=false --scorecard.replay.snapshot=$(LOGS)/scorecard-snapshot.json --scorecard.replay.exit=true"
